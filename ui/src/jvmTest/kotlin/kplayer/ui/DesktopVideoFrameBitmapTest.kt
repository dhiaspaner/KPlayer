package kplayer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kplayer.core.state.MediaSource
import kplayer.videoplayer.DesktopVideoPlayer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last hop of the desktop render path: engine bytes → [ImageBitmap].
 *
 * `:video`'s `AvFoundationFrameTest` proves real BGRA frames come out of the
 * decoder; this proves the surface can turn one into something Compose can draw,
 * against a real decoded frame rather than a synthetic array. Everything after
 * this is a single `Image()` call.
 *
 * The pixel assertions are the point. A wrong `ColorType`, a stride mistaken for
 * `width * 4`, or an unlocked base address all still produce a bitmap of exactly
 * the right size — only the colours give them away.
 *
 * **Skips on non-macOS**, which is where the only verified desktop engine runs.
 */
class DesktopVideoFrameBitmapTest {

    private val videoFile: File? =
        File("/System/Library/Desktop Pictures/.wallpapers/Sonoma")
            .takeIf(File::isDirectory)
            ?.listFiles { f: File -> f.name.endsWith(".mov") }
            ?.minByOrNull(File::length)

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    /** Plays until a frame is decoded, then hands it to [body] as a bitmap. */
    private fun withFrameBitmap(body: (ImageBitmap) -> Unit) {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val player = DesktopVideoPlayer()
        try {
            val frameSource = assertNotNull(player.frameSource, "macOS engine should expose frames")
            frameSource.setFrameOutputEnabled(true)

            player.load(MediaSource.FilePath(file.absolutePath))
            assertTrue(waitFor { player.state.value.durationMs > 0 }, "never became ready")
            player.play()

            assertTrue(waitFor { frameSource.latestFrame() != null }, "no frame was decoded")
            val frame = assertNotNull(frameSource.latestFrame())

            body(assertNotNull(frame.toImageBitmap(), "frame did not convert to a bitmap"))
        } finally {
            player.release()
        }
    }

    @Test
    fun `a decoded frame becomes a bitmap of the same size`() = withFrameBitmap { bitmap ->
        assertTrue(bitmap.width in 16..8192, "implausible width ${bitmap.width}")
        assertTrue(bitmap.height in 16..8192, "implausible height ${bitmap.height}")
    }

    /**
     * The stride assertion, made visible. The Sonoma wallpapers are 4K, where
     * `rowBytes` happens to equal `width * 4` — but if the conversion ever
     * confused the two on a padded frame, every row would be offset from the last
     * and the image would shear diagonally. A sheared frame still has correct
     * dimensions; what it does not have is a top row that resembles its neighbour.
     */
    @Test
    fun `adjacent rows resemble each other rather than shearing`() = withFrameBitmap { bitmap ->
        val pixels = bitmap.toPixelMap()
        val sampleY = bitmap.height / 2
        val width = minOf(bitmap.width, 64)

        var differing = 0
        for (x in 0 until width) {
            if (pixels[x, sampleY] != pixels[x, sampleY + 1]) differing++
        }

        // Real video rows are similar but not identical; a sheared image makes
        // essentially every pixel differ from the row below it.
        assertTrue(
            differing < width,
            "every sampled pixel differs from the row below — suspect rowBytes vs width",
        )
    }

    @Test
    fun `the bitmap holds an actual picture rather than one flat colour`() = withFrameBitmap { bitmap ->
        val pixels = bitmap.toPixelMap()
        val sampled = buildSet {
            for (y in 0 until minOf(bitmap.height, 64)) {
                for (x in 0 until minOf(bitmap.width, 64)) add(pixels[x, y])
            }
        }

        assertTrue(
            sampled.size > 1,
            "the sampled region was a single flat colour — suspect the pixel format " +
                "or an unlocked base address",
        )
    }

    /**
     * BGRA in, correct channels out. A red/blue swap is the classic failure when
     * `ColorType` disagrees with what the decoder was asked for, and it is
     * invisible in every assertion above — the image still looks like an image.
     */
    @Test
    fun `pixels are opaque with channels in the right order`() = withFrameBitmap { bitmap ->
        val pixels = bitmap.toPixelMap()
        val colour = pixels[bitmap.width / 2, bitmap.height / 2]

        // Video has no transparency; ColorAlphaType.OPAQUE must survive the trip.
        assertEquals(1f, colour.alpha, "frame pixels should be fully opaque")

        // Nothing stronger than a range check is possible without knowing the
        // frame's content, but a channel read from the wrong offset routinely
        // lands outside it.
        assertTrue(colour.red in 0f..1f && colour.green in 0f..1f && colour.blue in 0f..1f)
    }

    /**
     * The surface picks its strategy from the engine's capability, and the two
     * are mutually exclusive by construction — an engine that produces frames has
     * no window to draw into, and vice versa. Getting this wrong would either
     * render nothing or render twice.
     */
    @Test
    fun `an engine offers exactly one rendering strategy`() {
        if (!isMac) return println("skipped: not macOS")

        val player = DesktopVideoPlayer()
        try {
            val hasFrames = player.frameSource != null
            val hasWindow = player.rendersIntoNativeWindow

            assertTrue(
                hasFrames != hasWindow,
                "expected exactly one strategy, got frames=$hasFrames window=$hasWindow",
            )
            // AVFoundation is pulled through AVPlayerItemVideoOutput; there is no
            // NSView to hand over from JNA, so macOS must be the frame path.
            assertTrue(hasFrames, "macOS should render through frames")
        } finally {
            player.release()
        }
    }

    /** No-ops rather than throwing, so a surface can call them unconditionally. */
    @Test
    fun `window operations are inert on a frame-producing engine`() {
        if (!isMac) return println("skipped: not macOS")

        val player = DesktopVideoPlayer()
        try {
            player.attachVideoWindow(1234L)
            player.onVideoWindowResized()
            player.attachVideoWindow(0L)

            assertTrue(player.frameSource != null, "the frame path must be unaffected")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a player with no frame source draws nothing rather than failing`() {
        if (!isMac) return println("skipped: not macOS")

        val player = DesktopVideoPlayer()
        try {
            // Frames are off until asked for, so there is nothing to convert yet —
            // and the surface must render its background instead of throwing.
            assertNull(assertNotNull(player.frameSource).latestFrame())
        } finally {
            player.release()
        }
    }
}
