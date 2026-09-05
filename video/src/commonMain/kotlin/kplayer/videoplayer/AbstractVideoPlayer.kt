package kplayer.videoplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kplayer.core.player.AbstractMediaPlayer

/**
 * `AbstractMediaPlayer` configured for video.
 *
 * The transport, the state machine and the event plumbing are all shared with
 * `:audio` in `:core`. What is left here is the one way video genuinely differs —
 * subtitle cues — expressed through the two hooks the shared machine offers, which
 * live in `VideoPlaybackStateMachine.kt` so a test builds the same configuration.
 *
 * @param scope delivers `events`; see [AbstractMediaPlayer]. Previously named
 *   `stateMachineScope` and unused — transitions have not needed a scope since they
 *   became synchronous.
 */
abstract class AbstractVideoPlayer(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) : AbstractMediaPlayer<VideoPlayerState>(
    initialState = VideoPlayerState(),
    scope = scope,
    reduceCustom = VideoCueReducer,
    onLoad = VideoOnLoad,
)
