package kplayer.videoplayer.mac

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * The slice of CoreVideo needed to read a `CVPixelBuffer`'s bytes.
 *
 * Plain C functions, so unlike [ObjC] there is no message dispatch involved —
 * JNA calls them directly. The only thing that needs care is
 * [kCVPixelBufferPixelFormatTypeKey], which is an exported *global variable*
 * holding a `CFStringRef` rather than a function: the symbol's address is the
 * address of the pointer, so it takes one extra dereference to reach the string.
 */
internal object CoreVideo {

    private val library: NativeLibrary =
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreVideo.framework/CoreVideo")

    /** `kCVPixelFormatType_32BGRA` — the FourCC `'BGRA'`. */
    const val PIXEL_FORMAT_32_BGRA = 0x42475241

    /** `kCVPixelBufferLock_ReadOnly`, which lets the decoder keep its fast path. */
    private const val LOCK_READ_ONLY = 1L

    val kCVPixelBufferPixelFormatTypeKey: Pointer by lazy {
        library.getGlobalVariableAddress("kCVPixelBufferPixelFormatTypeKey").getPointer(0)
    }

    private val lockFn: Function by lazy { library.getFunction("CVPixelBufferLockBaseAddress") }
    private val unlockFn: Function by lazy { library.getFunction("CVPixelBufferUnlockBaseAddress") }
    private val baseAddressFn: Function by lazy { library.getFunction("CVPixelBufferGetBaseAddress") }
    private val widthFn: Function by lazy { library.getFunction("CVPixelBufferGetWidth") }
    private val heightFn: Function by lazy { library.getFunction("CVPixelBufferGetHeight") }
    private val bytesPerRowFn: Function by lazy { library.getFunction("CVPixelBufferGetBytesPerRow") }
    private val releaseFn: Function by lazy { library.getFunction("CVPixelBufferRelease") }

    fun width(pixelBuffer: Pointer): Int = widthFn.invokeLong(arrayOf<Any>(pixelBuffer)).toInt()

    fun height(pixelBuffer: Pointer): Int = heightFn.invokeLong(arrayOf<Any>(pixelBuffer)).toInt()

    fun bytesPerRow(pixelBuffer: Pointer): Int =
        bytesPerRowFn.invokeLong(arrayOf<Any>(pixelBuffer)).toInt()

    fun release(pixelBuffer: Pointer) {
        releaseFn.invokeVoid(arrayOf<Any>(pixelBuffer))
    }

    /**
     * Locks [pixelBuffer], hands its base address to [block], and unlocks it.
     *
     * The lock is not optional: the base address is undefined without it, and an
     * unbalanced lock wedges the buffer for every later reader — hence the
     * `finally`. Read-only so the decoder is not forced to move the buffer out of
     * whatever memory the hardware gave it.
     *
     * @return `null` if the buffer could not be locked, or whatever [block] returns.
     */
    fun <T> withLockedBytes(pixelBuffer: Pointer, block: (base: Pointer) -> T): T? {
        if (lockFn.invokeInt(arrayOf<Any>(pixelBuffer, LOCK_READ_ONLY)) != 0) return null
        return try {
            val base = baseAddressFn.invokePointer(arrayOf<Any>(pixelBuffer)) ?: return null
            block(base)
        } finally {
            unlockFn.invokeInt(arrayOf<Any>(pixelBuffer, LOCK_READ_ONLY))
        }
    }
}
