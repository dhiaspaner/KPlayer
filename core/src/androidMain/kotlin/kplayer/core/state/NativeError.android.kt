package kplayer.core.state

import kplayer.core.state.jdkPlaybackError

/**
 * media3's failure vocabulary — plus the JDK's, for whatever a call throws.
 *
 * Two ways in, because Android fails in two ways. An asynchronous fault arrives as a
 * `PlaybackException` and is described by [media3]; a synchronous one is an ordinary
 * JDK exception and goes through the common `NativeError(Throwable)` constructor,
 * which classifies through `jdkPlaybackError` — the table `androidMain` shares with
 * `jvmMain`.
 *
 * The fields are `Int`s and `String`s rather than a `PlaybackException` on purpose:
 * media3 may not cross into `:core` (ADR 0001), so the engine extracts — digging
 * `errorCode` out of the exception, unwrapping an `InvalidResponseCodeException` to
 * find the HTTP status, reading the renderer format's MIME type — and this classifies.
 */
actual class NativeError private constructor(
    internal val cause: Throwable?,
    internal val message: String?,
    internal val errorCode: Int?,
    internal val httpStatusCode: Int?,
    internal val mimeType: String?,
) {
    /** What a call into ExoPlayer threw: a released player, a dead socket, an interrupt. */
    actual constructor(cause: Throwable?) : this(cause, cause?.message, null, null, null)

    companion object {
        /**
         * A media3 `PlaybackException`, taken apart by the engine that caught it.
         *
         * @param errorCode `PlaybackException.errorCode`.
         * @param httpStatusCode the status from an `InvalidResponseCodeException` in
         *   the cause chain, when there is one. This is what lets a retry policy tell
         *   a 503 worth waiting on from a 403 that never heals, so it is worth the
         *   engine's digging.
         * @param mimeType `ExoPlaybackException.rendererFormat?.sampleMimeType`, which
         *   names the codec that could not be handled.
         */
        fun media3(
            errorCode: Int,
            message: String? = null,
            cause: Throwable? = null,
            httpStatusCode: Int? = null,
            mimeType: String? = null,
        ): NativeError = NativeError(cause, message, errorCode, httpStatusCode, mimeType)
    }
}

/**
 * Grouped by `errorCode`'s documented thousands-ranges rather than by individual
 * constant, so a media3 upgrade that adds codes keeps classifying them sensibly.
 * The HTTP/file codes are pulled out of the I/O range on purpose: a 404 or a missing
 * file is a bad *source*, not a flaky network, and retrying it just fails again a
 * second later.
 */
actual fun NativeError.toPlaybackError(): PlaybackError {
    val code = errorCode
        ?: return cause?.jdkPlaybackError() ?: PlaybackError.Unknown(message)

    return when (code) {
        // Pulled out of the 2xxx I/O range: a 404, a missing file, a permission
        // refusal and a cleartext block are all bad *sources*. None of them is a
        // flaky connection, and retrying any of them fails again a second later.
        ERROR_CODE_IO_BAD_HTTP_STATUS,
        ERROR_CODE_IO_FILE_NOT_FOUND,
        ERROR_CODE_IO_NO_PERMISSION,
        ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> PlaybackError.Source(message, cause, httpStatusCode)

        // 2xxx I/O: DNS, timeouts, dropped connections, unreachable network.
        in 2000..2999 -> PlaybackError.Network(cause, message, httpStatusCode)

        // 3xxx parsing — a malformed container or manifest is a broken source.
        in 3000..3999 -> PlaybackError.Source(message, cause, httpStatusCode)

        // 4xxx decoding, 5xxx renderer init: the device cannot play these bytes.
        in 4000..5999 -> PlaybackError.Decoder(message, cause, mimeType)

        // 6xxx DRM. Not a decode problem and not worth a second attempt: an unusable
        // licence is unusable the next time too.
        in 6000..6999 -> PlaybackError.Source(message, cause, httpStatusCode)

        else -> PlaybackError.Unknown(message, cause)
    }
}

// media3's own constants, spelled out because :core has no media3 dependency.
private const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
private const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
private const val ERROR_CODE_IO_NO_PERMISSION = 2006
private const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 2007
private const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008
