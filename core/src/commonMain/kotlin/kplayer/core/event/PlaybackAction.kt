package kplayer.core.event

import kplayer.core.state.MediaSource

sealed interface PlaybackAction {
    data class Load(val source: MediaSource) : PlaybackAction
    data object Play : PlaybackAction
    data object Pause : PlaybackAction
    data object Stop : PlaybackAction
    data object Release : PlaybackAction
    data class SeekTo(val positionMs: Long) : PlaybackAction
    data class SetPlaybackSpeed(val speed: Float) : PlaybackAction
    data class SetVolume(val volume: Float) : PlaybackAction
}
