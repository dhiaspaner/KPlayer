package kplayer.core.state

/**
 * Structured cause of a [kplayer.core.state.PlaybackStatus.Error], replacing a bare error string.
 *
 * Every variant exposes [message] and [cause] uniformly so call sites that only
 * want "what do I show or log" never need an exhaustive `when` just to read a
 * common field — the `when` is reserved for logic that genuinely differs by
 * failure kind, like deciding what to retry.
 *
 * This type *describes* a failure and nothing more. Whether a failure is worth
 * retrying is a policy question — it depends on the action that hit it, how many
 * attempts have already been spent and what the app wants — so it lives in
 * [kplayer.core.player.PlaybackRetryPolicy], not here. Putting an `isRetryable` flag on
 * the variants would freeze one app's answer into the description of the failure.
 */
sealed interface PlaybackError {
    val message: String?
    val cause: Throwable?

    /** No bytes reaching the decoder — DNS, timeout, dropped connection, non-2xx response. */
    data class Network(
        override val cause: Throwable? = null,
        override val message: String? = cause?.message,
        val httpStatusCode: Int? = null,
    ) : PlaybackError

    /** The decoder rejected the media — corrupt data or an unsupported codec/container. */
    data class Decoder(
        override val message: String? = null,
        override val cause: Throwable? = null,
        val mimeType: String? = null,
    ) : PlaybackError

    /** The source is invalid at the container level — 404, expired URL, malformed manifest. */
    data class Source(
        override val message: String? = null,
        override val cause: Throwable? = null,
        val httpStatusCode: Int? = null,
    ) : PlaybackError

    data object AudioSessionDenied : PlaybackError {
        override val message: String = "Audio session ownership was denied"
        override val cause: Throwable? = null
    }

    /**
     * The platform refuses to start playback until the user asks for it.
     *
     * A browser rejects `play()` with `NotAllowedError` until the page has seen a
     * gesture; iOS refuses for the same reason from the background. Distinct from
     * [AudioSessionDenied], which means another app holds focus and this one may
     * get it back on its own — nothing here heals without a tap, so a retry policy
     * must give up and let the UI prompt instead.
     */
    data class PlaybackBlocked(
        override val message: String? = null,
        override val cause: Throwable? = null,
    ) : PlaybackError

    /**
     * Loading was cancelled before it finished — not a fault.
     *
     * `MEDIA_ERR_ABORTED`, `NSURLErrorCancelled`, a JVM interrupt: the caller, the
     * user or a source swap stopped the fetch. Retrying re-loads a source nobody
     * wants any more, so this is never worth another attempt.
     */
    data class Aborted(
        override val message: String? = null,
        override val cause: Throwable? = null,
    ) : PlaybackError

    /** Anything the platform layer couldn't classify. Prefer adding a real variant over routing more failures here. */
    data class Unknown(
        override val message: String? = null,
        override val cause: Throwable? = null,
    ) : PlaybackError
}

/** Best-effort user-facing string when no dedicated UI copy exists for [this]. */
fun PlaybackError.toDisplayMessage(): String =
    message ?: cause?.message ?: "Playback failed"
