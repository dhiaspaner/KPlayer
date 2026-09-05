package kplayer.videoplayer

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kplayer.videoplayer.frame.FrameBuffer
import kplayer.videoplayer.frame.FrameOutputFailures
import kplayer.videoplayer.frame.FramePump
import kplayer.videoplayer.frame.PixelSource
import kplayer.videoplayer.frame.VideoFrame
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.rate
import platform.AVFoundation.removeOutput
import platform.AVFoundation.tracks
import platform.CoreMedia.CMTime
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatBGRA8
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetCStringPtr
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSLog
import platform.posix.memcpy

/**
 * Pulls decoded frames out of an `AVPlayerItem` as BGRA bytes.
 *
 * The iOS twin of the desktop frame pump, and structurally the same thing — an
 * `AVPlayerItemVideoOutput` polled for pixel buffers — but for a different
 * reason. On desktop it is the *only* way to get a picture, because there is no
 * native view to hand over. Here there is one, and it is better: an
 * `AVPlayerViewController` keeps frames in the system compositor and costs no
 * copy at all. This exists for the case that surface cannot serve, which is that
 * UIKit interop puts the video in its own layer **above** the Compose scene. A
 * video rendered that way cannot be blurred, clipped to a rounded corner,
 * cross-faded, or drawn underneath other Compose content — the sample's blur
 * toggle is the visible demonstration.
 *
 * So this is the expensive half of the [kplayer.ui.VideoRenderMode] trade, and it
 * is **off unless a surface asks for it**: 1080p is 8 MB per frame, and paying
 * that while an `AVPlayerViewController` is drawing would be pure waste.
 *
 * ### Two ways out of a pixel buffer
 *
 * The output is asked for packed BGRA, and when it obliges the frame is a single
 * `memcpy` with no conversion at all. When it does not — the attributes
 * dictionary is fiddly to build from Kotlin/Native, and a decoder may vend its
 * native planar YUV regardless — the buffer has no single base address to copy
 * from, and CoreImage converts it instead. That costs a conversion per frame, so
 * it stays the fallback, but it means a frame is drawn either way rather than the
 * surface going black on a format nobody predicted.
 *
 * ### Why polling here too
 *
 * Unlike the desktop engine — which polls because JNA cannot define the
 * Objective-C class KVO needs — Kotlin/Native *can* subclass `NSObject`, so
 * `CADisplayLink` or `AVPlayerItemOutputPullDelegate` are both reachable. Polling
 * is still the better fit: `hasNewPixelBufferForItemTime:` is the check either
 * design ends up making, a coroutine loop cancels cleanly with the rest of the
 * engine, and it keeps the copy **off the main thread**, which a `CADisplayLink`
 * target would not.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvVideoFrameOutput(private val player: AVPlayer) : PixelSource {

    private val frames = FrameBuffer()

    /** The shared policy for *when* to copy; see [FramePump]. */
    private val pump = FramePump(frames, this)

    private var output: AVPlayerItemVideoOutput? = null

    /** The item [output] is attached to, so it can be detached from the same one. */
    private var attachedItem: AVPlayerItem? = null

    private var pumpJob: Job? = null

    /**
     * `Dispatchers.Default`, not `Main`. `AVPlayerItemVideoOutput` is safe to pull
     * from any thread, and an 8 MB copy per frame on the main thread would show up
     * as dropped frames in the Compose scene rather than in the video.
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    var isEnabled: Boolean = false
        private set

    /**
     * The first thing that went wrong, for diagnostics.
     *
     * Frames are dropped rather than reported — one bad frame is not a playback
     * error — but a pump that fails on *every* frame and says nothing is
     * indistinguishable from a video with no picture. That is exactly how the
     * malformed attributes dictionary hid: a planar buffer, a null base address,
     * a silent `return`, and a black surface.
     *
     * That particular case no longer reaches here — a planar buffer is converted
     * rather than refused — so anything appearing here now is something neither
     * path could handle.
     *
     * `NSLog` rather than the default `println`, so the line lands in the device
     * console alongside everything else the app logs.
     */
    private val failures = FrameOutputFailures { line -> NSLog("%@", line) }

    val failure: StateFlow<String?> get() = failures.failure

    private fun report(reason: String) = failures.report(reason)

    /**
     * Built lazily, and only if a buffer ever needs converting — a
     * correctly-configured output never touches it. Reused across frames because
     * a `CIContext` is expensive to create and cheap to keep.
     */
    private var ciContext: CIContext? = null

    fun latestFrame(): VideoFrame? = frames.latest()

    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        isEnabled = enabled
        if (enabled) start() else stop()
    }

    /**
     * Re-attaches to a newly loaded item.
     *
     * Outputs belong to an `AVPlayerItem`, not to the player, so every
     * `replaceCurrentItemWithPlayerItem:` orphans the previous attachment. The
     * engine calls this after each load; when frames are disabled it does nothing
     * beyond forgetting the stale item.
     */
    fun onItemChanged() {
        detach()
        frames.clear()
        pump.reset()
        // A reason belonging to the previous item must not be shown against the
        // new one, and first-wins would otherwise hide whatever this item hits.
        failures.clear()
        if (isEnabled) attach()
    }

    /**
     * The picture is stale even though nothing new was produced — a seek while
     * paused. Delegated to the pump, which forces exactly one copy.
     */
    fun refresh() = pump.requestRefresh()

    fun release() {
        stop()
        detach()
        frames.clear()
    }

    private fun start() {
        attach()
        pumpJob?.cancel()
        pumpJob = scope.launch {
            while (isActive) {
                runCatching { pump.tick() }.onFailure { report("pump: ${it.message}") }
                delay(PUMP_INTERVAL_MS)
            }
        }
    }

    private fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        pump.reset()
        detach()
        // Dropped rather than left showing: a surface that switched away from
        // frame rendering must not keep several megabytes alive for a picture
        // nobody is drawing.
        frames.clear()
    }

    private fun attach() {
        val item = player.currentItem ?: run {
            NSLog(
                "VIDEO DEBUG: attach skipped — " +
                        "player.currentItem == null"
            )
            detach()
            return
        }

        NSLog(
            "VIDEO DEBUG: attaching to item " +
                    "status=${item.status} " +
                    "duration=${CMTimeGetSeconds(item.duration)} " +
                    "tracks=${item.tracks.size} " +
                    "playerTime=${CMTimeGetSeconds(player.currentTime())} " +
                    "rate=${player.rate}"
        )

        if (attachedItem === item && output != null) {
            return
        }

        detach()

        val formatKey = kCVPixelBufferPixelFormatTypeKey
            ?.let(::cfStringToKotlin)
            ?: run {
                report("could not read kCVPixelBufferPixelFormatTypeKey")
                return
            }

        val created = AVPlayerItemVideoOutput(
            pixelBufferAttributes = mapOf<Any?, Any?>(
                formatKey to kCVPixelFormatType_32BGRA.toInt(),
            ),
        )

        item.addOutput(created)

        output = created
        attachedItem = item

        NSLog("VIDEO DEBUG: video output attached")
    }

    private fun detach() {
        val currentOutput = output ?: return
        attachedItem?.removeOutput(currentOutput)
        output = null
        attachedItem = null
    }

    // ── PixelSource: the platform half of the shared pump ────────────────────

    /**
     * The item can be replaced under us between ticks — outputs belong to the
     * `AVPlayerItem`, not the player — so a load while playing orphans the
     * previous attachment and this re-makes it.
     */
    override fun ensureAttached(): Boolean {
        if (attachedItem !== player.currentItem) attach()
        return output != null
    }

    override fun hasNewFrame(): Boolean {
        val currentOutput = output ?: return false
        return currentOutput.hasNewPixelBufferForItemTime(player.currentTime())
    }

    override fun publishCurrentFrame(into: FrameBuffer): Boolean {
        val currentOutput = output ?: return false
        val itemTime: CValue<CMTime> = player.currentTime()

        // Not gated on hasNewPixelBufferForItemTime: the pump decides that, and
        // deliberately bypasses it for the first frame and after a seek. Copying
        // unconditionally here is what lets a paused player draw at all.

        NSLog("hasNewPixelBufferForItemTime ${currentOutput.hasNewPixelBufferForItemTime(itemTime)}")
        val pixelBuffer = currentOutput.copyPixelBufferForItemTime(itemTime, null)
            if (pixelBuffer == null) {
            NSLog("publishCurrentFrame: null buffer at t=${CMTimeGetSeconds(itemTime)}")
            return false
        }
        try {
            val width = CVPixelBufferGetWidth(pixelBuffer).toInt()
            val height = CVPixelBufferGetHeight(pixelBuffer).toInt()
            val rowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer).toInt()

            // Read-only so the decoder is not forced to move the buffer out of
            // whatever memory the hardware gave it. Unbalancing the lock wedges the
            // buffer for every later reader, hence the `finally`.
            if (CVPixelBufferLockBaseAddress(pixelBuffer, LOCK_READ_ONLY) != 0) {
                report("CVPixelBufferLockBaseAddress failed")
                return false
            }
            try {
                val base = CVPixelBufferGetBaseAddress(pixelBuffer)
                if (base != null) {
                    // Fast path: the buffer is already packed BGRA, so this is a
                    // straight copy with no conversion at all. Written into the
                    // buffer the pump handed us, not our own field — the pump owns
                    // which buffer a frame goes to.
                    into.publish(width, height, rowBytes) { destination ->
                        // One bulk copy while the lock is held, and nothing else in
                        // here — anything slower would be blocking the decoder from
                        // reusing the buffer.
                        destination.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), base, destination.size.toULong())
                        }
                    }
                } else {
                    // A null base address means the buffer is **planar** — its
                    // planes have to be read individually, so there is nothing to
                    // memcpy. That happens whenever the output's
                    // pixelBufferAttributes did not take effect and AVFoundation
                    // vended the codec's native YUV instead of the BGRA we asked
                    // for.
                    // Converting rather than giving up: CoreImage reads any format
                    // the decoder can produce and writes packed BGRA, on the GPU.
                    // It costs a conversion per frame, so it stays the fallback —
                    // but it means a frame is drawn either way, instead of the
                    // surface going black on a format we did not predict.
                    if (!renderThroughCoreImage(pixelBuffer, width, height, into)) return false
                }
            } finally {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, LOCK_READ_ONLY)
            }
            return true
        } finally {
            // "copy" in the selector name means we own it. Without this the decoder
            // leaks a whole frame every tick.
            CVPixelBufferRelease(pixelBuffer)
        }
    }

    /**
     * Converts any pixel buffer to packed BGRA via CoreImage.
     *
     * `CIContext` is expensive to build and safe to reuse, so it is created once
     * and only if this path is ever taken — a correctly-configured output never
     * touches it.
     *
     * The destination has no row padding (`rowBytes == width * 4`), because we
     * choose the layout here rather than accepting the decoder's.
     */
    private fun renderThroughCoreImage(
        pixelBuffer: CVPixelBufferRef,
        width: Int,
        height: Int,
        into: FrameBuffer,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val context = ciContext ?: CIContext().also { ciContext = it }
        val image = CIImage.imageWithCVPixelBuffer(pixelBuffer) ?: run {
            report("CIImage could not wrap the pixel buffer")
            return false
        }

        val rowBytes = width * 4
        into.publish(width, height, rowBytes) { destination ->
            destination.usePinned { pinned ->
                context.render(
                    image = image,
                    toBitmap = pinned.addressOf(0),
                    rowBytes = rowBytes.toLong(),
                    bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                    format = kCIFormatBGRA8,
                    colorSpace = null,
                )
            }
        }
        return true
    }

    /**
     * A `CFStringRef` as a Kotlin string.
     *
     * `CFStringGetCStringPtr` usually succeeds outright for the constant strings
     * CoreVideo exports, but it is documented as allowed to fail for any string,
     * so the copying path is not optional.
     */
    private fun cfStringToKotlin(cfString: CFStringRef): String? = memScoped {
        CFStringGetCStringPtr(cfString, kCFStringEncodingUTF8)?.toKString()?.let { return@memScoped it }

        val maxSize = CFStringGetMaximumSizeForEncoding(
            CFStringGetLength(cfString),
            kCFStringEncodingUTF8,
        ) + 1
        val buffer = allocArray<ByteVar>(maxSize)
        if (CFStringGetCString(cfString, buffer, maxSize, kCFStringEncodingUTF8)) {
            buffer.toKString()
        } else {
            null
        }
    }

    private companion object {
        /**
         * ~60fps. Not synchronised to the media's frame rate, because
         * `it:` already answers "is there anything new?"
         * — over-polling costs one message send, under-polling drops frames.
         */
        const val PUMP_INTERVAL_MS = 16L

        /** `kCVPixelBufferLock_ReadOnly`. */
        const val LOCK_READ_ONLY = 1uL
    }
}
