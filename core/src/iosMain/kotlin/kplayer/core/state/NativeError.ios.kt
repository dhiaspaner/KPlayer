package kplayer.core.state

import platform.Foundation.NSError

/**
 * AVFoundation's failure vocabulary: an `NSError`, reduced to the two primitives
 * that classify it.
 *
 * The extraction happens here rather than in the engines because both of them do the
 * identical thing with a failed `AVPlayerItem`, and `:video` and `:audio` cannot
 * share code with each other. The classification then happens in `appleSharedMain`,
 * which the desktop AVFoundation engine reaches too.
 */
actual class NativeError private constructor(
    internal val cause: Throwable?,
    internal val domain: String?,
    internal val code: Long?,
    internal val description: String?,
) {
    /**
     * What a call into `AVPlayer` threw.
     *
     * Kotlin/Native surfaces an Objective-C failure as an ordinary [Throwable], with
     * the `NSError` — when there is one at all — reachable only through the message,
     * which is why [toPlaybackError] falls back to reading the text.
     */
    actual constructor(cause: Throwable?) : this(cause, null, null, cause?.message)

    companion object {
        /**
         * A failed `AVPlayerItem`'s error.
         *
         * A null argument is normal, not a caller mistake: `AVPlayerItem.status` can
         * reach `failed` with `error` still unset, and that is a real failure that
         * must still be reported.
         */
        fun avError(error: NSError?): NativeError = NativeError(
            cause = null,
            domain = error?.domain,
            code = error?.code,
            description = error?.localizedDescription?.takeIf { it.isNotBlank() },
        )
    }
}

actual fun NativeError.toPlaybackError(): PlaybackError {
    // The classified path: a real NSError, straight off the item.
    if (domain != null && code != null) {
        return appleErrorPlaybackError(
            domain = domain,
            code = code,
            description = description ?: "AVError domain=$domain code=$code",
            cause = cause,
        )
    }

    // A throw, where the domain and code are only ever text. There is no typed
    // exception to match on, so anything without a recognisable domain stays Unknown.
    val text = description.orEmpty()
    val sniffedDomain = when {
        NS_URL_ERROR_DOMAIN in text -> NS_URL_ERROR_DOMAIN
        AV_FOUNDATION_ERROR_DOMAIN in text -> AV_FOUNDATION_ERROR_DOMAIN
        else -> null
    }
    val sniffedCode = sniffedDomain?.let {
        CODE_IN_MESSAGE.find(text)?.groupValues?.get(1)?.toLongOrNull()
    }

    return when {
        sniffedDomain != null && sniffedCode != null ->
            appleErrorPlaybackError(sniffedDomain, sniffedCode, description, cause)

        cause != null -> PlaybackError.Unknown(description, cause)

        // An item that failed without saying why. Still a failure, still reported.
        else -> PlaybackError.Unknown("Playback failed (no AV error provided)")
    }
}

private val CODE_IN_MESSAGE = Regex("""[Cc]ode=(-?\d+)""")
