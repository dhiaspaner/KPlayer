package kplayer.core.player

import kplayer.core.event.PlaybackAction
import kplayer.core.state.PlaybackError

/**
 * Tracks how many consecutive times *the same* [PlaybackAction] has failed,
 * so [PlaybackRetryPolicy] — which is stateless — can be told an attempt
 * number without having to remember anything itself.
 *
 * A new action (by `==`) starts a fresh chain: retrying [PlaybackAction.SeekTo]
 * twice then failing a new [PlaybackAction.Load] is attempt 1 of the load, not
 * attempt 3 of some unrelated chain.
 */
class RetryAttemptTracker(private val policy: PlaybackRetryPolicy) {
    private var retryAction: PlaybackAction? = null
    private var retryAttempts: Int = 0

    fun decide(action: PlaybackAction, error: PlaybackError): RetryDecision {
        if (action != retryAction) {
            retryAction = action
            retryAttempts = 0
        }
        retryAttempts++
        return policy.decide(action, error, retryAttempts)
    }

    /** Call once a chain resolves without exhausting retries — success, or a fresh action starting. */
    fun reset() {
        retryAction = null
        retryAttempts = 0
    }
}