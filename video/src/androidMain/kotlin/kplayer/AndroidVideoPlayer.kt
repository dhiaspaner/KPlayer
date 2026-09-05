package kplayer

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kplayer.core.event.PlaybackAction
import kplayer.core.player.EngineMediaPlayer
import kplayer.videoplayer.ExoVideoEngine
import kplayer.videoplayer.VideoCueReducer
import kplayer.videoplayer.VideoOnLoad
import kplayer.videoplayer.VideoPlayerState

/**
 * Video backend for Android: `EngineMediaPlayer` driving an ExoPlayer.
 *
 * There is deliberately no logic here. Everything the player does lives in
 * `EngineMediaPlayer` in `:core` (and is unit-tested against a fake engine);
 * everything ExoPlayer-specific lives in `ExoVideoEngine`. This class exists to name
 * the combination, apply video's state-machine hooks, and expose the native handle
 * the render surface needs.
 */
class AndroidVideoPlayer private constructor(
    private val exoEngine: ExoVideoEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<VideoPlayerState>(
    engine = exoEngine,
    initialState = VideoPlayerState(),
    scope = scope,
    reduceCustom = VideoCueReducer,
    onLoad = VideoOnLoad,
) {

    /**
     * @param scope scope every [PlaybackAction] is dispatched on. Must be
     *   main-thread bound — ExoPlayer rejects calls from anywhere else.
     */
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ) : this(ExoVideoEngine(context), scope)

    /**
     * The engine instance, exposed **only** so a Compose `PlayerView` can attach to
     * it for rendering (see `:ui`'s `NativeVideoSurface`).
     *
     * Do not issue transport commands through this — playback must go through
     * [kplayer.core.MediaPlayer] so the state machine and the interruption engine stay in sync.
     * Do not release it either; this player owns its lifetime.
     */
    val exoPlayer: ExoPlayer get() = exoEngine.exoPlayer
}
