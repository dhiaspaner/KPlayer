package kplayer.core.state

import kplayer.core.state.PlaybackError

/**
 * Three desktop vocabularies behind one seam.
 *
 * The JVM is the one target with more than one backend: GStreamer everywhere,
 * AVFoundation through JNA on macOS, MFPlay on Windows — and each fails in its own
 * terms. A bus `ERROR` carries a code and a message, a failed `AVPlayerItem` carries
 * an `NSError` domain and code, and MFPlay simply throws. Whichever one an engine
 * has, it builds a [NativeError] and the classification is the same call.
 *
 * The Apple half classifies through `appleErrorPlaybackError` in `appleSharedMain` —
 * the same table iOS uses, reached here from a completely different interop.
 */
actual class NativeError private constructor(
    internal val cause: Throwable?,
    internal val message: String?,
    internal val gstreamerCode: Int?,
    internal val appleDomain: String?,
    internal val appleCode: Long?,
) {
    /** What a call into a desktop backend threw — including an MFPlay poll tick. */
    actual constructor(cause: Throwable?) : this(cause, cause?.message, null, null, null)

    companion object {
        /**
         * A GStreamer bus `ERROR`.
         *
         * Only a code and a message, because that is all `gst1-java-core` hands the
         * `Bus.ERROR` callback — see [gstreamerPlaybackError] for why that forces
         * classification onto the text.
         */
        fun gstreamer(code: Int, message: String): NativeError =
            NativeError(null, message, code, null, null)

        /**
         * An `NSError` read off AVFoundation by `objc_msgSend`.
         *
         * The JNA route to exactly the fields `AvAudioEngine` gets for free from
         * Kotlin/Native interop, and from here on the two are indistinguishable.
         */
        fun avError(domain: String?, code: Long, description: String? = null): NativeError =
            NativeError(null, description, null, domain, code)
    }
}

actual fun NativeError.toPlaybackError(): PlaybackError = when {
    gstreamerCode != null -> gstreamerPlaybackError(gstreamerCode, message.orEmpty())
    appleCode != null -> appleErrorPlaybackError(appleDomain, appleCode, message, cause)
    else -> cause?.jdkPlaybackError() ?: PlaybackError.Unknown(message)
}

/**
 * A GStreamer bus `ERROR` in [PlaybackError]'s terms.
 *
 * Classified on the message text, which is not a choice: `gst1-java-core`'s
 * `Bus.ERROR` callback hands over the `GError` code and message but **drops the
 * domain**, and the code alone is ambiguous — `GST_RESOURCE_ERROR_NOT_FOUND` and
 * `GST_STREAM_ERROR_DECODE` are both `3`. Without a domain the text is the only
 * thing that separates "server not found" from "no decoder", so the match runs on
 * the stable fragments of GStreamer's own English strings and falls back to
 * [PlaybackError.Unknown] — which stays retryable — whenever nothing matches.
 *
 * If the binding ever exposes the domain, replace all of this with a code lookup.
 */
private fun gstreamerPlaybackError(code: Int, message: String): PlaybackError {
    val detail = "GStreamer error $code: $message"
    val text = message.lowercase()
    return when {
        text.containsAny("resolve", "not connect", "connection", "network", "timed out", "timeout") ->
            PlaybackError.Network(message = detail)

        text.containsAny("not found", "open resource", "does not exist", "no such file", "not authorized") ->
            PlaybackError.Source(detail)

        text.containsAny("decode", "decoder", "not-negotiated", "codec", "no suitable plugins", "stream format") ->
            PlaybackError.Decoder(detail)

        else -> PlaybackError.Unknown(detail)
    }
}

private fun String.containsAny(vararg needles: String): Boolean = needles.any { it in this }
