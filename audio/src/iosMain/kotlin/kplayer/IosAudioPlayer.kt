package kplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kplayer.audioplayer.AvAudioEngine
import kplayer.audioplayer.AudioPlayerState
import kplayer.core.player.EngineMediaPlayer
import kplayer.core.event.PlaybackAction
import platform.AVFoundation.AVPlayer

/**
 * Audio backend for iOS: [EngineMediaPlayer] driving an `AVPlayer`.
 *
 * There is deliberately no logic here. Everything the player does lives in
 * [EngineMediaPlayer] (and is unit-tested against a fake engine); everything
 * AVFoundation-specific lives in `AvAudioEngine`. This class exists to name the
 * combination and to expose the native handle.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioPlayer private constructor(
    private val avEngine: AvAudioEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<AudioPlayerState>(avEngine, AudioPlayerState(), scope) {

    /**
     * @param scope scope every [PlaybackAction] is dispatched on. Must be
     *   main-thread bound — `AVPlayer` mutation off the main thread is undefined.
     */
    constructor(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ) : this(AvAudioEngine(), scope)

    /**
     * The engine instance, exposed for integrations that need the real `AVPlayer` —
     * `MPNowPlayingInfoCenter` and remote-command handling for the lock screen,
     * say.
     *
     * Do not issue transport commands through this — playback must go through
     * [kplayer.core.MediaPlayer] so the state machine and the interruption engine stay in sync.
     * Do not release it either; this player owns its lifetime.
     */
    val avPlayer: AVPlayer get() = avEngine.avPlayer
}
