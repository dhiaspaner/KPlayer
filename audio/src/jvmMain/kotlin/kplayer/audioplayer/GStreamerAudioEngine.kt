package kplayer.audioplayer

import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.toPlaybackError
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.GstObject
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.PlayBin
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * [MediaEngine] backed by GStreamer, via `gst1-java-core`.
 *
 * This is the Linux backend Compose Media Player uses, applied to all three desktop
 * OSes: `playbin` auto-plugs a decoder and an audio sink per platform, so one
 * implementation covers Windows, macOS and Linux.
 *
 * **The GStreamer natives must be installed on the machine** — `brew install
 * gstreamer`, `apt install libgstreamer1.0-0 gstreamer1.0-plugins-{base,good}`, or the
 * Windows MSI. `gst1-java-core` is bindings only. [isAvailable] reports whether the
 * native library could be loaded, so a caller can fail informatively rather than at
 * the first `play()`.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the translation
 * of GStreamer bus messages into the vocabulary [MediaEngine.events] carries.
 */
internal class GStreamerAudioEngine : AbstractMediaEngine() {

    private val playBin: PlayBin = PlayBin("kplayer-audio")

    /** Set once metadata is known, so `Ready` is reported exactly once per source. */
    private var reportedReady = false

    private val eosListener = Bus.EOS {
        reportBuffering(false)
        reportCompleted()
    }

    private val errorListener = Bus.ERROR { _: GstObject, code: Int, message: String ->
        reportError(NativeError.gstreamer(code, message).toPlaybackError())
    }

    private val bufferingListener = Bus.BUFFERING { _: GstObject, percent: Int ->
        // playbin reports a 0..100 fill level; anything short of full is a stall.
        reportBuffering(percent < 100)
    }

    /**
     * `playbin` has no "ready" message — reaching `PLAYING` or `PAUSED` is what tells
     * us the pipeline pre-rolled and duration is queryable.
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

    private fun queryDurationMs(): Long =
        playBin.queryDuration(TimeUnit.MILLISECONDS).coerceAtLeast(0L)

    init {
        with(playBin.bus) {
            connect(eosListener)
            connect(errorListener)
            connect(bufferingListener)
            connect(stateListener)
        }
    }

    /**
     * GStreamer takes a URI, so a local path has to be converted — `File.toURI()`
     * produces the `file:` form it expects.
     */
    override fun setSource(source: MediaSource): Boolean {
        val uri = runCatching {
            when (source) {
                is MediaSource.Url -> URI.create(source.value)
                is MediaSource.FilePath -> File(source.path).toURI()
                is MediaSource.AndroidUriString -> URI.create(source.value)
                is MediaSource.Custom -> URI.create(source.value)
            }
        }.getOrNull() ?: return false

        reportedReady = false
        // A source swap needs the pipeline back to NULL, or playbin keeps the old one.
        playBin.state = State.NULL
        playBin.setURI(uri)
        return true
    }

    /**
     * `PAUSED` is GStreamer's pre-roll state: it builds the pipeline and decodes the
     * first frames without playing, which is exactly "prepare".
     */
    override fun prepare() {
        reportBuffering(true)
        playBin.state = State.PAUSED
    }

    override fun play() {
        playBin.play()
    }

    override fun pause() {
        playBin.pause()
    }

    override fun seekTo(positionMs: Long) {
        playBin.seek(positionMs, TimeUnit.MILLISECONDS)
    }

    /**
     * GStreamer has no rate property — playback speed is a seek with a rate, applied
     * from the current position.
     */
    override fun setSpeed(speed: Float) {
        playBin.seek(
            speed.toDouble(),
            Format.TIME,
            java.util.EnumSet.of(
                org.freedesktop.gstreamer.event.SeekFlags.FLUSH,
                org.freedesktop.gstreamer.event.SeekFlags.ACCURATE,
            ),
            org.freedesktop.gstreamer.event.SeekType.SET,
            playBin.queryPosition(TimeUnit.NANOSECONDS),
            org.freedesktop.gstreamer.event.SeekType.NONE,
            -1L,
        )
    }

    /** `playbin`'s volume is a linear 0..1 double, same range as the shared contract. */
    override fun setVolume(volume: Float) {
        playBin.set("volume", volume.toDouble())
    }

    override fun currentPositionMs(): Long =
        playBin.queryPosition(TimeUnit.MILLISECONDS).coerceAtLeast(0L)

    override fun release() {
        with(playBin.bus) {
            disconnect(eosListener)
            disconnect(errorListener)
            disconnect(bufferingListener)
            disconnect(stateListener)
        }
        playBin.state = State.NULL
        playBin.dispose()
    }
}
