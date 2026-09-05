package kplayer.core.state

import kplayer.core.state.PlaybackError

/**
 * The browser's failure vocabulary, which comes in two flavours.
 *
 * An `error` event on the element gives a [mediaElement] `MediaError.code` — four
 * values, and genuinely all the detail there is, since `MediaError.message` is empty
 * in most browsers. A [rejected] `play()` promise instead gives a `DOMException`,
 * whose `name` is the signal; the most important one, `NotAllowedError`, is not a
 * fault at all but the autoplay policy waiting for a gesture.
 *
 * Both are strings and ints by the time they get here, so neither needs a DOM type
 * in the signature — which matters because Kotlin/Wasm cannot reach a typed
 * `DOMException` anyway.
 */
actual class NativeError private constructor(
    internal val cause: Throwable?,
    internal val message: String?,
    internal val mediaErrorCode: Int?,
) {
    /**
     * What a call into the element threw. Kotlin/Wasm wraps a JavaScript throw in a
     * `JsException` whose message carries the exception's `name`, which is exactly
     * what [toPlaybackError] classifies on.
     */
    actual constructor(cause: Throwable?) : this(cause, cause?.message, null)

    companion object {
        /**
         * The element's own `MediaError`, from an `error` event.
         *
         * [message] is optional because `org.w3c.dom.MediaError` binds only `code`
         * — the spec's `message` is not reachable from Kotlin, and is empty in most
         * browsers anyway.
         */
        fun mediaElement(code: Int?, message: String? = null): NativeError =
            NativeError(null, message, code)

        /**
         * A rejected `play()` promise.
         *
         * `HTMLMediaElement.play()` rejects with a `JsAny` that is not a [Throwable],
         * so `.catch { }` has nothing but its text — which still carries the name.
         */
        fun rejected(description: String?): NativeError = NativeError(null, description, null)
    }
}

actual fun NativeError.toPlaybackError(): PlaybackError {
    val detail = message?.takeIf { it.isNotBlank() }

    // Four codes, each mapping onto exactly one variant.
    if (mediaErrorCode != null) return when (mediaErrorCode) {
        MEDIA_ERR_ABORTED -> PlaybackError.Aborted(detail ?: "Loading aborted", cause)
        MEDIA_ERR_NETWORK -> PlaybackError.Network(cause, detail ?: "Network error while loading media")
        MEDIA_ERR_DECODE -> PlaybackError.Decoder(detail ?: "Media decoding failed", cause)
        MEDIA_ERR_SRC_NOT_SUPPORTED ->
            PlaybackError.Source(detail ?: "Media source not supported by this browser", cause)

        else -> PlaybackError.Unknown(detail ?: "Playback failed", cause)
    }

    // Matching on the text is not elegant, but the typed `DOMException` is not
    // reachable from Kotlin here, and the alternative is calling every autoplay
    // refusal Unknown and retrying it forever.
    return when (DOM_EXCEPTION_NAMES.firstOrNull { it in message.orEmpty() }) {
        // Not a fault: nothing here heals without a tap, so a retry policy must give
        // up and let the UI put a play button on screen instead.
        "NotAllowedError" -> PlaybackError.PlaybackBlocked(
            detail ?: "Playback needs a user gesture first",
            cause,
        )

        "AbortError" -> PlaybackError.Aborted(detail ?: "Playback aborted", cause)
        "NotSupportedError" -> PlaybackError.Source(detail ?: "Media source not supported", cause)
        "NetworkError" -> PlaybackError.Network(cause, detail ?: "Network error")
        else -> PlaybackError.Unknown(detail ?: "Playback failed", cause)
    }
}

private const val MEDIA_ERR_ABORTED = 1
private const val MEDIA_ERR_NETWORK = 2
private const val MEDIA_ERR_DECODE = 3
private const val MEDIA_ERR_SRC_NOT_SUPPORTED = 4

private val DOM_EXCEPTION_NAMES = listOf(
    "NotAllowedError",
    "AbortError",
    "NotSupportedError",
    "NetworkError",
)
