package kplayer.core.event

import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import com.dhiachemingui.statemachine.Event as MachineEvent

interface PlaybackEvent : MachineEvent {
    data class LoadRequested(val source: MediaSource) : PlaybackEvent
    data class Ready(val durationMs: Long) : PlaybackEvent
    data class SeekToStarted(val positionMs: Long) : PlaybackEvent
    data class PositionSynced(val positionMs: Long) : PlaybackEvent
    /**
     * The engine failed. [error] describes the failure; [kplayer.core.state.PlaybackStatus.Error] is
     * what the machine does about it.
     *
     * An engine reports one through [kplayer.core.player.MediaEngine.events] and
     * [kplayer.core.player.EngineMediaPlayer] constructs one for a failure of its own;
     * nobody else should. Both routes meet in the player's `reportFailure`, so
     * retry and reporting happen in one place.
     */
    data class Failure(val error: PlaybackError) : PlaybackEvent {

        /** For an engine or a test with nothing better than a string to offer. */
        constructor(message: String) : this(PlaybackError.Unknown(message))

        val message: String? get() = error.message
    }

    data object PlaybackStarted : PlaybackEvent
    data object PlaybackPaused : PlaybackEvent
    data object Stopped : PlaybackEvent
    data object BufferingStarted : PlaybackEvent
    data object BufferingEnded : PlaybackEvent
    data object PlaybackCompleted : PlaybackEvent
    data object StopRequested : PlaybackEvent
    data object ReleaseRequested : PlaybackEvent
    data class SpeedChanged(val speed: Float) : PlaybackEvent
    data class VolumeChanged(val volume: Float) : PlaybackEvent

    /**
     * The set of on-screen subtitle cues changed; [text] is the flattened
     * result, or `null` when nothing is showing.
     *
     * Like [PositionSynced] this carries no status meaning and fires often, so
     * the state machine applies it directly rather than routing it through the
     * graph.
     */
    data class SubtitleCueChanged(val text: String?) : PlaybackEvent
}
