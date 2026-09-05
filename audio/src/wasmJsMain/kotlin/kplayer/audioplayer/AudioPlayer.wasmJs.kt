package kplayer.audioplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.player.EngineMediaPlayer
import kplayer.core.state.MediaSource
import org.w3c.dom.HTMLAudioElement

actual fun AudioPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode,
): MediaPlayer<MediaSource, AudioPlayerState> = MediaPlayer {

    player { WebAudioPlayer() }

    interruptionConfig(interruptionConfig)
    // Carried for parity only: the browser picks its own output routing and there
    // is no session category to map the mode onto.
    mode(audioSessionMode)
}

/**
 * Audio backend for the web: `EngineMediaPlayer` driving an HTML5 `<audio>` element.
 *
 * There is deliberately no logic here — everything the player does lives in
 * `EngineMediaPlayer` and is unit-tested against a fake engine, and everything
 * browser-specific lives in `HtmlAudioEngine`.
 *
 * Wasm is single-threaded, so the action scope is `Dispatchers.Main` for the same
 * reason as on Android and iOS: it is the only dispatcher there is.
 */
class WebAudioPlayer private constructor(
    private val webEngine: HtmlAudioEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<AudioPlayerState>(webEngine, AudioPlayerState(), scope) {

    constructor(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ) : this(HtmlAudioEngine(), scope)

    /**
     * The underlying element, exposed for integrations that need it — wiring up the
     * Media Session API for OS-level media keys and lock-screen metadata, say.
     *
     * Do not issue transport commands through this: playback must go through
     * [MediaPlayer] so the state machine and the interruption engine stay in sync.
     */
    val audioElement: HTMLAudioElement get() = webEngine.audioElement
}
