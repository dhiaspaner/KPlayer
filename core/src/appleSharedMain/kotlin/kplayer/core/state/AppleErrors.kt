package kplayer.core.state

import kplayer.core.state.PlaybackError

/**
 * Apple's failure vocabulary, translated into [kplayer.core.state.PlaybackError].
 *
 * ### Why it is here and not next to an engine
 *
 * Two engines reach AVFoundation by completely different routes — Kotlin/Native
 * interop on iOS, JNA `objc_msgSend` on desktop — and both end up holding the same
 * two primitives: a domain string and a code. Written once per engine this table had
 * already begun to drift, and `:video` and `:audio` cannot see each other to share
 * it. It is not backend logic anyway: it is the definition of what our own
 * vocabulary *means* for Apple's, so it belongs in `:core`.
 *
 * `appleSharedMain` rather than `commonMain` because only iOS and the JVM have any
 * use for it. Keying on `String` and `Long` rather than on `NSError` is what lets
 * one table serve both: the *engine* extracts (reading `domain`, `code` and
 * `localizedDescription` off the object, by whichever interop it has), and `:core`
 * classifies, with no Apple type in sight and therefore testable from `jvmTest` on
 * any host.
 *
 * `NSURLErrorDomain` classifies cleanly — it is the transport, and its codes say
 * plainly whether the problem is the connection or the URL. `AVFoundationErrorDomain`
 * does not: most of its range mixes unplayable formats with transient
 * media-services resets, so only the codes that are unambiguous are named and the
 * rest stay [kplayer.core.state.PlaybackError.Unknown]. Guessing [kplayer.core.state.PlaybackError.Decoder] there would
 * tell a retry policy to give up on failures that heal on their own.
 */
internal fun appleErrorPlaybackError(
    domain: String?,
    code: Long,
    description: String? = null,
    cause: Throwable? = null,
): PlaybackError {
    val message = description ?: "$domain error $code"
    return when (domain) {
        NS_URL_ERROR_DOMAIN -> when (code) {
            NSURL_ERROR_CANCELLED -> PlaybackError.Aborted(message, cause)

            // The URL itself is wrong, or points at nothing we may read. No amount
            // of waiting fixes any of these.
            NSURL_ERROR_BAD_URL,
            NSURL_ERROR_UNSUPPORTED_URL,
            NSURL_ERROR_FILE_DOES_NOT_EXIST,
            NSURL_ERROR_FILE_IS_DIRECTORY,
            NSURL_ERROR_NO_PERMISSIONS_TO_READ_FILE,
            NSURL_ERROR_ZERO_BYTE_RESOURCE,
            NSURL_ERROR_RESOURCE_UNAVAILABLE,
            -> PlaybackError.Source(message, cause)

            NSURL_ERROR_CANNOT_DECODE_RAW_DATA,
            NSURL_ERROR_CANNOT_DECODE_CONTENT_DATA,
            -> PlaybackError.Decoder(message, cause)

            // Everything else in this domain is the connection: timeouts, DNS,
            // offline, dropped, TLS. All plausibly transient.
            else -> PlaybackError.Network(cause, message)
        }

        AV_FOUNDATION_ERROR_DOMAIN -> when (code) {
            // The bytes are unplayable on this device. None of these heal.
            AV_ERROR_DECODE_FAILED,
            AV_ERROR_FILE_FORMAT_NOT_RECOGNIZED,
            AV_ERROR_FILE_FAILED_TO_PARSE,
            AV_ERROR_DECODER_NOT_FOUND,
            AV_ERROR_UNDECODABLE_MEDIA_DATA,
            AV_ERROR_FORMAT_UNSUPPORTED,
            -> PlaybackError.Decoder(message, cause)

            // The item is the problem, not the pipeline: gone, expired, or ours to
            // fetch but not to play.
            AV_ERROR_CONTENT_IS_NOT_AUTHORIZED,
            AV_ERROR_CONTENT_IS_UNAVAILABLE,
            AV_ERROR_NO_LONGER_PLAYABLE,
            AV_ERROR_SERVER_INCORRECTLY_CONFIGURED,
            -> PlaybackError.Source(message, cause)

            AV_ERROR_FAILED_TO_LOAD_MEDIA_DATA -> PlaybackError.Network(cause, message)

            // Media services resetting is the textbook transient AV failure. It
            // stays Unknown deliberately — that is the one variant our retry
            // policies still retry, which is exactly the treatment it wants.
            AV_ERROR_MEDIA_SERVICES_WERE_RESET -> PlaybackError.Unknown(message, cause)

            else -> PlaybackError.Unknown(message, cause)
        }

        POSIX_ERROR_DOMAIN -> when (code) {
            POSIX_ENOENT, POSIX_EACCES -> PlaybackError.Source(message, cause)
            POSIX_ECONNRESET, POSIX_ETIMEDOUT, POSIX_ENETDOWN, POSIX_EHOSTUNREACH ->
                PlaybackError.Network(cause, message)

            else -> PlaybackError.Unknown(message, cause)
        }

        else -> PlaybackError.Unknown(message, cause)
    }
}

internal const val NS_URL_ERROR_DOMAIN: String = "NSURLErrorDomain"
internal const val AV_FOUNDATION_ERROR_DOMAIN: String = "AVFoundationErrorDomain"
private const val POSIX_ERROR_DOMAIN = "NSPOSIXErrorDomain"

// NSURLError codes; see the note on AVError below.
private const val NSURL_ERROR_CANCELLED = -999L
private const val NSURL_ERROR_BAD_URL = -1000L
private const val NSURL_ERROR_UNSUPPORTED_URL = -1002L
private const val NSURL_ERROR_ZERO_BYTE_RESOURCE = -1014L
private const val NSURL_ERROR_CANNOT_DECODE_RAW_DATA = -1015L
private const val NSURL_ERROR_CANNOT_DECODE_CONTENT_DATA = -1016L
private const val NSURL_ERROR_RESOURCE_UNAVAILABLE = -1008L
private const val NSURL_ERROR_FILE_DOES_NOT_EXIST = -1100L
private const val NSURL_ERROR_FILE_IS_DIRECTORY = -1101L
private const val NSURL_ERROR_NO_PERMISSIONS_TO_READ_FILE = -1102L

// AVError codes. Named here rather than imported because this source set compiles
// for the JVM too, where the desktop engine reaches AVFoundation through JNA and has
// no Objective-C constants to import. `AppleErrorCodesTest` in :core's iosTest pins
// every one of them against the real `platform.AVFoundation` value.
private const val AV_ERROR_MEDIA_SERVICES_WERE_RESET = -11819L
private const val AV_ERROR_DECODE_FAILED = -11821L
private const val AV_ERROR_FILE_FORMAT_NOT_RECOGNIZED = -11828L
private const val AV_ERROR_FILE_FAILED_TO_PARSE = -11829L
private const val AV_ERROR_DECODER_NOT_FOUND = -11833L
private const val AV_ERROR_CONTENT_IS_NOT_AUTHORIZED = -11835L
private const val AV_ERROR_FAILED_TO_LOAD_MEDIA_DATA = -11849L
private const val AV_ERROR_SERVER_INCORRECTLY_CONFIGURED = -11850L
private const val AV_ERROR_UNDECODABLE_MEDIA_DATA = -11855L
private const val AV_ERROR_CONTENT_IS_UNAVAILABLE = -11863L
private const val AV_ERROR_FORMAT_UNSUPPORTED = -11864L
private const val AV_ERROR_NO_LONGER_PLAYABLE = -11867L

private const val POSIX_ENOENT = 2L
private const val POSIX_EACCES = 13L
private const val POSIX_ECONNRESET = 54L
private const val POSIX_ENETDOWN = 50L
private const val POSIX_EHOSTUNREACH = 65L
private const val POSIX_ETIMEDOUT = 60L
