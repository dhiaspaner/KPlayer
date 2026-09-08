package kplayer.core.player

import kplayer.core.event.PlaybackAction
import kplayer.core.state.PlaybackError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Retry logic that genuinely depends on *both* dimensions:
 *
 * - [PlaybackAction.Release], [PlaybackAction.Stop], [PlaybackAction.Pause], [PlaybackAction.SetPlaybackSpeed], [PlaybackAction.SetVolume]:
 *   never retried, for any error. These are local state transitions with no
 *   external resource to wait on — repeating them verbatim can't change the
 *   outcome, and retrying [PlaybackAction.Release] would fight teardown.
 *
 * - [PlaybackAction.Load] / [PlaybackAction.Play]: retried per error kind —
 *     - [PlaybackError.Network] / [PlaybackError.Source]: retried only when the
 *       failure looks server-side (5xx or no status at all, e.g. a timeout).
 *       A 4xx means the URL or auth is wrong and won't fix itself. Note that
 *       "no status" is a heuristic: it also covers DNS failures, TLS errors,
 *       and cancellations, which aren't actually transient — we accept that
 *       imprecision in favor of not giving up on a plain timeout.
 *     - [PlaybackError.AudioSessionDenied]: retried on a fixed, shorter cadence —
 *       focus is usually released quickly, so exponential backoff would make
 *       playback resume later than necessary.
 *     - [PlaybackError.Unknown]: retried with standard backoff — no information
 *       says it's permanent, so give it the benefit of the doubt.
 *     - [PlaybackError.Decoder]: never retried — a codec the device can't
 *       decode fails identically on every attempt.
 *
 * - [PlaybackAction.SeekTo]: only [PlaybackError.Network] / [PlaybackError.Source]
 *   are retried (a seek can trigger a new range request that can itself flake).
 *   Any other error on a seek — decoder, audio session — isn't fixed by
 *   repeating the seek, so it's surfaced immediately instead.
 *
 * [maxAttempts] is a hard ceiling applied before any of the above, regardless
 * of action or error kind.
 *
 * Contract: [attempt] is 1-indexed — the first failed attempt is `1`, not `0`.
 * Callers must not pass values below 1.
 */
class ActionAwareRetryPolicy(
    private val maxAttempts: Int = 3,
    private val initialDelay: Duration = 500.milliseconds,
    private val focusRetryDelay: Duration = 300.milliseconds,
    private val maxDelay: Duration = 30.minutes,
) : PlaybackRetryPolicy {

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive, was $maxAttempts" }
        require(initialDelay > Duration.ZERO) { "initialDelay must be positive, was $initialDelay" }
        require(focusRetryDelay > Duration.ZERO) { "focusRetryDelay must be positive, was $focusRetryDelay" }
        require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be >= initialDelay ($initialDelay)" }
    }

    override fun decide(action: PlaybackAction, error: PlaybackError, attempt: Int): RetryDecision {
        require(attempt >= 1) { "attempt must be 1-indexed (>= 1), was $attempt" }
        if (attempt >= maxAttempts) return RetryDecision.GiveUp

        return when (action) {
            PlaybackAction.Release,
            PlaybackAction.Stop,
            PlaybackAction.Pause,
            is PlaybackAction.SetPlaybackSpeed,
            is PlaybackAction.SetVolume,
                -> RetryDecision.GiveUp

            is PlaybackAction.Load,
            PlaybackAction.Play,
                -> error.asServerSideRetry(attempt) ?: when (error) {
                PlaybackError.AudioSessionDenied -> RetryDecision.RetryAfter(focusRetryDelay)
                is PlaybackError.Unknown -> backoff(attempt)
                is PlaybackError.Decoder -> RetryDecision.GiveUp

                // Neither is a fault, so neither is retried: a blocked playback
                // waits for a gesture, and an abort means the source was
                // abandoned on purpose.
                is PlaybackError.PlaybackBlocked,
                is PlaybackError.Aborted,
                    -> RetryDecision.GiveUp

                // Network/Source are handled by asServerSideRetry above; this
                // branch is unreachable but keeps the `when` exhaustive without
                // an `else`.
                is PlaybackError.Network, is PlaybackError.Source -> RetryDecision.GiveUp
            }

            is PlaybackAction.SeekTo -> error.asServerSideRetry(attempt) ?: RetryDecision.GiveUp
        }
    }

    /**
     * Shared server-side-retry heuristic for [PlaybackError.Network] and
     * [PlaybackError.Source]. Returns `null` for any other error kind so
     * callers can fall through to action-specific handling.
     */
    private fun PlaybackError.asServerSideRetry(attempt: Int): RetryDecision? = when (this) {
        is PlaybackError.Network -> retryIfServerSide(httpStatusCode, attempt)
        is PlaybackError.Source -> retryIfServerSide(httpStatusCode, attempt)
        else -> null
    }

    private fun retryIfServerSide(httpStatusCode: Int?, attempt: Int): RetryDecision =
        if (httpStatusCode == null || httpStatusCode >= 500) backoff(attempt) else RetryDecision.GiveUp

    private fun backoff(attempt: Int): RetryDecision {
        val shift = (attempt - 1).coerceAtMost(30) // guard against Int overflow in `1 shl n`
        val delay = initialDelay * (1 shl shift)
        return RetryDecision.RetryAfter(minOf(delay, maxDelay))
    }
}