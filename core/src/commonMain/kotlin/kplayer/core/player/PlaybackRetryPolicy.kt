package kplayer.core.player

import kplayer.core.event.PlaybackAction
import kplayer.core.state.PlaybackError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

sealed interface RetryDecision {
    data class RetryAfter(val delay: Duration) : RetryDecision
    data object GiveUp : RetryDecision
}

fun interface PlaybackRetryPolicy {

    fun decide(action: PlaybackAction, error: PlaybackError, attempt: Int): RetryDecision

    companion object {
        val None: PlaybackRetryPolicy = PlaybackRetryPolicy { _, _, _ -> RetryDecision.GiveUp }

        fun transient(
            maxAttempts: Int = 3,
            initialDelay: Duration = 500.milliseconds
        ) =
            PlaybackRetryPolicy { action, error, attempt ->
                when {
                    attempt >= maxAttempts -> RetryDecision.GiveUp
                    action == PlaybackAction.Release -> RetryDecision.GiveUp
                    !error.isTransient() -> RetryDecision.GiveUp
                    else -> RetryDecision.RetryAfter(delay = initialDelay * (1 shl (attempt - 1)))
                }
            }

        private fun PlaybackError.isTransient(): Boolean = when (this) {
            // 4xx (bad URL, auth) will not fix itself; timeouts and 5xx might.
            is PlaybackError.Network -> httpStatusCode == null || httpStatusCode >= 500
            is PlaybackError.Unknown -> true
            is PlaybackError.Decoder, is PlaybackError.Source -> false
            PlaybackError.AudioSessionDenied -> true

            // Neither is a fault. A blocked playback needs a gesture, not another
            // attempt, and an abort means nobody wants this source any more —
            // retrying either just fails the same way, louder.
            is PlaybackError.PlaybackBlocked, is PlaybackError.Aborted -> false
        }

    }
}


inline fun RetryDecision.peekOnGiveUp(action: (RetryDecision.GiveUp) -> Unit): RetryDecision {
    if (this is RetryDecision.GiveUp) action(this)
    return this
}


suspend inline fun RetryDecision.onRetryAfter(action: suspend (RetryDecision.RetryAfter) -> Unit): RetryDecision {
    if (this is RetryDecision.RetryAfter) action(this)
    return this
}

