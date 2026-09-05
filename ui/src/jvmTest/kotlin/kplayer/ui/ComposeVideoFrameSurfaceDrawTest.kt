package kplayer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.MutableStateFlow
import kplayer.ui.model.VideoScalingMode
import kplayer.videoplayer.frame.VideoFrame
import kplayer.videoplayer.frame.VideoFrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface actually puts pixels on screen.
 *
 * Every other test in this module stops one step short: `DesktopVideoFrameBitmapTest`
 * proves a decoded frame becomes a valid [ImageBitmap], and there the trail went
 * cold — "and then Compose draws it" was inference. This renders the real
 * composable offscreen through [ImageComposeScene] and reads the result back, so
 * the draw is observed rather than assumed.
 *
 * No engine involved: the frame source is a fake holding a known image, which is
 * what makes the assertions exact — a solid red frame must produce red pixels in
 * the middle of the canvas.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeVideoFrameSurfaceDrawTest {

    private val canvasSize = 64

    /** Hands out one fixed frame, and records whether the surface asked for output. */
    private class FakeFrameSource(private val frame: VideoFrame) : VideoFrameSource {
        var enabled = false
            private set
        var enableCalls = 0

        override fun latestFrame(): VideoFrame = frame

        override fun setFrameOutputEnabled(enabled: Boolean) {
            this.enabled = enabled
            if (enabled) enableCalls++
        }

        override val frameOutputFailure = MutableStateFlow<String?>(null)
    }

    /** A solid frame of one colour, in the BGRA layout the engines produce. */
    private fun solidFrame(width: Int, height: Int, blue: Int, green: Int, red: Int): VideoFrame {
        val bytes = ByteArray(width * height * 4)
        for (pixel in 0 until width * height) {
            val offset = pixel * 4
            bytes[offset] = blue.toByte()
            bytes[offset + 1] = green.toByte()
            bytes[offset + 2] = red.toByte()
            bytes[offset + 3] = 0xFF.toByte()
        }
        return VideoFrame(width, height, rowBytes = width * 4, pixels = bytes, sequence = 1)
    }

    private fun render(
        source: VideoFrameSource,
        scalingMode: VideoScalingMode = VideoScalingMode.FIT,
    ): ImageBitmap {
        val scene = ImageComposeScene(width = canvasSize, height = canvasSize) {
            ComposeVideoFrameSurface(
                frameSource = source,
                config = VideoSurfaceConfig(scalingMode = scalingMode),
                modifier = Modifier.fillMaxSize(),
            )
        }
        return try {
            // Two frames: the first runs the LaunchedEffect that converts the
            // frame, the second draws what it produced. withFrameNanos means the
            // sample is tied to the frame clock, so one tick is not enough.
            scene.render()
            scene.render(nanoTime = 16_000_000L).toComposeImageBitmap()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a frame reaches the canvas`() {
        val source = FakeFrameSource(solidFrame(canvasSize, canvasSize, blue = 0, green = 0, red = 0xFF))

        val pixels = render(source).toPixelMap()
        val centre = pixels[canvasSize / 2, canvasSize / 2]

        // Red in, red out. A channel swap would show up as blue here, and a frame
        // that never drew would leave the black background.
        assertTrue(centre.red > 0.9f, "expected a red frame, got $centre")
        assertTrue(centre.blue < 0.1f, "blue channel leaked — suspect BGRA vs RGBA")
        assertTrue(centre.green < 0.1f)
    }

    /**
     * The renderer's half of `VideoFrameDiagnostics`.
     *
     * Without this, "frames arrive but nothing is drawn" and "frames never
     * arrive" are the same black rectangle — the whole reason the surface
     * publishes counters at all, so they are worth an assertion rather than a
     * hope.
     */
    @Test
    fun `the surface reports what it drew`() {
        val source = FakeFrameSource(solidFrame(8, 8, blue = 0xFF, green = 0, red = 0))
        val scene = ImageComposeScene(width = canvasSize, height = canvasSize) {
            ComposeVideoFrameSurface(
                frameSource = source,
                config = VideoSurfaceConfig(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        try {
            scene.render()
            scene.render(nanoTime = 16_000_000L)

            val report = VideoFrameRenderReports.of(source).value
            assertTrue(report.outputEnabled, "the surface asked for frames but did not say so")
            assertEquals(1L, report.drawnFrames, "one frame in, one conversion out")
            assertNull(report.failure)
        } finally {
            scene.close()
        }

        // Forgotten on dispose: the entry is what keeps a released engine
        // reachable from the registry.
        assertEquals(0L, VideoFrameRenderReports.of(source).value.drawnFrames)
    }

    @Test
    fun `the surface turns frame output on while it is composed`() {
        val source = FakeFrameSource(solidFrame(8, 8, blue = 0xFF, green = 0, red = 0))

        render(source)

        // Decoding costs a full copy per frame, so it must be the surface asking
        // for it — nothing else knows whether anyone is drawing.
        assertTrue(source.enableCalls > 0, "the surface never enabled frame output")
    }

    /**
     * The other half of that contract, and the one that makes switching render
     * modes cheap rather than merely possible.
     *
     * iOS composes this surface only under `VideoRenderMode.TEXTURE` and an
     * `AVPlayerViewController` otherwise, so flipping back to `DIRECT` disposes
     * this subtree — and if the dispose did not turn frame output off, the engine
     * would keep copying 8 MB a frame for a picture the compositor is now
     * drawing, with nothing on screen to show for it.
     */
    @Test
    fun `the surface turns frame output off when it leaves the composition`() {
        val source = FakeFrameSource(solidFrame(8, 8, blue = 0xFF, green = 0, red = 0))

        render(source) // renders, then closes the scene

        assertFalse(source.enabled, "frame output outlived the surface that asked for it")
    }

    /**
     * FIT letterboxes, so a frame far wider than the canvas must leave the
     * background visible above and below rather than stretching to fill it.
     */
    @Test
    fun `FIT letterboxes rather than stretching`() {
        val source = FakeFrameSource(solidFrame(64, 8, blue = 0, green = 0xFF, red = 0))

        val pixels = render(source, VideoScalingMode.FIT).toPixelMap()

        val centre = pixels[canvasSize / 2, canvasSize / 2]
        val topEdge = pixels[canvasSize / 2, 1]
        assertTrue(centre.green > 0.9f, "the frame should be drawn across the middle")
        assertTrue(
            topEdge.green < 0.1f,
            "an 8:1 frame in a square canvas must leave letterbox bars, got $topEdge",
        )
    }

    /**
     * FILL ignores the aspect ratio, so the same frame covers the whole canvas —
     * the top edge that FIT left as a bar is now picture.
     */
    @Test
    fun `FILL stretches to the whole canvas`() {
        val source = FakeFrameSource(solidFrame(64, 8, blue = 0, green = 0xFF, red = 0))

        val pixels = render(source, VideoScalingMode.FILL).toPixelMap()

        assertTrue(pixels[canvasSize / 2, 1].green > 0.9f, "FILL should reach the top edge")
        assertTrue(pixels[canvasSize / 2, canvasSize - 2].green > 0.9f, "and the bottom")
    }

    /**
     * CROP scales until both axes are covered and clips the overflow. Without the
     * clip the overflowing axis would paint outside the player's bounds and over
     * whatever sits beside it.
     */
    @Test
    fun `CROP covers the canvas`() {
        val source = FakeFrameSource(solidFrame(64, 8, blue = 0xFF, green = 0, red = 0))

        val pixels = render(source, VideoScalingMode.CROP).toPixelMap()

        assertTrue(pixels[canvasSize / 2, 1].blue > 0.9f, "CROP should cover the top edge")
        assertTrue(pixels[1, canvasSize / 2].blue > 0.9f, "and the left edge")
    }

    @Test
    fun `a source with no frame draws the configured background`() {
        val empty = object : VideoFrameSource {
            override fun latestFrame(): VideoFrame? = null
            override fun setFrameOutputEnabled(enabled: Boolean) = Unit
            override val frameOutputFailure = MutableStateFlow<String?>(null)
        }

        val pixels = render(empty).toPixelMap()

        // Black is VideoSurfaceConfig's default background; the point is that a
        // frameless source draws it rather than throwing or drawing garbage.
        val centre = pixels[canvasSize / 2, canvasSize / 2]
        assertEquals(0f, centre.red)
        assertEquals(0f, centre.green)
        assertEquals(0f, centre.blue)
    }
}
