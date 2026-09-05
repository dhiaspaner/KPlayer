package kplayer.core.state

import platform.AVFoundation.AVErrorContentIsNotAuthorized
import platform.AVFoundation.AVErrorContentIsUnavailable
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFailedToLoadMediaData
import platform.AVFoundation.AVErrorFileFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorFormatUnsupported
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVErrorNoLongerPlayable
import platform.AVFoundation.AVErrorServerIncorrectlyConfigured
import platform.AVFoundation.AVErrorUndecodableMediaData
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotDecodeContentData
import platform.Foundation.NSURLErrorCannotDecodeRawData
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorFileDoesNotExist
import platform.Foundation.NSURLErrorFileIsDirectory
import platform.Foundation.NSURLErrorNoPermissionsToReadFile
import platform.Foundation.NSURLErrorResourceUnavailable
import platform.Foundation.NSURLErrorUnsupportedURL
import platform.Foundation.NSURLErrorZeroByteResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `appleErrorPlaybackError` lives in `appleSharedMain`, which compiles for the JVM
 * as well, so it spells Apple's error codes as literals — the desktop AVFoundation
 * engine reaches the same table through JNA and has no Objective-C constants to
 * import.
 *
 * This is the guard on that. Every literal in the table is asserted against the real
 * `platform.*` constant here, so a wrong or drifted code fails the build instead of
 * silently classifying a decode failure as a network blip for the rest of time.
 *
 * If you add a code to the table, add it here too.
 */
class AppleErrorCodesTest {

    @Test
    fun `the domain names match Foundation`() {
        assertEquals(NSURLErrorDomain, NS_URL_ERROR_DOMAIN)
        assertEquals(AVFoundationErrorDomain, AV_FOUNDATION_ERROR_DOMAIN)
    }

    @Test
    fun `NSURLError codes classify through the real constants`() {
        assertIs<PlaybackError.Aborted>(url(NSURLErrorCancelled))

        assertIs<PlaybackError.Source>(url(NSURLErrorBadURL))
        assertIs<PlaybackError.Source>(url(NSURLErrorUnsupportedURL))
        assertIs<PlaybackError.Source>(url(NSURLErrorFileDoesNotExist))
        assertIs<PlaybackError.Source>(url(NSURLErrorFileIsDirectory))
        assertIs<PlaybackError.Source>(url(NSURLErrorNoPermissionsToReadFile))
        assertIs<PlaybackError.Source>(url(NSURLErrorZeroByteResource))
        assertIs<PlaybackError.Source>(url(NSURLErrorResourceUnavailable))

        assertIs<PlaybackError.Decoder>(url(NSURLErrorCannotDecodeRawData))
        assertIs<PlaybackError.Decoder>(url(NSURLErrorCannotDecodeContentData))
    }

    @Test
    fun `AVError codes classify through the real constants`() {
        assertIs<PlaybackError.Decoder>(av(AVErrorDecodeFailed))
        assertIs<PlaybackError.Decoder>(av(AVErrorFileFormatNotRecognized))
        assertIs<PlaybackError.Decoder>(av(AVErrorFileFailedToParse))
        assertIs<PlaybackError.Decoder>(av(AVErrorDecoderNotFound))
        assertIs<PlaybackError.Decoder>(av(AVErrorUndecodableMediaData))
        assertIs<PlaybackError.Decoder>(av(AVErrorFormatUnsupported))

        assertIs<PlaybackError.Source>(av(AVErrorContentIsNotAuthorized))
        assertIs<PlaybackError.Source>(av(AVErrorContentIsUnavailable))
        assertIs<PlaybackError.Source>(av(AVErrorNoLongerPlayable))
        assertIs<PlaybackError.Source>(av(AVErrorServerIncorrectlyConfigured))

        assertIs<PlaybackError.Network>(av(AVErrorFailedToLoadMediaData))

        // Transient, and Unknown is the only variant our retry policies retry.
        assertIs<PlaybackError.Unknown>(av(AVErrorMediaServicesWereReset))
    }

    private fun url(code: Long) = appleErrorPlaybackError(NSURLErrorDomain, code)

    private fun av(code: Long) = appleErrorPlaybackError(AVFoundationErrorDomain, code)
}
