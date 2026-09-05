package kplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does `Modifier.blur` actually reach the video on desktop?
 *
 * The question matters because the answer decides whether the desktop surface's
 * whole premise holds. The claim is that drawing frames as an ordinary Compose
 * `Image` makes the video a real participant in the layout — blurrable, clippable
 * — unlike an interop view. That is only true if Compose's blur works on this
 * backend at all, and `Modifier.blur` is a documented no-op below Android API 31,
 * so "it works on Android" is not evidence about skiko.
 *
 * Rendered offscreen with [ImageComposeScene], so no window and no display are
 * involved: a hard-edged black/white checkerboard is drawn twice, once plain and
 * once blurred, and the two are compared. A blur that did nothing would produce
 * identical images; a working one softens the edges into greys that the source
 * image does not contain.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopBlurSupportTest {

    private val size = 64

    /**
     * A 2x2 black/white checkerboard, built through the same raster path the
     * desktop surface uses for real frames. All hard edges: any blur at all has
     * to introduce intermediate greys, and there are none to begin with.
     */
    private fun checkerboard(): ImageBitmap {
        val bytes = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val white = ((x / (size / 2)) + (y / (size / 2))) % 2 == 0
                val value = if (white) 0xFF.toByte() else 0x00.toByte()
                val offset = (y * size + x) * 4
                bytes[offset] = value       // B
                bytes[offset + 1] = value   // G
                bytes[offset + 2] = value   // R
                bytes[offset + 3] = 0xFF.toByte()
            }
        }
        return Image.makeRaster(
            imageInfo = ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
            bytes = bytes,
            rowBytes = size * 4,
        ).toComposeImageBitmap()
    }

    private fun render(modifier: Modifier): ImageBitmap {
        val source = checkerboard()
        val scene = ImageComposeScene(width = size, height = size) {
            Box(Modifier.fillMaxSize().then(modifier)) {
                Image(
                    bitmap = source,
                    contentDescription = null,
                    modifier = Modifier.size(size.dp),
                )
            }
        }
        return try {
            scene.render().toComposeImageBitmap()
        } finally {
            scene.close()
        }
    }

    /**
     * The load-bearing assertion for the desktop surface's premise: blurring a
     * hard-edged image must introduce colours the original did not have.
     */
    @Test
    fun `blur changes what is drawn on the desktop backend`() {
        val plain = render(Modifier).toPixelMap()
        val blurred = render(Modifier.blur(8.dp)).toPixelMap()

        var differing = 0
        var intermediate = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (plain[x, y] != blurred[x, y]) differing++
                val red = blurred[x, y].red
                // Neither black nor white: only a blur can produce these.
                if (red > 0.1f && red < 0.9f) intermediate++
            }
        }

        assertTrue(
            differing > 0,
            "Modifier.blur produced a pixel-identical image — it is a no-op on this backend, " +
                "which would mean the desktop surface cannot be blurred either",
        )
        assertTrue(
            intermediate > 0,
            "blurring a black/white checkerboard produced no intermediate greys",
        )
    }
}
