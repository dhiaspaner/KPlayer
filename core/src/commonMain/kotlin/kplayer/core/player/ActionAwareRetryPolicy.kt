package kplayer.core.player

import kplayer.core.event.PlaybackAction
import kplayer.core.state.PlaybackError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retry logic that genuinely depends on *both* dimensions:
 *
 * - [PlaybackAction.Release], [Stop], [Pause], [SetPlaybackSpeed], [SetVolume]:
 *   never retried, for any error. These are local state transitions with no
 *   external resource to wait on — repeating them verbatim can't change the
 *   outcome, and retrying [PlaybackAction.Release] would fight teardown.
 *
 * - [PlaybackAction.Load] / [PlaybackAction.Play]: retried per error kind —
 *     - [PlaybackError.Network] / [PlaybackError.Source]: retried only when the
 *       failure looks server-side (5xx or no status at all, e.g. a timeout).
 *       A 4xx means the URL or auth is wrong and won't fix itself.
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
 */
class ActionAwareRetryPolicy(
    private val maxAttempts: Int = 3,
    private val initialDelay: Duration = 500.milliseconds,
    private val focusRetryDelay: Duration = 300.milliseconds,
) : PlaybackRetryPolicy {

    override fun decide(action: PlaybackAction, error: PlaybackError, attempt: Int): RetryDecision {
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
            -> when (error) {
                is PlaybackError.Network -> retryIfServerSide(error.httpStatusCode, attempt)
                is PlaybackError.Source -> retryIfServerSide(error.httpStatusCode, attempt)
                PlaybackError.AudioSessionDenied -> RetryDecision.RetryAfter(focusRetryDelay)
                is PlaybackError.Unknown -> backoff(attempt)
                is PlaybackError.Decoder -> RetryDecision.GiveUp

                // Neither is a fault, so neither is retried: a blocked playback
                // waits for a gesture, and an abort means the source was
                // abandoned on purpose.
                is PlaybackError.PlaybackBlocked,
                is PlaybackError.Aborted,
                -> RetryDecision.GiveUp
            }

            is PlaybackAction.SeekTo -> when (error) {
                is PlaybackError.Network -> retryIfServerSide(error.httpStatusCode, attempt)
                is PlaybackError.Source -> retryIfServerSide(error.httpStatusCode, attempt)
                is PlaybackError.Decoder,
                PlaybackError.AudioSessionDenied,
                is PlaybackError.Unknown,
                is PlaybackError.PlaybackBlocked,
                is PlaybackError.Aborted,
                -> RetryDecision.GiveUp
            }
        }
    }

    private fun retryIfServerSide(httpStatusCode: Int?, attempt: Int): RetryDecision =
        if (httpStatusCode == null || httpStatusCode >= 500) backoff(attempt) else RetryDecision.GiveUp

    private fun backoff(attempt: Int): RetryDecision =
        RetryDecision.RetryAfter(initialDelay * (1 shl (attempt - 1)))
}