package kplayer.videoplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.player.EngineMediaPlayer
import kplayer.core.state.MediaSource
import org.w3c.dom.HTMLVideoElement

actual fun VideoPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode,
): MediaPlayer<MediaSource, VideoPlayerState> = MediaPlayer {
    player { WebVideoPlayer() }
    interruptionConfig(interruptionConfig)
    mode(audioSessionMode)
}

/**
 * Video backend for the web: `EngineMediaPlayer` driving an HTML5 `<video>` element.
 *
 * There is deliberately no logic here — everything the player does lives in
 * `EngineMediaPlayer`, and everything browser-specific in `HtmlVideoEngine`.
 */
class WebVideoPlayer private constructor(
    private val webEngine: HtmlVideoEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<VideoPlayerState>(
    engine = webEngine,
    initialState = VideoPlayerState(),
    scope = scope,
    reduceCustom = VideoCueReducer,
    onLoad = VideoOnLoad,
) {

    constructor(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ) : this(HtmlVideoEngine(), scope)

    /**
     * The element that renders the video. Unlike the audio engine's, this one *must*
     * be placed in the document for anything to be visible — that is the web's
     * equivalent of attaching a `PlayerView` or an `AVPlayerLayer`.
     *
     * Do not issue transport commands through it; playback must go through
     * [MediaPlayer] so the state machine and interruption engine stay in sync.
     */
    val videoElement: HTMLVideoElement get() = webEngine.videoElement
}
