package kplayer.core.state

import kplayer.core.state.PlaybackError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The desktop actual's three routes in, all testable on any host with no media.
 *
 * The Apple half is the interesting one: this asserts the very table iOS classifies
 * through, from `appleSharedMain`, which is exactly why that source set exists. What
 * `iosTest`'s `AppleErrorCodesTest` adds is the other guard — that the literals below
 * still equal the real `platform.*` constants.
 */
class DesktopErrorMappingTest {

    // ── Apple, reached through JNA ────────────────────────────────────────────

    @Test
    fun `a cancelled url load is an abort rather than a failure`() {
        assertIs<PlaybackError.Aborted>(apple("NSURLErrorDomain", -999L))
    }

    @Test
    fun `a timeout is a network failure`() {
        assertIs<PlaybackError.Network>(apple("NSURLErrorDomain", -1001L))
    }

    @Test
    fun `a missing file is a source failure`() {
        assertIs<PlaybackError.Source>(apple("NSURLErrorDomain", -1100L))
    }

    @Test
    fun `an unrecognised container is a decoder failure`() {
        assertIs<PlaybackError.Decoder>(apple("AVFoundationErrorDomain", -11828L))
    }

    /**
     * Deliberate, and the one case where `Unknown` is the *right* answer rather
     * than a shrug: media services resetting heals on its own, and `Unknown` is
     * the only variant the retry policies still retry.
     */
    @Test
    fun `a media services reset stays unknown so it keeps being retried`() {
        assertIs<PlaybackError.Unknown>(apple("AVFoundationErrorDomain", -11819L))
    }

    @Test
    fun `an unknown domain keeps the description`() {
        val error = apple("SomeOtherDomain", 7L, "went wrong")

        assertIs<PlaybackError.Unknown>(error)
        assertEquals("went wrong", error.message)
    }

    @Test
    fun `a null description falls back to the domain and code`() {
        assertEquals("SomeOtherDomain error 7", apple("SomeOtherDomain", 7L).message)
    }

    // ── GStreamer ─────────────────────────────────────────────────────────────

    @Test
    fun `gstreamer text separates transport from source from codec`() {
        assertIs<PlaybackError.Network>(gstreamer(3, "Could not resolve server name."))
        assertIs<PlaybackError.Source>(gstreamer(3, "Resource not found."))
        assertIs<PlaybackError.Decoder>(
            gstreamer(5, "Your GStreamer installation is missing a plug-in: decoder")
        )
    }

    @Test
    fun `an unrecognised gstreamer message stays unknown and keeps the code`() {
        val error = gstreamer(9, "Internal data stream error.")

        assertIs<PlaybackError.Unknown>(error)
        assertEquals("GStreamer error 9: Internal data stream error.", error.message)
    }

    // ── A plain throw, from MFPlay's poll tick or any action ──────────────────

    @Test
    fun `a missing native library is not the media's fault`() {
        val error = NativeError(UnsatisfiedLinkError("no gstreamer in java.library.path"))
            .toPlaybackError()

        assertIs<PlaybackError.Unknown>(error)
        assertEquals(
            "Playback backend unavailable: no gstreamer in java.library.path",
            error.message,
        )
    }

    private fun apple(domain: String, code: Long, description: String? = null): PlaybackError =
        NativeError.avError(domain, code, description).toPlaybackError()

    private fun gstreamer(code: Int, message: String): PlaybackError =
        NativeError.gstreamer(code, message).toPlaybackError()
}
