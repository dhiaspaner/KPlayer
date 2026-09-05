package kplayer.videoplayer

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.PlaybackError
import kplayer.core.state.toPlaybackError
import kplayer.videoplayer.frame.FrameBuffer
import kplayer.videoplayer.frame.FrameOutputFailures
import kplayer.videoplayer.frame.FramePump
import kplayer.videoplayer.frame.PixelSource
import kplayer.videoplayer.frame.VideoFrame
import kplayer.videoplayer.frame.VideoFrameSource
import kplayer.videoplayer.mac.CMTime
import kplayer.videoplayer.mac.CoreVideo
import kplayer.videoplayer.mac.ObjC
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [MediaEngine] backed by AVFoundation on macOS, driven through the Objective-C
 * runtime with JNA (see [ObjC]).
 *
 * This is the same `AVPlayer` `:video`'s iOS backend uses; what differs is how it
 * is *observed*. On iOS the engine registers KVO observers. Here it cannot: JNA
 * can call into Objective-C but cannot define a class, so there is no object to
 * receive `observeValueForKeyPath:…`. The engine therefore **polls** — every
 * callback below is synthesised from a property read on a timer.
 *
 * That is a deliberate trade against the alternative, a JNI/Objective-C shim
 * compiled into the repo:
 *
 * - **Polling costs latency and a thread.** State changes are reported up to
 *   [POLL_INTERVAL_MS] late, and end-of-media is inferred rather than announced.
 * - **A shim costs a build.** Every consumer would need a toolchain, a per-arch
 *   dylib and a packaging story, and its absence is an `UnsatisfiedLinkError` at
 *   the first `play()` rather than a message.
 *
 * Polling wins because the shape already exists: `EngineMediaPlayer` polls
 * [currentPositionMs] regardless, so this adds a cadence, not a concept.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the
 * translation of `AVPlayer` state into the vocabulary [MediaEngine.events] carries.
 */
internal class AvFoundationVideoEngine : AbstractMediaEngine(), VideoFrameSource, PixelSource {

    /** The `AVPlayerItemVideoOutput` decoded frames are pulled from. */
    private var videoOutput: Pointer? = null

    private val frames = FrameBuffer()

    /**
     * The same policy iOS uses; see [FramePump]. The AVFoundation calls differ —
     * `objc_msgSend` here, cinterop there — but when to make them does not.
     */
    private val pump = FramePump(frames, this)

    /** On by default — see [setFrameOutputEnabled] for why desktop differs from iOS. */
    private var frameOutputEnabled = true

    private var framePumpHandle: ScheduledFuture<*>? = null

    /**
     * The first failure the frame pump hit, for diagnostics. Never surfaces as a
     * playback error — see [pumpFrame].
     */
    private val failures = FrameOutputFailures()

    override val frameOutputFailure: StateFlow<String?> get() = failures.failure

    /** The same value, unwrapped, for tests and for anything reading it once. */
    internal val framePumpError: String? get() = failures.failure.value

    /** The `AVPlayer`, retained. Null until [prepare]. */
    private var player: Pointer? = null

    /** The `AVPlayerItem`, retained, so status and buffering can be read. */
    private var item: Pointer? = null

    /** The `NSURL` for the pending source, retained between [setSource] and [prepare]. */
    private var url: Pointer? = null

    /**
     * One daemon thread for the whole engine. Daemon because a desktop app that
     * forgets to `release()` must still be able to exit.
     */
    /**
     * Guards every message sent to the native player, item and video output.
     *
     * Teardown runs on whichever thread called `release`/`setFrameOutputEnabled`
     * while the poller thread may be mid-tick, and `ScheduledFuture.cancel(false)`
     * neither interrupts a running task nor waits for it. Without this the pump
     * could hold a local reference to an output that the other thread had just
     * released — and messaging a freed Objective-C object does not throw, it hits
     * whatever now occupies that memory. Observed as
     * `-[AVTelemetryInterval hasNewPixelBufferForItemTime:]: unrecognized
     * selector`, aborting the process.
     *
     * Reentrant, which matters: `prepare` takes it and calls `attachVideoOutput`,
     * which takes it again.
     */
    private val nativeLock = Any()

    private val poller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kplayer-avfoundation-poll").apply { isDaemon = true }
    }
    private var pollHandle: ScheduledFuture<*>? = null

    // ── Polled state, owned by the poll thread ────────────────────────────────

    private var reportedReady = false
    private var reportedCompleted = false
    private var lastPlaying = false
    private var lastBuffering = false

    /**
     * Re-applied after every transition into playing, because `-[AVPlayer play]`
     * and the end of a seek both reset `rate` to 1.0. The iOS engine works around
     * the identical wart; see `AvAudioEngine`.
     */
    private var speed = 1f

    override fun setSource(source: MediaSource): Boolean {
        val spec = when (source) {
            is MediaSource.Url -> source.value
            is MediaSource.FilePath -> source.path
            is MediaSource.AndroidUriString -> source.value
            is MediaSource.Custom -> source.value
        }
        if (spec.isBlank()) return false

        return ObjC.autoreleasing {
            val nsSpec = ObjC.nsString(spec)
            try {
                // A path with a scheme is already a URL; fileURLWithPath: would
                // percent-escape the "://" into a path that resolves to nothing.
                val created = if (spec.contains("://")) {
                    ObjC.send(ObjC.cls("NSURL"), "URLWithString:", nsSpec)
                } else {
                    ObjC.send(ObjC.cls("NSURL"), "fileURLWithPath:", nsSpec)
                }
                // NSURL returns nil for a string it cannot parse. Nothing has
                // changed yet at this point, which is what `false` promises.
                if (created == null) return@autoreleasing false

                releaseUrl()
                url = ObjC.retain(created)
                true
            } finally {
                ObjC.release(nsSpec)
            }
        }
    }

    override fun prepare() = synchronized(nativeLock) {
        val source = url ?: return
        teardownPlayer()

        reportedReady = false
        reportedCompleted = false
        lastPlaying = false
        lastBuffering = false
        // A reason belonging to the previous item must not sit on the new one's
        // diagnostics, and first-wins would otherwise hide whatever this item hits.
        failures.clear()

        ObjC.autoreleasing {
            val newItem = ObjC.send(ObjC.cls("AVPlayerItem"), "playerItemWithURL:", source)
            if (newItem == null) {
                reportError(PlaybackError.Source("AVFoundation could not open the source"))
                return@autoreleasing
            }
            val newPlayer = ObjC.send(ObjC.cls("AVPlayer"), "playerWithPlayerItem:", newItem)
            if (newPlayer == null) {
                reportError(PlaybackError.Unknown("AVFoundation could not create a player"))
                return@autoreleasing
            }
            // Both factories return autoreleased objects and the pool drains on the
            // way out of this block, so anything kept has to be retained here.
            item = ObjC.retain(newItem)
            player = ObjC.retain(newPlayer)
        }

        if (player == null) return

        if (frameOutputEnabled) attachVideoOutput()

        // Loading has begun and no frame is available yet — reported as buffering
        // rather than left silent, so the UI has something to show before onReady.
        lastBuffering = true
        reportBuffering(true)
        startPolling()
    }

    override fun latestFrame(): VideoFrame? = frames.latest()

    /**
     * Unlike iOS, desktop defaults this **on**: there is no native view to hand
     * frames to, so pulling them is the only way to get a picture at all and a
     * surface that never asked would show nothing. Turning it off is still
     * worthwhile for audio-only playback of a video-capable engine.
     */
    override fun setFrameOutputEnabled(enabled: Boolean) = synchronized(nativeLock) {
        if (enabled == frameOutputEnabled) return
        frameOutputEnabled = enabled
        if (enabled) {
            if (videoOutput != null) startFramePump() else attachVideoOutput()
        } else {
            releaseVideoOutput()
        }
    }

    /**
     * Attaches an `AVPlayerItemVideoOutput` so decoded frames can be pulled.
     *
     * The alternative is an `AVPlayerLayer` in an `NSView`, which is zero-copy and
     * hardware-composited — but it needs a real view in the window hierarchy, which
     * means AppKit interop and Compose's layering. Pulling pixel buffers costs one
     * copy per frame and works with nothing on screen at all, so it is the path
     * that can be tested and the one to start from.
     *
     * A failure here is not fatal: the audio track still plays and the transport
     * still works, so the engine reports it and carries on without frames.
     */
    private fun attachVideoOutput() = synchronized(nativeLock) {
        // Not a failure: `prepare` attaches before there is an item on a load that
        // is still in flight, and `ensureAttached` retries every tick.
        val currentItem = item ?: return

        ObjC.autoreleasing {
            // @{ kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA) }.
            // Asking for BGRA up front makes AVFoundation do any conversion from
            // the codec's native YUV itself, on the GPU where it belongs — doing it
            // here would be a colour-space conversion per frame in Java.
            val format = ObjC.send(
                ObjC.cls("NSNumber"),
                "numberWithInt:",
                CoreVideo.PIXEL_FORMAT_32_BGRA,
            ) ?: return@autoreleasing failures.report("could not box the BGRA pixel format")

            val attributes = ObjC.send(
                ObjC.cls("NSDictionary"),
                "dictionaryWithObject:forKey:",
                format,
                CoreVideo.kCVPixelBufferPixelFormatTypeKey,
            ) ?: return@autoreleasing failures.report("could not build the pixel-buffer attributes")

            val output = ObjC.send(ObjC.cls("AVPlayerItemVideoOutput"), "alloc")
                ?.let { ObjC.send(it, "initWithPixelBufferAttributes:", attributes) }

            if (output == null) {
                failures.report("AVFoundation could not create a video output")
                reportError(PlaybackError.Unknown("AVFoundation could not create a video output"))
                return@autoreleasing
            }

            // -initWith… returns +1 already, so it is owned rather than autoreleased
            // and must not be retained again here.
            videoOutput = output
            ObjC.sendVoid(currentItem, "addOutput:", output)
        }

        if (videoOutput != null) startFramePump()
    }

    /**
     * How many outputs the current `AVPlayerItem` actually holds.
     *
     * `addOutput:` returns nothing and fails silently, so "we called it" and "it
     * took effect" are different claims — this is what lets a test assert the
     * second one, and what distinguishes a missing attachment from a decoder that
     * simply has no frame yet.
     */
    internal fun attachedOutputCount(): Int = synchronized(nativeLock) {
        val currentItem = item ?: return 0
        val outputs = ObjC.send(currentItem, "outputs") ?: return 0
        ObjC.sendLong(outputs, "count").toInt()
    }

    override fun play() {
        // setRate: rather than play(): they are the same call for speed 1.0, and
        // play() would stamp on a non-default speed. Nothing is reported here —
        // the poller will see timeControlStatus move, which is the fact.
        player?.let { ObjC.sendVoid(it, "setRate:", speed) }
    }

    override fun pause() {
        player?.let { ObjC.sendVoid(it, "pause") }
    }

    override fun seekTo(positionMs: Long) {
        val target = player ?: return
        // A seek away from the end reopens the item, so a completion already
        // reported must not suppress the next one.
        reportedCompleted = false
        ObjC.sendVoid(target, "seekToTime:", CMTime.fromMillis(positionMs))
        // Seeking while paused moves the picture without advancing time, so
        // nothing is "new" and the cheap gate would hold the pre-seek frame.
        pump.requestRefresh()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        // Only while playing: setRate: on a paused player is how you *start* it,
        // so applying it here would resume playback nobody asked to resume.
        val target = player ?: return
        if (ObjC.sendLong(target, "timeControlStatus") == TIME_CONTROL_PLAYING) {
            ObjC.sendVoid(target, "setRate:", speed)
        }
    }

    override fun setVolume(volume: Float) {
        player?.let { ObjC.sendVoid(it, "setVolume:", volume) }
    }

    override fun currentPositionMs(): Long {
        val target = player ?: return 0L
        return ObjC.sendTime(target, "currentTime").toMillisOrNull() ?: 0L
    }

    override fun release() = synchronized(nativeLock) {
        pollHandle?.cancel(false)
        pollHandle = null
        poller.shutdownNow()
        teardownPlayer()
        releaseUrl()
    }

    // ── The poll loop ─────────────────────────────────────────────────────────

    private fun startPolling() {
        pollHandle?.cancel(false)
        pollHandle = poller.scheduleWithFixedDelay(
            ::poll,
            0,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * One tick: read the item and the player, and report whatever changed.
     *
     * Wrapped in `runCatching` because this runs on a scheduled executor, where an
     * escaping exception silently cancels all future ticks — the player would
     * simply stop updating with nothing logged.
     */
    private fun poll() {
        runCatching { synchronized(nativeLock) { pollOnce() } }.onFailure {
            // A throw from a poll tick is a JVM exception like any other, so it
            // goes through the same classifier the action boundary uses.
            reportError(it.toPlaybackError())
        }
    }

    private fun pollOnce() {
        val currentItem = item ?: return
        val currentPlayer = player ?: return

        when (ObjC.sendLong(currentItem, "status")) {
            STATUS_FAILED -> {
                stopPolling()
                reportBuffering(false)
                reportError(itemError(currentItem))
                return
            }

            STATUS_READY -> if (!reportedReady) {
                reportedReady = true
                val duration = ObjC.sendTime(currentItem, "duration").toMillisOrNull()
                lastBuffering = false
                reportBuffering(false)
                // 0, not the null we got: a live stream has no duration, and that
                // is exactly what onReady's contract says 0 means.
                reportReady(duration ?: 0L)
            }

            else -> return // still unknown; nothing to report yet
        }

        val timeControl = ObjC.sendLong(currentPlayer, "timeControlStatus")

        // Completion is checked before the playing transition, and this ordering is
        // the whole wart. AVFoundation stops at the end of an item by dropping
        // timeControlStatus to paused — the same value a real pause produces — so
        // reporting the transition first would step the player visibly through
        // Paused on its way to Completed. Both HTML engines and ExoVideoEngine
        // swallow the identical event.
        if (!reportedCompleted && timeControl == TIME_CONTROL_PAUSED && isAtEnd(currentItem, currentPlayer)) {
            reportedCompleted = true
            lastPlaying = false
            if (lastBuffering) {
                lastBuffering = false
                reportBuffering(false)
            }
            reportCompleted()
            return
        }

        // waitingToPlayAtSpecifiedRate is AVFoundation stalling for data while it
        // still intends to play — buffering, not a pause. Reporting it as a pause
        // would have the player leave Playing every time the network hiccups.
        val buffering = timeControl == TIME_CONTROL_WAITING ||
            ObjC.sendBoolean(currentItem, "isPlaybackBufferEmpty")
        if (buffering != lastBuffering) {
            lastBuffering = buffering
            reportBuffering(buffering)
        }

        val playing = timeControl == TIME_CONTROL_PLAYING
        if (playing != lastPlaying) {
            lastPlaying = playing
            reportPlaying(playing)
        }
    }

    /**
     * Whether the item has played through.
     *
     * Inferred, because there is no property to read: the notification that would
     * say so, `AVPlayerItemDidPlayToEndTime`, needs an observer object we cannot
     * create. [END_TOLERANCE_MS] covers the last frame's worth of rounding — a
     * 30fps item stops ~33ms short of its stated duration.
     */
    private fun isAtEnd(currentItem: Pointer, currentPlayer: Pointer): Boolean {
        val duration = ObjC.sendTime(currentItem, "duration").toMillisOrNull() ?: return false
        if (duration <= 0L) return false // live stream: never "at the end"
        val position = ObjC.sendTime(currentPlayer, "currentTime").toMillisOrNull() ?: return false
        return position >= duration - END_TOLERANCE_MS
    }

    /**
     * The failed item's `NSError`, classified.
     *
     * Reads `domain`, `code` and `localizedDescription` off the error by
     * `objc_msgSend` and hands them to `:core` as a [NativeError] — the same seam,
     * and behind it the same table, that iOS reaches through Kotlin/Native interop.
     * Two entirely different routes to AVFoundation, one classification.
     */
    private fun itemError(currentItem: Pointer): PlaybackError = ObjC.autoreleasing {
        val error = ObjC.send(currentItem, "error")
            ?: return@autoreleasing PlaybackError.Source("AVFoundation failed to load the source")
        NativeError.avError(
            domain = ObjC.javaString(ObjC.send(error, "domain")),
            code = ObjC.sendLong(error, "code"),
            description = ObjC.javaString(ObjC.send(error, "localizedDescription")),
        ).toPlaybackError()
    }

    // ── The frame pump ────────────────────────────────────────────────────────

    /**
     * Separate from the state poller, and much faster.
     *
     * State changes at human speed and is read at [POLL_INTERVAL_MS]; frames
     * arrive at the media's frame rate, so pumping them on the same 100 ms cadence
     * would cap playback at 10fps. Both run on the same single-threaded executor,
     * which keeps every `objc_msgSend` in this engine on one thread and means the
     * state poll and a 33 MB frame copy can never overlap.
     */
    private fun startFramePump() {
        framePumpHandle?.cancel(false)
        framePumpHandle = poller.scheduleWithFixedDelay(
            ::pumpFrame,
            0,
            FRAME_PUMP_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * A frame that fails to arrive is a dropped frame, not a playback failure, so
     * this never calls `onError` — at 16 ms it would bury the log and put the
     * player into `Error` over something cosmetic.
     *
     * It is not swallowed silently either. The *first* failure is kept in
     * [framePumpError], because a pump that throws on every tick and says nothing
     * looks exactly like a pump that is working on a video with no frames — which
     * cost an hour the first time.
     */
    private fun pumpFrame() {
        // The whole tick under the lock, copy included: a 4K copy is a few
        // milliseconds, and briefly blocking a `setFrameOutputEnabled` is a much
        // better trade than reading a buffer that is being freed underneath it.
        runCatching { synchronized(nativeLock) { pump.tick() } }.onFailure { failure ->
            failures.report("${failure::class.simpleName}: ${failure.message}")
        }
    }

    // ── PixelSource: the platform half of the shared pump ────────────────────

    override fun ensureAttached(): Boolean {
        if (videoOutput == null && frameOutputEnabled) attachVideoOutput()
        return videoOutput != null
    }

    override fun hasNewFrame(): Boolean {
        val output = videoOutput ?: return false
        val currentPlayer = player ?: return false
        val itemTime = ObjC.sendTime(currentPlayer, "currentTime")
        if (!itemTime.isValid) return false
        return ObjC.sendBoolean(output, "hasNewPixelBufferForItemTime:", itemTime)
    }

    override fun publishCurrentFrame(into: FrameBuffer): Boolean {
        val output = videoOutput ?: return false
        val currentPlayer = player ?: return false

        val itemTime = ObjC.sendTime(currentPlayer, "currentTime")
        if (!itemTime.isValid) return false

        // Not gated on hasNewPixelBufferForItemTime: the pump decides that, and
        // deliberately bypasses it for the first frame and after a seek, which is
        // what lets a paused player draw at all.
        val pixelBuffer = ObjC.send(
            output,
            "copyPixelBufferForItemTime:itemTimeForDisplay:",
            itemTime,
            Pointer.NULL,
        ) ?: return false

        return try {
            val width = CoreVideo.width(pixelBuffer)
            val height = CoreVideo.height(pixelBuffer)
            val rowBytes = CoreVideo.bytesPerRow(pixelBuffer)

            val copied = CoreVideo.withLockedBytes(pixelBuffer) { base ->
                into.publish(width, height, rowBytes) { destination ->
                    // One bulk copy out of the locked buffer. The lock is held for
                    // exactly this long — anything else in here would be blocking
                    // the decoder from reusing the buffer.
                    base.read(0, destination, 0, destination.size)
                }
            } != null

            if (!copied) {
                // Either the lock failed or the buffer has no single base address,
                // which means it is **planar** — the pixel-format request did not
                // take effect and the decoder is vending its native YUV. Silent
                // here is exactly how that bug hid on iOS: a black surface and no
                // error anywhere. Desktop has no CoreImage fallback yet, so it says
                // so instead.
                failures.report(
                    "pixel buffer could not be read as packed BGRA (${width}x$height, " +
                        "stride $rowBytes) — the decoder is probably vending planar YUV"
                )
            }
            copied
        } finally {
            // "copy" in the selector name means we own it. Without this the
            // decoder leaks a full frame every tick.
            CoreVideo.release(pixelBuffer)
        }
    }

    private fun stopFramePump() {
        framePumpHandle?.cancel(false)
        framePumpHandle = null
    }

    private fun releaseVideoOutput() = synchronized(nativeLock) {
        stopFramePump()
        pump.reset()
        val output = videoOutput ?: return
        // Detach before releasing: the item retains its outputs, so releasing a
        // still-attached output leaves the item holding a dangling reference.
        item?.let { ObjC.sendVoid(it, "removeOutput:", output) }
        ObjC.release(output)
        videoOutput = null
        frames.clear()
    }

    private fun stopPolling() {
        pollHandle?.cancel(false)
        pollHandle = null
    }

    private fun teardownPlayer() = synchronized(nativeLock) {
        stopPolling()
        // Before the item goes: removeOutput: needs it to still be alive.
        releaseVideoOutput()
        player?.let {
            ObjC.sendVoid(it, "pause")
            ObjC.release(it)
        }
        player = null
        ObjC.release(item)
        item = null
    }

    private fun releaseUrl() {
        ObjC.release(url)
        url = null
    }

    private companion object {
        /**
         * Fast enough that a pause reads as instant, slow enough that a dozen
         * `objc_msgSend`s per tick stay invisible. `EngineMediaPlayer`'s own
         * position sync runs at its own cadence and is unaffected by this.
         */
        const val POLL_INTERVAL_MS = 100L

        /**
         * ~60fps. Not synchronised to the media's frame rate, because
         * `hasNewPixelBufferForItemTime:` already answers "is there anything new?"
         * — over-polling costs one cheap message send, under-polling drops frames.
         */
        const val FRAME_PUMP_INTERVAL_MS = 16L

        /** One frame at 30fps, rounded up. */
        const val END_TOLERANCE_MS = 40L

        // AVPlayerItemStatus
        const val STATUS_READY = 1L
        const val STATUS_FAILED = 2L

        // AVPlayerTimeControlStatus
        const val TIME_CONTROL_PAUSED = 0L
        const val TIME_CONTROL_WAITING = 1L
        const val TIME_CONTROL_PLAYING = 2L
    }
}
