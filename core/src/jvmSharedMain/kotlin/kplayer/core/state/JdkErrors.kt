package kplayer.core.state

import kplayer.core.state.PlaybackError
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import javax.net.ssl.SSLException

/**
 * One JDK table for both JVM targets.
 *
 * `jvmSharedMain` exists for this file: Android and desktop throw the *same* JDK
 * exceptions — ExoPlayer's data sources and GStreamer's are both reading through
 * `java.net` — so recognising them twice would be the duplication this whole seam
 * removes, just moved inside `:core`. Each target's `NativeError.toPlaybackError()`
 * falls back to it when it has nothing but a throwable.
 *
 * Only the JDK is matched here. A media3 `PlaybackException` or a GStreamer bus
 * error never reaches this function: those arrive asynchronously and their engines
 * describe them with far more context, through `NativeError.media3` and
 * `NativeError.gstreamer`. What lands here is what a *call* threw — a released
 * player, a missing native library, a socket that died under a synchronous read.
 */
internal fun Throwable.jdkPlaybackError(): PlaybackError = when (this) {
    // Cancellation, in its two JDK spellings. Neither is a fault.
    is InterruptedException, is ClosedByInterruptException -> PlaybackError.Aborted(message, this)

    // A timeout is an InterruptedIOException too, so it has to be caught first.
    is SocketTimeoutException -> PlaybackError.Network(this, message)
    is InterruptedIOException -> PlaybackError.Aborted(message, this)

    // The file or URL is wrong; waiting does not help.
    is FileNotFoundException -> PlaybackError.Source(message, this)

    is UnknownHostException,
    is ConnectException,
    is NoRouteToHostException,
    is PortUnreachableException,
    is SocketException,
    is SSLException,
    is HttpRetryException,
    -> PlaybackError.Network(this, message)

    // Everything else from java.io is transport as far as we can tell.
    is IOException -> PlaybackError.Network(this, message)

    // The native library is not installed, or is the wrong architecture. Nothing
    // about the media is wrong, and no retry will conjure the library.
    is UnsatisfiedLinkError, is NoClassDefFoundError ->
        PlaybackError.Unknown("Playback backend unavailable: $message", this)

    else -> PlaybackError.Unknown(message, this)
}
