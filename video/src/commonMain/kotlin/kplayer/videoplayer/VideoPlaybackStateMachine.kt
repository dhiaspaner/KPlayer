package kplayer.videoplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kplayer.core.event.PlaybackEvent
import kplayer.core.player.PlaybackStateMachine

/**
 * The single definition of how video configures the shared state machine.
 *
 * Both hooks exist for subtitles, which is the only way video's state genuinely
 * differs from audio's. Keeping them here rather than inline in
 * [AbstractVideoPlayer] means the machine a test builds is the same one the real
 * player runs — otherwise `VideoPlayerStateMachine()` in a test would quietly
 * exercise the audio configuration.
 */

/**
 * Cues change several times a minute and carry no status meaning, so they are
 * applied straight to the state instead of moving through the graph.
 */
internal val VideoCueReducer: (VideoPlayerState, PlaybackEvent) -> VideoPlayerState? =
    { state, event ->
        (event as? PlaybackEvent.SubtitleCueChanged)?.let { state.copy(activeSubtitle = it.text) }
    }

/**
 * The outgoing media's last cue would otherwise stay on screen until the new one
 * produces its first.
 */
internal val VideoOnLoad: (VideoPlayerState) -> VideoPlayerState =
    { it.copy(activeSubtitle = null) }

/** `PlaybackStateMachine` wired for video. */
internal fun VideoPlayerStateMachine(
    initialState: VideoPlayerState = VideoPlayerState(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) = PlaybackStateMachine(
    initialState = initialState,
    reduceCustom = VideoCueReducer,
    onLoad = VideoOnLoad,
)
