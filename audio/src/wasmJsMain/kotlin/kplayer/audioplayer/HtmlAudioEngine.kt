package kplayer.audioplayer

import kotlinx.browser.document
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.toPlaybackError
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.events.Event

/**
 * [MediaEngine] backed by an HTML5 `<audio>` element — the same approach Compose
 * Media Player takes for its web target.
 *
 * The element is created detached and never added to the document: an `<audio>`
 * element plays perfectly well out of the tree, and keeping it out means the page
 * gets no stray default controls. Web needs no equivalent of the Android or iOS
 * surface plumbing, so nothing above the seam changes.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the
 * translation of media-element events into the vocabulary [MediaEngine.events]
 * carries.
 */
internal class HtmlAudioEngine : AbstractMediaEngine() {

    val audioElement: HTMLAudioElement =
        (document.createElement("audio") as HTMLAudioElement).apply {
            preload = "auto"
        }

    /**
     * `<audio>` reports duration in seconds as a `Double`, and `NaN` until metadata
     * has loaded — `0` is the shared vocabulary's "unknown", same as a live stream.
     */
    private fun durationMs(): Long {
        val seconds = audioElement.duration
        return if (seconds.isNaN() || seconds.isInfinite()) 0L else (seconds * 1000.0).toLong()
    }

    // ── Element events → engine events ────────────────────────────────────────

    private val onPlaying: (Event) -> Unit = { reportPlaying(true) }

    private val onPause: (Event) -> Unit = {
        // `pause` also fires immediately before `ended`. Reporting it would read as
        // a pause and make the player step through Paused on its way to Completed,
        // so it is swallowed here exactly as ExoPlayer's STATE_ENDED case is.
        if (!audioElement.ended) reportPlaying(false)
    }

    private val onWaiting: (Event) -> Unit = { reportBuffering(true) }

    private val onCanPlay: (Event) -> Unit = {
        reportBuffering(false)
        reportReady(durationMs())
    }

    private val onPlayingBuffered: (Event) -> Unit = { reportBuffering(false) }

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
            NativeError.mediaElement(code = audioElement.error?.code?.toInt()).toPlaybackError()
        )
    }

    init {
        with(audioElement) {
            addEventListener("playing", onPlaying)
            addEventListener("pause", onPause)
            addEventListener("waiting", onWaiting)
            addEventListener("canplay", onCanPlay)
            addEventListener("canplaythrough", onPlayingBuffered)
            addEventListener("ended", onEnded)
            addEventListener("error", onError)
        }
    }

    /**
     * Every [MediaSource] variant is a URL string to a browser; a `FilePath` can
     * only be a served path or a `blob:` / `data:` URL, since a page cannot read
     * the local filesystem.
     */
    override fun setSource(source: MediaSource): Boolean {
        val url = when (source) {
            is MediaSource.Url -> source.value
            is MediaSource.FilePath -> source.path
            is MediaSource.AndroidUriString -> source.value
            is MediaSource.Custom -> source.value
        }
        if (url.isBlank()) return false
        audioElement.src = url
        return true
    }

    override fun prepare() {
        audioElement.load()
    }

    /**
     * The returned `Promise` rejects when autoplay policy blocks playback — before
     * any user gesture on the page. Surfacing that as an engine error is the only
     * way the caller finds out; the element itself stays silently paused.
     */
    @OptIn(ExperimentalWasmJsInterop::class)
    override fun play() {
        audioElement.play()
            .catch { error ->
                reportError(NativeError.rejected(error.toString()).toPlaybackError())
                null
            }

    }

    override fun pause() {
        audioElement.pause()
    }

    override fun seekTo(positionMs: Long) {
        audioElement.currentTime = positionMs / 1000.0
    }

    override fun setSpeed(speed: Float) {
        audioElement.playbackRate = speed.toDouble()
    }

    override fun setVolume(volume: Float) {
        audioElement.volume = volume.toDouble()
    }

    override fun currentPositionMs(): Long = (audioElement.currentTime * 1000.0).toLong()

    override fun release() {
        with(audioElement) {
            removeEventListener("playing", onPlaying)
            removeEventListener("pause", onPause)
            removeEventListener("waiting", onWaiting)
            removeEventListener("canplay", onCanPlay)
            removeEventListener("canplaythrough", onPlayingBuffered)
            removeEventListener("ended", onEnded)
            removeEventListener("error", onError)
            pause()
            // Dropping src and re-loading is how you make a media element release
            // its network connection and decoder; there is no explicit dispose.
            removeAttribute("src")
            load()
        }
    }
}
