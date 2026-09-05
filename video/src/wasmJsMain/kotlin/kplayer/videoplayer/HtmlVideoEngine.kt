package kplayer.videoplayer

import kotlinx.browser.document
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.toPlaybackError
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event

/**
 * [MediaEngine] backed by an HTML5 `<video>` element — the same approach Compose
 * Media Player takes for its web target.
 *
 * The audio counterpart is `:audio`'s `HtmlAudioEngine`; the difference is that this
 * element has to be placed in the document to be visible, so [videoElement] is the
 * web's render surface rather than a private detail.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the translation
 * of media-element events into the vocabulary [MediaEngine.events] carries.
 */
internal class HtmlVideoEngine : AbstractMediaEngine() {

    val videoElement: HTMLVideoElement =
        (document.createElement("video") as HTMLVideoElement).apply {
            preload = "auto"
            // The library supplies its own controls through :ui; the browser's would
            // fight them and issue transport commands behind the state machine's back.
            // `:ui`'s NativeVideoSurface turns them back on for showNativeControls.
            controls = false
            // Without this, iOS Safari hijacks play() into its own fullscreen player
            // — the element never renders in place, so the Compose surface it was
            // composed into shows nothing.
            setAttribute("playsinline", "")
        }

    /** `NaN` until metadata loads, `Infinity` for a live stream — both mean unknown. */
    private fun durationMs(): Long {
        val seconds = videoElement.duration
        return if (seconds.isNaN() || seconds.isInfinite()) 0L else (seconds * 1000.0).toLong()
    }

    private val onPlaying: (Event) -> Unit = { reportPlaying(true) }

    private val onPause: (Event) -> Unit = {
        // `pause` also fires immediately before `ended`; reporting it would read as a
        // pause and step the player through Paused on its way to Completed.
        if (!videoElement.ended) reportPlaying(false)
    }

    private val onWaiting: (Event) -> Unit = { reportBuffering(true) }

    private val onCanPlay: (Event) -> Unit = {
        reportBuffering(false)
        reportReady(durationMs())
    }

    private val onCanPlayThrough: (Event) -> Unit = { reportBuffering(false) }

    private val onEnded: (Event) -> Unit = {
        reportBuffering(false)
        reportCompleted()
    }

    private val onError: (Event) -> Unit = {
        // The code is the only detail there is: `MediaError.message` is empty in
        // most browsers and `org.w3c.dom.MediaError` does not even bind it. So the
        // code is carried through to :core rather than flattened into prose that
        // would classify as Unknown.
        reportError(
            NativeError.mediaElement(code = videoElement.error?.code?.toInt()).toPlaybackError()
        )
    }

    init {
        with(videoElement) {
            addEventListener("playing", onPlaying)
            addEventListener("pause", onPause)
            addEventListener("waiting", onWaiting)
            addEventListener("canplay", onCanPlay)
            addEventListener("canplaythrough", onCanPlayThrough)
            addEventListener("ended", onEnded)
            addEventListener("error", onError)
        }
    }

    /**
     * Every [MediaSource] variant is a URL string to a browser; a `FilePath` can only
     * be a served path or a `blob:` / `data:` URL, since a page cannot read the local
     * filesystem.
     */
    override fun setSource(source: MediaSource): Boolean {
        val url = when (source) {
            is MediaSource.Url -> source.value
            is MediaSource.FilePath -> source.path
            is MediaSource.AndroidUriString -> source.value
            is MediaSource.Custom -> source.value
        }
        if (url.isBlank()) return false
        videoElement.src = url
        return true
    }

    override fun prepare() {
        videoElement.load()
    }

    /** Rejects when autoplay policy blocks playback — before any user gesture. */
    override fun play() {
        videoElement.play().catch { error ->
            reportError(NativeError.rejected(error.toString()).toPlaybackError())
            null
        }
    }

    override fun pause() {
        videoElement.pause()
    }

    override fun seekTo(positionMs: Long) {
        videoElement.currentTime = positionMs / 1000.0
    }

    override fun setSpeed(speed: Float) {
        videoElement.playbackRate = speed.toDouble()
    }

    override fun setVolume(volume: Float) {
        videoElement.volume = volume.toDouble()
    }

    override fun currentPositionMs(): Long = (videoElement.currentTime * 1000.0).toLong()

    override fun release() {
        with(videoElement) {
            removeEventListener("playing", onPlaying)
            removeEventListener("pause", onPause)
            removeEventListener("waiting", onWaiting)
            removeEventListener("canplay", onCanPlay)
            removeEventListener("canplaythrough", onCanPlayThrough)
            removeEventListener("ended", onEnded)
            removeEventListener("error", onError)
            pause()
            // Dropping src and re-loading is how a media element releases its network
            // connection and decoder; there is no explicit dispose.
            removeAttribute("src")
            load()
        }
    }
}
