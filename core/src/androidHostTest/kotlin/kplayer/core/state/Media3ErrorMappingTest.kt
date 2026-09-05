package kplayer.core.state

import kplayer.core.state.NativeError
import kplayer.core.state.PlaybackError
import kplayer.core.state.toPlaybackError
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * media3's classification, asserted without a device, an emulator or any media.
 *
 * That is the point of `NativeError.media3` taking `Int`s and `String`s rather than a
 * `PlaybackException`: the engine does the extraction, which needs media3 and a real
 * failure, and everything below the extraction is a pure function over primitives.
 *
 * These assert the distinctions a retry policy actually acts on — transport vs.
 * source vs. codec — rather than every code in the range.
 */
class Media3ErrorMappingTest {

    @Test
    fun `a bad http status is a source failure, not a network one`() {
        val error = media3(errorCode = 2004, message = "403", httpStatusCode = 403)

        assertIs<PlaybackError.Source>(error)
        assertEquals(403, error.httpStatusCode)
    }

    @Test
    fun `a dropped connection is a network failure and keeps its status`() {
        val error = media3(errorCode = 2001, message = "unreachable")

        assertIs<PlaybackError.Network>(error)
        assertEquals(null, error.httpStatusCode)
    }

    @Test
    fun `a decoder failure carries the codec that could not be handled`() {
        val error = media3(errorCode = 4001, mimeType = "video/av01")

        assertIs<PlaybackError.Decoder>(error)
        assertEquals("video/av01", error.mimeType)
    }

    @Test
    fun `a malformed manifest is a source failure`() {
        assertIs<PlaybackError.Source>(media3(errorCode = 3001))
    }

    @Test
    fun `an unrecognised media3 code stays unknown`() {
        assertIs<PlaybackError.Unknown>(media3(errorCode = 1000))
    }

    /**
     * The other half of the Android actual: with no media3 code to go on, a plain
     * throw falls through to the JDK table `androidMain` shares with `jvmMain`.
     */
    @Test
    fun `a synchronous throw still classifies through the JDK table`() {
        assertIs<PlaybackError.Network>(
            NativeError(UnknownHostException("nope")).toPlaybackError()
        )
    }

    private fun media3(
        errorCode: Int,
        message: String? = null,
        httpStatusCode: Int? = null,
        mimeType: String? = null,
    ): PlaybackError = NativeError.media3(
        errorCode = errorCode,
        message = message,
        httpStatusCode = httpStatusCode,
        mimeType = mimeType,
    ).toPlaybackError()
}
