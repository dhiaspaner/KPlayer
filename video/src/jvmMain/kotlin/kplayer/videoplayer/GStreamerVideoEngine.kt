package kplayer.videoplayer

import kotlinx.coroutines.flow.StateFlow
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.toPlaybackError
import kplayer.videoplayer.frame.FrameBuffer
import kplayer.videoplayer.frame.FrameOutputFailures
import kplayer.videoplayer.frame.VideoFrame
import kplayer.videoplayer.frame.VideoFrameSource
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.Caps
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.FlowReturn
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.GstObject
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.AppSink
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.event.SeekType
import java.io.File
import java.net.URI
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * [MediaEngine] backed by GStreamer on Linux — `playbin` decoding into an
 * `appsink`.
 *
 * > **Unverified.** Written against the `gst1-java-core` API on a machine with no
 * > GStreamer natives installed, so it has never been executed. The bus-message
 * > translation is a close copy of `:audio`'s `GStreamerAudioEngine`, which *is*
 * > exercised; the `appsink` frame path below is new and entirely untested.
 *
 * The counterpart of the other two desktop engines, and the one that differs
 * most: `playbin` pushes frames at us through a `NEW_SAMPLE` callback, so unlike
 * macOS and Windows there is nothing to poll for pictures. Playback *state* is
 * still reported through the bus, which is a real callback too — GStreamer is the
 * only desktop stack of the three that does not need synthesising, because
 * `gst1-java-core` already owns the native callback object JNA cannot create.
 *
 * **The GStreamer natives must be installed**: `apt install libgstreamer1.0-0
 * gstreamer1.0-plugins-base gstreamer1.0-plugins-good`. `gst1-java-core` is
 * bindings only, and `DesktopVideoEngines.isAvailable` probes for them so a
 * missing install is a message rather than an `UnsatisfiedLinkError`.
 */
internal class GStreamerVideoEngine : AbstractMediaEngine(), VideoFrameSource {

    private val playBin: PlayBin = PlayBin("kplayer-video")

    private val frames = FrameBuffer()

    /** Why no picture, when there is none. See [VideoFrameSource.frameOutputFailure]. */
    private val failures = FrameOutputFailures()

    override val frameOutputFailure: StateFlow<String?> get() = failures.failure

    /** On by default — see [setFrameOutputEnabled]. */
    private var frameOutputEnabled = true

    /** Set once metadata is known, so `Ready` is reported exactly once per source. */
    private var reportedReady = false

    /**
     * `appsink` rather than a windowed sink, for the same reason macOS pulls pixel
     * buffers: it renders nowhere, which is what makes it testable and what lets
     * Compose own the surface. A `glimagesink` in an X11/Wayland window would be
     * faster and is the eventual zero-copy path.
     */
    private val videoSink: AppSink = (ElementFactory.make("appsink", "kplayer-video-sink") as AppSink).apply {
        // BGRx, not BGRA: `playbin` will happily hand over an alpha channel it had
        // to invent, and video has no transparency to carry. The byte layout is
        // identical, so the padding byte simply lands where alpha would.
        caps = Caps("video/x-raw,format=BGRx")
        set("emit-signals", true)
        // Without these, a paused renderer would let the pipeline queue frames until
        // it ran out of memory. One-deep and dropping matches FrameBuffer's own
        // latest-wins policy, so the two do not fight.
        set("max-buffers", 1)
        set("drop", true)
        set("sync", true)
    }

    private val sampleListener = AppSink.NEW_SAMPLE { sink ->
        val sample = sink.pullSample() ?: return@NEW_SAMPLE FlowReturn.OK
        // Pulled and dropped rather than left unpulled: an appsink whose samples
        // are never collected blocks the pipeline once its queue fills.
        if (!frameOutputEnabled) {
            sample.dispose()
            return@NEW_SAMPLE FlowReturn.OK
        }
        try {
            // Nothing in here may throw past this point: a NEW_SAMPLE callback that
            // propagates an exception into GStreamer's streaming thread takes the
            // pipeline down. The reason is kept instead, which is also the only way
            // a caps or stride surprise becomes visible — otherwise it is a black
            // surface and a healthy-looking transport.
            runCatching {
                val structure = sample.caps.getStructure(0)
                val width = structure.getInteger("width")
                val height = structure.getInteger("height")
                val buffer = sample.buffer

                val mapped = buffer.map(false)
                if (mapped == null) {
                    failures.report("GStreamer buffer could not be mapped for reading")
                    return@runCatching
                }
                try {
                    // GStreamer's own stride, not width * 4: the negotiated caps
                    // do not carry padding, so this is derived from what actually
                    // arrived. A hardware decoder can align rows well past width.
                    val rowBytes = mapped.remaining() / height
                    frames.publish(width, height, rowBytes) { destination ->
                        mapped.duplicate().get(destination, 0, minOf(destination.size, mapped.remaining()))
                    }
                } finally {
                    buffer.unmap()
                }
            }.onFailure { failure ->
                failures.report("${failure::class.simpleName}: ${failure.message}")
            }
        } finally {
            // gst1-java-core does not free samples on GC promptly enough for video;
            // an unreleased sample per frame holds the whole decoded buffer.
            sample.dispose()
        }
        FlowReturn.OK
    }

    private val eosListener = Bus.EOS {
        reportBuffering(false)
        reportCompleted()
    }

    private val errorListener = Bus.ERROR { _: GstObject, code: Int, message: String ->
        reportError(NativeError.gstreamer(code, message).toPlaybackError())
    }

    private val bufferingListener = Bus.BUFFERING { _: GstObject, percent: Int ->
        reportBuffering(percent < 100)
    }

    /**
     * `playbin` has no "ready" message — reaching `PLAYING` or `PAUSED` is what
     * tells us the pipeline pre-rolled and duration is queryable.
     */
    private val stateListener = Bus.STATE_CHANGED { source: GstObject, _: State, current: State, _: State ->
        if (source != playBin) return@STATE_CHANGED
        when (current) {
            State.PAUSED, State.PLAYING -> {
                if (!reportedReady) {
                    reportedReady = true
                    reportBuffering(false)
                    reportReady(queryDurationMs())
                }
                reportPlaying(current == State.PLAYING)
            }

            else -> Unit
        }
    }

    init {
        playBin.setVideoSink(videoSink)
        videoSink.connect(sampleListener)
        with(playBin.bus) {
            connect(eosListener)
            connect(errorListener)
            connect(bufferingListener)
            connect(stateListener)
        }
    }

    override fun setSource(source: MediaSource): Boolean {
        val uri = when (source) {
            is MediaSource.Url -> source.value
            // playbin takes a URI, never a path, and a bare path silently resolves
            // to nothing rather than failing.
            is MediaSource.FilePath -> File(source.path).toURI().toString()
            is MediaSource.AndroidUriString -> source.value
            is MediaSource.Custom -> source.value
        }
        if (uri.isBlank()) return false

        reportedReady = false
        frames.clear()
        // The previous item's reason must not be shown against this one.
        failures.clear()
        playBin.setURI(URI.create(uri))
        return true
    }

    override fun prepare() {
        // PAUSED, not PLAYING: pre-rolls the pipeline so duration becomes
        // queryable and the first frame is decoded, without starting playback that
        // EngineMediaPlayer has not asked for.
        reportBuffering(true)
        playBin.state = State.PAUSED
    }

    override fun play() {
        playBin.state = State.PLAYING
    }

    override fun pause() {
        playBin.state = State.PAUSED
    }

    override fun seekTo(positionMs: Long) {
        playBin.seek(positionMs, TimeUnit.MILLISECONDS)
    }

    /**
     * GStreamer has no playback-rate property: speed is a seek that carries a rate,
     * which is why this reads the current position first. `:audio`'s engine does
     * the identical dance.
     */
    override fun setSpeed(speed: Float) {
        // Byte-for-byte the dance GStreamerAudioEngine does, including seeking back
        // to the current position: a rate-only seek still needs a start, and
        // SeekType.NONE there would restart the stream at zero.
        playBin.seek(
            speed.toDouble(),
            Format.TIME,
            EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
            SeekType.SET,
            playBin.queryPosition(TimeUnit.NANOSECONDS),
            SeekType.NONE,
            -1L,
        )
    }

    /** `playbin`'s volume is a linear 0..1 double, same range as the shared contract. */
    override fun setVolume(volume: Float) {
        playBin.set("volume", volume.toDouble())
    }

    override fun currentPositionMs(): Long =
        playBin.queryPosition(TimeUnit.MILLISECONDS).coerceAtLeast(0L)

    override fun latestFrame(): VideoFrame? = frames.latest()

    /**
     * On by default, as on macOS: `appsink` *is* the video sink, so there is no
     * picture at all without it.
     *
     * Disabling drops the frames rather than the pipeline — `max-buffers=1` and
     * `drop=true` mean GStreamer discards them at the sink instead of stalling —
     * so playback and the audio track continue untouched.
     */
    override fun setFrameOutputEnabled(enabled: Boolean) {
        if (enabled == frameOutputEnabled) return
        frameOutputEnabled = enabled
        if (!enabled) frames.clear()
    }

    override fun release() {
        videoSink.disconnect(sampleListener)
        with(playBin.bus) {
            disconnect(eosListener)
            disconnect(errorListener)
            disconnect(bufferingListener)
            disconnect(stateListener)
        }
        playBin.state = State.NULL
        playBin.dispose()
        frames.clear()
    }

    private fun queryDurationMs(): Long =
        playBin.queryDuration(TimeUnit.MILLISECONDS).coerceAtLeast(0L)
}
