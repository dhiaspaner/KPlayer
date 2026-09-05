package kplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kplayer.core.event.PlaybackAction
import kplayer.core.player.EngineMediaPlayer
import kplayer.videoplayer.AvVideoEngine
import kplayer.videoplayer.VideoCueReducer
import kplayer.videoplayer.VideoOnLoad
import kplayer.videoplayer.VideoPlayerState
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVPlayer

/**
 * Video backend for iOS: `EngineMediaPlayer` driving an `AVPlayer`.
 *
 * There is deliberately no logic here. Everything the player does lives in
 * `EngineMediaPlayer` in `:core` (and is unit-tested against a fake engine);
 * everything AVFoundation-specific lives in `AvVideoEngine`. This class exists to
 * name the combination, apply video's state-machine hooks, and expose the handles
 * the render surface needs.
 *
 * `AVAudioSession` is a process-wide singleton configured by `:core`'s
 * `IosAudioSession`, so nothing about the session is wired up per instance here.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVideoPlayer private constructor(
    private val avEngine: AvVideoEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<VideoPlayerState>(
    engine = avEngine,
    initialState = VideoPlayerState(),
    scope = scope,
    reduceCustom = VideoCueReducer,
    onLoad = VideoOnLoad,
) {

    /**
     * @param scope scope every [PlaybackAction] is dispatched on. Must be
     *   main-thread bound — `AVPlayer` mutation off the main thread is undefined.
     */
    constructor(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ) : this(AvVideoEngine(), scope)

    /**
     * The engine instance, exposed **only** so a Compose `AVPlayerLayer` can attach
     * to it for rendering (see `:ui`'s `NativeVideoSurface`).
     *
     * Do not issue transport commands through this — playback must go through
     * [kplayer.core.MediaPlayer] so the state machine and the interruption engine stay in sync.
     * Do not release it either; this player owns its lifetime.
     */
    val avPlayer: AVPlayer get() = avEngine.avPlayer


    /**
     * Routes subtitle cues into `VideoPlayerState.activeSubtitle` instead of letting
     * AVFoundation draw them. Set by `:ui`'s `NativeVideoSurface`; safe to flip
     * mid-playback. See `AvVideoEngine.routesSubtitlesToState` for why this is an
     * either/or on iOS.
     */
    var routesSubtitlesToState: Boolean
        get() = avEngine.routesSubtitlesToState
        set(value) {
            avEngine.routesSubtitlesToState = value
        }

    /**
     * Lets a test serve media from memory under the `mockfile://` scheme, so iOS
     * tests need no network. Set before loading.
     */
    var testResourceLoaderDelegate: AVAssetResourceLoaderDelegateProtocol?
        get() = avEngine.testResourceLoaderDelegate
        set(value) {
            avEngine.testResourceLoaderDelegate = value
        }
}
