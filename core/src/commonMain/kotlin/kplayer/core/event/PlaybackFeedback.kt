package kplayer.core.event

sealed interface PlaybackFeedback {
    data class Accepted(val message: String? = null) : PlaybackFeedback

    data class Rejected(val reason: String) : PlaybackFeedback

    data class Failed(val message: String) : PlaybackFeedback
}
