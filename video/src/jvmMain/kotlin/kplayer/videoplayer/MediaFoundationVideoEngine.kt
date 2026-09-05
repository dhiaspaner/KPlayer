package kplayer.videoplayer

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.videoplayer.frame.FrameBuffer
import kplayer.videoplayer.frame.FrameOutputFailures
import kplayer.videoplayer.frame.FramePump
import kplayer.videoplayer.frame.PixelSource
import kplayer.videoplayer.frame.VideoFrame
import kplayer.videoplayer.frame.VideoFrameSource
import kplayer.videoplayer.win.MediaEngineNotify
import kplayer.videoplayer.win.MediaFoundation
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [MediaEngine] backed by Media Foundation on Windows, through `IMFMediaEngine`
 * in frame-server mode.
 *
 * > **Unverified — written on macOS and never executed.** See
 * > `kplayer.videoplayer.win.MediaFoundation`'s class doc for how the vtable
 * > indices and GUIDs here were checked, and `video/README.md` § Desktop.
 *
 * Structurally the twin of [AvFoundationVideoEngine]: `IMFMediaEngine` offers no
 * way to observe state changes without a synthesised COM callback
 * ([MediaEngineNotify]), and that sink is left inert — every fact this engine
 * needs is polled from a `GetState`-shaped read, exactly as MFPlay was polled
 * before it. What is new relative to the MFPlay version this replaced is
 * [VideoFrameSource]: frame-server mode decodes without drawing anywhere, so
 * frames are pulled through [MediaFoundation.transferVideoFrame] into a WIC
 * bitmap and published through the same [FrameBuffer] / [FramePump] macOS uses,
 * which is what lets `:ui` draw Windows video exactly as it draws macOS and Linux
 * video — no more embedded native window.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the
 * translation of `IMFMediaEngine` state into the vocabulary [MediaEngine.events]
 * carries.
 */
internal class MediaFoundationVideoEngine : AbstractMediaEngine(), VideoFrameSource, PixelSource {

    private var engine: Pointer? = null

    /** Kept alive for exactly as long as [engine] — see [MediaEngineNotify]'s class doc. */
    private var notify: MediaEngineNotify? = null

    /** The URL accepted by [setSource], applied to the engine in [prepare]. */
    private var pendingUrl: String? = null

    /**
     * Created once and reused for the engine's whole lifetime — a WIC imaging
     * factory has no per-source state, unlike the bitmap it creates.
     */
    private var wicFactory: Pointer? = null

    /** The frame-server destination surface, sized to the source's native video size. */
    private var wicBitmap: Pointer? = null
    private var wicBitmapWidth = 0
    private var wicBitmapHeight = 0

    private val frames = FrameBuffer()
    private val pump = FramePump(frames, this)
    private val failures = FrameOutputFailures()

    override val frameOutputFailure: StateFlow<String?> get() = failures.failure

    /** On by default, as on macOS and Linux: there is no native view to hand frames to instead. */
    private var frameOutputEnabled = true

    private val poller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kplayer-mediafoundation-poll").apply { isDaemon = true }
    }
    private var pollHandle: ScheduledFuture<*>? = null
    private var framePumpHandle: ScheduledFuture<*>? = null

    private var reportedReady = false
    private var reportedCompleted = false
    private var lastPlaying = false
    private var lastBuffering = false

    /** Re-applied after every `Play()`, defensively — see `setSpeed`. */
    private var speed = 1f

    private var volume = 1f

    override fun setSource(source: MediaSource): Boolean {
        val url = when (source) {
            is MediaSource.Url -> source.value
            is MediaSource.FilePath -> source.path
            is MediaSource.AndroidUriString -> source.value
            is MediaSource.Custom -> source.value
        }
        if (url.isBlank()) return false
        // Nothing native happens here: the engine is (re)built in prepare(), so a
        // rejected source leaves the current one untouched as the contract asks.
        pendingUrl = url
        return true
    }

    override fun prepare() {
        val url = pendingUrl ?: return
        teardownEngine()

        reportedReady = false
        reportedCompleted = false
        lastPlaying = false
        lastBuffering = false
        failures.clear()

        // Per-thread, and prepare() is the first native call this engine makes.
        MediaFoundation.initializeThread()

        val sink = MediaEngineNotify()
        val created = MediaFoundation.createMediaEngine(sink)
        if (created == null) {
            reportError(PlaybackError.Unknown("Media Foundation could not create a media engine"))
            return
        }
        notify = sink
        engine = created

        reportBuffering(true)

        if (!MediaFoundation.setSource(created, url) || !MediaFoundation.load(created)) {
            reportBuffering(false)
            reportError(PlaybackError.Source("Media Foundation could not open $url"))
            teardownEngine()
            return
        }

        MediaFoundation.setVolume(created, volume.toDouble())
        startPolling()
        startFramePump()
    }

    override fun play() {
        val target = engine ?: return
        MediaFoundation.play(target)
        // Defensively re-applied: every other engine in this codebase re-asserts
        // its speed after starting playback rather than trusting the native side
        // to preserve a non-default rate across the call, and nothing in the
        // Media Foundation docs promises otherwise here.
        if (speed != 1f) MediaFoundation.setPlaybackRate(target, speed.toDouble())
    }

    override fun pause() {
        engine?.let { MediaFoundation.pause(it) }
    }

    override fun seekTo(positionMs: Long) {
        reportedCompleted = false
        engine?.let { MediaFoundation.setCurrentTimeSeconds(it, positionMs / 1000.0) }
        // Seeking while paused moves the picture without advancing time, so
        // nothing is "new" and the cheap gate in FramePump would hold the
        // pre-seek frame — see FramePump's doc.
        pump.requestRefresh()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        val target = engine ?: return
        if (!MediaFoundation.isPaused(target)) {
            MediaFoundation.setPlaybackRate(target, speed.toDouble())
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        engine?.let { MediaFoundation.setVolume(it, volume.toDouble()) }
    }

    override fun currentPositionMs(): Long =
        engine?.let { (MediaFoundation.currentTimeSeconds(it) * 1000).toLong().coerceAtLeast(0L) } ?: 0L

    override fun latestFrame(): VideoFrame? = frames.latest()

    override fun setFrameOutputEnabled(enabled: Boolean) {
        if (enabled == frameOutputEnabled) return
        frameOutputEnabled = enabled
        if (!enabled) {
            releaseWicBitmap()
            frames.clear()
        }
    }

    override fun release() {
        stopPolling()
        stopFramePump()
        poller.shutdownNow()
        teardownEngine()
        releaseWicFactory()
    }

    // ── State polling ─────────────────────────────────────────────────────────

    private fun startPolling() {
        pollHandle?.cancel(false)
        pollHandle = poller.scheduleWithFixedDelay(::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /** As on macOS: an escaping exception would silently cancel every later tick. */
    private fun poll() {
        runCatching { pollOnce() }.onFailure { reportError(PlaybackError.Unknown(it.message ?: it.toString())) }
    }

    private fun pollOnce() {
        val target = engine ?: return

        val errorCode = MediaFoundation.errorCode(target)
        if (errorCode != MF_MEDIA_ENGINE_ERR_NOERROR) {
            stopPolling()
            reportBuffering(false)
            reportError(PlaybackError.Source("Media Foundation reported error code $errorCode"))
            return
        }

        if (!reportedReady) {
            if (MediaFoundation.readyState(target) < MediaFoundation.READY_HAVE_METADATA) return
            val durationSeconds = MediaFoundation.durationSeconds(target)
            // 0, not NaN or +Inf: unknown and unbounded both mean "no duration",
            // matching onReady's contract for a live stream.
            val durationMs = if (durationSeconds.isFinite() && durationSeconds >= 0) {
                (durationSeconds * 1000).toLong()
            } else {
                0L
            }
            reportedReady = true
            reportBuffering(false)
            reportReady(durationMs)
        }

        if (!reportedCompleted && MediaFoundation.isEnded(target)) {
            reportedCompleted = true
            lastPlaying = false
            reportCompleted()
            return
        }

        val playing = !MediaFoundation.isPaused(target)
        // Waiting for data while trying to play is buffering; the same condition
        // while paused is not — a paused player is supposed to have nothing new.
        val buffering = playing && MediaFoundation.readyState(target) < MediaFoundation.READY_HAVE_FUTURE_DATA
        if (buffering != lastBuffering) {
            lastBuffering = buffering
            reportBuffering(buffering)
        }
        if (playing != lastPlaying) {
            lastPlaying = playing
            reportPlaying(playing)
        }
    }

    private fun stopPolling() {
        pollHandle?.cancel(false)
        pollHandle = null
    }

    // ── The frame pump ────────────────────────────────────────────────────────

    /**
     * Separate from the state poller and much faster, exactly as on macOS: state
     * changes at human speed, frames arrive at the media's frame rate, and both
     * run on the same single-threaded executor so no two native calls into this
     * engine ever overlap.
     */
    private fun startFramePump() {
        framePumpHandle?.cancel(false)
        framePumpHandle = poller.scheduleWithFixedDelay(::pumpFrame, 0, FRAME_PUMP_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopFramePump() {
        framePumpHandle?.cancel(false)
        framePumpHandle = null
    }

    /**
     * A frame that fails to arrive is a dropped frame, not a playback failure —
     * see [AvFoundationVideoEngine.pumpFrame], which this mirrors exactly.
     */
    private fun pumpFrame() {
        runCatching { pump.tick() }.onFailure { failures.report("${it::class.simpleName}: ${it.message}") }
    }

    // ── PixelSource: the platform half of the shared pump ────────────────────

    override fun ensureAttached(): Boolean {
        val target = engine ?: return false
        if (!frameOutputEnabled) return false
        if (!MediaFoundation.hasVideo(target)) return false

        val size = MediaFoundation.nativeVideoSize(target) ?: return false
        val (width, height) = size[0] to size[1]
        if (wicBitmap != null && width == wicBitmapWidth && height == wicBitmapHeight) return true

        releaseWicBitmap()
        val factory = wicFactory ?: MediaFoundation.createWicFactory()?.also { wicFactory = it }
        val bitmap = factory?.let { MediaFoundation.createBitmap(it, width, height) }
        if (bitmap == null) {
            failures.report("Media Foundation could not create a WIC destination bitmap")
            return false
        }
        wicBitmap = bitmap
        wicBitmapWidth = width
        wicBitmapHeight = height
        return true
    }

    override fun hasNewFrame(): Boolean {
        val target = engine ?: return false
        return MediaFoundation.onVideoStreamTick(target)
    }

    override fun publishCurrentFrame(into: FrameBuffer): Boolean {
        val target = engine ?: return false
        val bitmap = wicBitmap ?: return false

        if (!MediaFoundation.transferVideoFrame(target, bitmap, wicBitmapWidth, wicBitmapHeight)) return false

        val lock = MediaFoundation.lockBitmapForReading(bitmap) ?: run {
            failures.report("Media Foundation could not lock the WIC bitmap for reading")
            return false
        }
        return try {
            val stride = MediaFoundation.lockStride(lock)
            val data = MediaFoundation.lockDataPointer(lock)
            if (stride <= 0 || data == null) {
                failures.report("Media Foundation returned an empty WIC bitmap lock")
                return false
            }
            val (pointer, availableBytes) = data
            into.publish(wicBitmapWidth, wicBitmapHeight, stride) { destination ->
                pointer.read(0, destination, 0, minOf(destination.size, availableBytes))
            }
            true
        } finally {
            MediaFoundation.release(lock)
        }
    }

    private fun releaseWicBitmap() {
        wicBitmap?.let { MediaFoundation.release(it) }
        wicBitmap = null
        wicBitmapWidth = 0
        wicBitmapHeight = 0
    }

    private fun releaseWicFactory() {
        wicFactory?.let { MediaFoundation.release(it) }
        wicFactory = null
    }

    private fun teardownEngine() {
        stopPolling()
        stopFramePump()
        pump.reset()
        releaseWicBitmap()
        frames.clear()
        engine?.let { MediaFoundation.shutdown(it) }
        engine = null
        notify = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L

        /** ~60fps — see [AvFoundationVideoEngine]'s identical constant. */
        const val FRAME_PUMP_INTERVAL_MS = 16L

        const val MF_MEDIA_ENGINE_ERR_NOERROR = 0
    }
}
