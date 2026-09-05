package kplayer.videoplayer.frame

import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * One decoded frame, as raw **BGRA** bytes.
 *
 * BGRA rather than RGBA because it is what both decoders hand over natively —
 * `kCVPixelFormatType_32BGRA` on macOS, `BGRx` from GStreamer — and it is also
 * Skia's `ColorType.BGRA_8888`, so a frame reaches the screen without a channel
 * swap anywhere in the path.
 *
 * [rowBytes] is not always `width * 4`: hardware decoders align rows, so a
 * 1920-wide frame can arrive with a 2048-pixel stride and the extra bytes at the
 * end of each row are padding. Anything reading [pixels] must step by [rowBytes],
 * not by width.
 *
 * The array is **owned by the buffer that produced it** and will be overwritten
 * once enough newer frames have been published — see [FrameBuffer]. Copy it if you
 * need to keep it.
 *
 * Constructible on purpose, even though engines publish through `FrameBuffer`: a
 * fake [VideoFrameSource] is how you render a player in a `@Preview`, a screenshot
 * test or a design gallery, and that needs a frame with no decoder behind it. One
 * built by hand owns its array outright and is never recycled.
 */
class VideoFrame(
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val pixels: ByteArray,
    /** Monotonic per source. Lets a consumer skip redrawing a frame it already drew. */
    val sequence: Long,
)

/**
 * An engine that can decode into frames the renderer draws itself, rather than
 * handing them to the system compositor.
 *
 * Deliberately *not* part of [kplayer.core.player.MediaEngine]: Android draws through
 * a `SurfaceView`/`TextureView` and never sees a pixel in memory, so a frame
 * accessor there would be a method nobody implements. Surfaces test for this
 * interface rather than assuming it.
 *
 * Implemented by the desktop engines — where it is the *only* way to get a
 * picture, since there is no native view to hand over — and by iOS's
 * `AvVideoEngine`, where it is the alternative to `AVPlayerViewController`: UIKit
 * interop puts the video in its own layer above the Compose scene, so it cannot
 * be blurred, clipped to a rounded corner, or drawn under other Compose content.
 * Pulling the pixels makes the video ordinary Compose content at the cost of a
 * copy per frame, which is exactly the [kplayer.ui.VideoRenderMode] trade.
 */
interface VideoFrameSource {

    /**
     * The most recently decoded frame, or `null` before the first one arrives.
     *
     * Non-blocking and safe to call from the render thread: it reads a published
     * reference and never waits for the decoder.
     */
    fun latestFrame(): VideoFrame?

    /**
     * Starts or stops producing frames.
     *
     * **Off until something asks**, because producing costs a full copy per frame
     * — 8 MB for 1080p, 33 MB for 4K — and the surface that usually renders a
     * player does not want them. Android draws through a `SurfaceView`, iOS
     * through an `AVPlayerViewController`, and both let the system compositor
     * keep the frames; only the Compose-drawn surface
     * ([kplayer.ui.VideoRenderMode.TEXTURE]) needs pixels in memory.
     *
     * Idempotent, and safe to flip mid-playback: the render surface calls it on
     * attach and detach, so switching render modes starts and stops the decode
     * without disturbing playback.
     */
    fun setFrameOutputEnabled(enabled: Boolean)

    /**
     * Why this source is producing nothing, or `null` if it has not failed.
     *
     * A dropped frame is **not** a playback error: one frame missing is normal,
     * and putting the player into `Error` at 60fps over something cosmetic would
     * be worse than the symptom. But swallowing the reason entirely makes "the
     * drawn surface is black" and "every single frame failed" look identical from
     * the outside, which has cost real hours — a malformed pixel-format
     * dictionary vending planar YUV, and a test JVM with no skiko natives, both
     * presented as nothing but a black rectangle.
     *
     * So failures are reported here instead of through
     * [kplayer.core.state.PlaybackState.errorMessage]: observable, first-one-wins, and
     * cleared when the item changes. Read it when
     * [kplayer.ui.VideoRenderMode.TEXTURE] shows nothing; `:ui` exposes it for a
     * player through `rememberVideoFrameDiagnostics`.
     *
     * `null` here with no frames arriving is a different diagnosis from a
     * non-null value: nothing has gone wrong, so either output was never enabled
     * or the decoder has not produced a picture yet.
     */
    val frameOutputFailure: StateFlow<String?>
}

/**
 * A latest-frame-wins buffer between a decoder and a renderer.
 *
 * **Drops rather than queues**, which is the whole point. A queue would let a
 * stalled UI thread back up the decoder until it either stutters or runs out of
 * memory — and at 4K a single frame is 33 MB, so a queue of even four is a
 * problem. Rendering the newest frame and discarding whatever was missed is what
 * a video player wants anyway: a frame nobody drew in time is a frame nobody
 * should draw late.
 *
 * ### Why three slots
 *
 * The arrays are recycled, because allocating 33 MB per frame at 60fps is 2 GB/s
 * of garbage. Recycling means a slot handed to the renderer must not be
 * overwritten while it is still being read, and with [SLOTS] slots rotating, the
 * producer does not return to a given array until [SLOTS] more frames have been
 * published. One slot would tear on every frame and two would tear whenever the
 * renderer was mid-draw; three gives the renderer a full frame of slack while
 * costing one more buffer. A renderer that lags more than that gets a torn frame
 * rather than a stall, which is the correct trade for video.
 */
internal class FrameBuffer {

    private val slots = arrayOfNulls<ByteArray>(SLOTS)
    private var nextSlot = 0
    private var sequence = 0L

    /**
     * `@Volatile` rather than an atomic: the only operation is a plain publish and
     * a plain read, with one producer and one consumer, so all that is needed is
     * that the renderer never sees a half-written reference. It is also the one
     * spelling that works on every target — `java.util.concurrent` does not exist
     * on Kotlin/Native, and this buffer is shared by the desktop and iOS engines.
     */
    @Volatile
    private var latest: VideoFrame? = null

    /**
     * Fills the next slot through [fill] and publishes it as the current frame.
     *
     * Called only from the decoder's thread — [nextSlot] and [sequence] are not
     * synchronised, and do not need to be while that holds. Publication itself is
     * atomic, so the renderer sees a whole frame or the previous one, never a
     * half-swapped reference.
     *
     * @param fill receives an array of at least `rowBytes * height` bytes. It may
     *   be a recycled array holding an older frame's pixels, so it must be filled
     *   completely rather than patched.
     */
    fun publish(width: Int, height: Int, rowBytes: Int, fill: (ByteArray) -> Unit) {
        if (width <= 0 || height <= 0 || rowBytes <= 0) return

        val required = rowBytes * height
        val slot = nextSlot
        nextSlot = (nextSlot + 1) % SLOTS

        // Reallocated only when the geometry changes — a resolution switch mid-
        // stream, or the first frame. Steady state never allocates.
        val existing = slots[slot]
        val array = if (existing != null && existing.size == required) existing else {
            ByteArray(required).also { slots[slot] = it }
        }

        fill(array)
        latest = VideoFrame(width, height, rowBytes, array, ++sequence)
    }

    fun latest(): VideoFrame? = latest

    /**
     * Drops the current frame and the recycled arrays.
     *
     * Called on release and on a new source, so a stopped player does not keep
     * showing the last frame of the previous one — and so ~100 MB of pixel buffers
     * does not outlive the player that made them.
     */
    fun clear() {
        latest = null
        slots.fill(null)
        nextSlot = 0
    }

    private companion object {
        const val SLOTS = 3
    }
}
