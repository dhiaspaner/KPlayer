package kplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kplayer.ui.model.VideoScalingMode
import kplayer.videoplayer.frame.VideoFrame
import kplayer.videoplayer.frame.VideoFrameSource
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws decoded frames as ordinary Compose content.
 *
 * The alternative to a native view, selected by [VideoRenderMode.TEXTURE] on iOS
 * and the only option on desktop. An interop view is handed its own layer *above*
 * the Compose scene, which puts the picture outside Compose's drawing pass
 * entirely — it cannot be blurred, clipped to a rounded corner, cross-faded or
 * drawn under other Compose content. Everything here goes through the draw scope,
 * so all of that simply works.
 *
 * Shared by desktop and iOS: the engines behind [frameSource] reach AVFoundation
 * through completely different runtimes, but by the time a frame arrives here it
 * is just BGRA bytes.
 *
 * ### Why a `Canvas` and not an `Image`
 *
 * At 60fps the difference matters. `Image(bitmap = …)` takes the frame as a
 * *parameter*, so every new frame invalidates composition and re-runs layout.
 * Reading it inside a [Canvas]'s draw lambda invalidates only the **draw phase** —
 * sixty times a second, that is the difference between redrawing a bitmap and
 * rebuilding a subtree.
 */
@Composable
internal fun ComposeVideoFrameSurface(
    frameSource: VideoFrameSource,
    config: VideoSurfaceConfig,
    modifier: Modifier,
) {
    // Decoding is off by default because it is expensive, so the surface that
    // wants pixels is the thing that turns it on — and turns it off again on
    // dispose, so switching back to DIRECT or leaving the screen stops the copy.
    DisposableEffect(frameSource) {
        frameSource.setFrameOutputEnabled(true)
        // Published so a diagnostics panel can tell "nobody asked for frames" from
        // "frames were asked for and none came" — see VideoFrameDiagnostics. The
        // log line is the same fact for a run with no panel on screen: a black
        // surface with no "output enabled" line above it never asked for pixels.
        VideoFrameRenderReports.update(frameSource) { it.copy(outputEnabled = true) }
        logFrames("output enabled by the drawn surface")
        onDispose {
            frameSource.setFrameOutputEnabled(false)
            VideoFrameRenderReports.forget(frameSource)
            logFrames("output disabled — surface left the composition")
        }
    }

    var bitmap by remember(frameSource) { mutableStateOf<ImageBitmap?>(null) }

    // withFrameNanos, not a fixed delay: this ties the read to Compose's own
    // frame clock, so the surface samples exactly once per display refresh and
    // stops entirely when the composition is not drawing. Polling on a timer
    // would either miss frames or wake the UI thread when nothing is on screen.
    //
    // The raster is built here rather than in the draw lambda so the conversion
    // happens once per *frame* rather than once per draw, and never while the
    // canvas is mid-paint.
    LaunchedEffect(frameSource) {
        var lastSequence = -1L
        while (true) {
            withFrameNanos { }
            val next = frameSource.latestFrame() ?: continue
            if (next.sequence == lastSequence) continue
            lastSequence = next.sequence
            // Keeps the previous frame on a conversion failure rather than
            // blanking — one bad frame should not clear the screen.
            val converted = next.toImageBitmap { failure ->
                VideoFrameRenderReports.update(frameSource) { report ->
                    // First one wins: at 60fps a repeating failure would rewrite
                    // this sixty times a second and say nothing the first did not.
                    // Logged on that same edge, so the console gets one line per
                    // source rather than sixty a second or — worse — none at all
                    // because another player already used up a process-wide flag.
                    if (report.failure != null) {
                        report
                    } else {
                        logFrames("could not convert a frame to a bitmap: $failure")
                        report.copy(failure = failure.toString())
                    }
                }
            } ?: continue
            bitmap = converted
            VideoFrameRenderReports.update(frameSource) { report ->
                // The first frame is the line that says the whole path works, and
                // its geometry is what a stride bug shows up in. After that,
                // nothing: a per-frame log at 60fps is not a log.
                if (report.drawnFrames == 0L) {
                    logFrames("first frame drawn: ${next.width}x${next.height}, stride ${next.rowBytes}")
                }
                report.copy(drawnFrames = report.drawnFrames + 1)
            }
        }
    }

    Canvas(modifier.background(config.backgroundColor)) {
        // Read inside the draw lambda: this is the whole point of using a Canvas,
        // and moving it outside would put every frame back through composition.
        val image = bitmap ?: return@Canvas
        drawVideoFrame(image, config.scalingMode)
    }
}

/**
 * Draws [image] into the current bounds according to [scalingMode].
 *
 * `CROP` is the only one that needs clipping: it scales up until both axes are
 * covered, so the overflowing axis would otherwise paint outside the player and
 * over whatever sits next to it.
 */
private fun DrawScope.drawVideoFrame(image: ImageBitmap, scalingMode: VideoScalingMode) {
    val imageWidth = image.width.toFloat()
    val imageHeight = image.height.toFloat()
    if (imageWidth <= 0f || imageHeight <= 0f) return
    if (size.width <= 0f || size.height <= 0f) return

    val widthRatio = size.width / imageWidth
    val heightRatio = size.height / imageHeight

    val (targetWidth, targetHeight) = when (scalingMode) {
        // Letterbox: the smaller ratio leaves bars on one axis.
        VideoScalingMode.FIT -> min(widthRatio, heightRatio).let {
            imageWidth * it to imageHeight * it
        }
        // Fill and overflow: the larger ratio covers both axes.
        VideoScalingMode.CROP -> max(widthRatio, heightRatio).let {
            imageWidth * it to imageHeight * it
        }
        // Stretch: ignore the aspect ratio entirely.
        VideoScalingMode.FILL -> size.width to size.height
    }

    val left = ((size.width - targetWidth) / 2f).roundToInt()
    val top = ((size.height - targetHeight) / 2f).roundToInt()

    clipRect {
        drawImage(
            image = image,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(targetWidth.roundToInt(), targetHeight.roundToInt()),
        )
    }
}

/**
 * Wraps the frame's bytes as a Skia raster image.
 *
 * `BGRA_8888` matches what the engines produce, so there is no channel swap here
 * — that was the point of choosing BGRA in the first place. `OPAQUE` because
 * video has no alpha to respect, and claiming otherwise would make Skia blend on
 * every draw.
 *
 * The frame's array is owned and recycled by the engine's buffer, and
 * `makeRaster` copies rather than aliasing it, so the bitmap stays valid after
 * the slot is reused three frames later.
 *
 * @param onFailure what to do with a conversion that threw. Nothing by default:
 *   a torn frame is dropped and self-corrects, so only a caller tracking the
 *   whole path — the surface, which logs the first one and publishes it — has
 *   anything to say about it. Callers that just want a bitmap get `null`.
 */
internal fun VideoFrame.toImageBitmap(onFailure: (Throwable) -> Unit = {}): ImageBitmap? =
    runCatching {
        Image.makeRaster(
            imageInfo = ImageInfo(
                width = width,
                height = height,
                colorType = ColorType.BGRA_8888,
                alphaType = ColorAlphaType.OPAQUE,
            ),
            bytes = pixels,
            rowBytes = rowBytes,
        ).toComposeImageBitmap()
        // A frame torn by a geometry change mid-publish would throw here rather
        // than draw garbage; dropping it costs one frame and self-corrects.
    }.onFailure(onFailure).getOrNull()
