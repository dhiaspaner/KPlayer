package kplayer.videoplayer.mac

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * The Objective-C runtime, reached through JNA.
 *
 * Enough of it to drive `AVPlayer` and no more: look a class up, register a
 * selector, send a message. Everything AVFoundation-shaped lives one layer up in
 * [kplayer.videoplayer.AvFoundationVideoEngine]; this file knows only about the
 * runtime and the ABI.
 *
 * ### Why this is possible at all
 *
 * The received wisdom — recorded in `docs/prompts/desktop-native-video-engines.md`
 * — is that JNA cannot do AVFoundation, for two reasons. One of them is true and
 * one is not, and the difference is what this engine is built on:
 *
 * - **True: JNA cannot *define* an Objective-C class.** `objc_msgSend` calls into
 *   ObjC fine, but KVO and `NSNotificationCenter` need an object implementing
 *   `observeValueForKeyPath:…`, and neither JNA nor Panama can synthesise one.
 *   That is why the engine polls rather than observes.
 * - **False: "`CMTime` is a 24-byte struct, JNA cannot handle that cleanly."**
 *   JNA calls through libffi, which implements the platform ABI including AArch64's
 *   indirect struct return, so a `Structure.ByValue` return works — measured, both
 *   as a return value (`-[AVPlayerItem duration]`) and as an argument
 *   (`-[AVPlayer seekToTime:]`). The one real subtlety is [msgSendStructFn] below.
 *
 * ### Threading
 *
 * `AVPlayer` is safe to message from any thread — it is not a UIKit/AppKit object —
 * so the engine's poller and the action scope can both drive it without hopping to
 * the main thread. Only a render layer would need AppKit's thread, and there is
 * none here yet.
 */
internal object ObjC {

    private val objc: NativeLibrary = NativeLibrary.getInstance("objc")

    /**
     * Loading the framework by path, not by name: `NativeLibrary.getInstance
     * ("AVFoundation")` searches `java.library.path` and the usual dylib name
     * mangling, neither of which finds a macOS framework bundle.
     */
    private val avFoundation: NativeLibrary =
        NativeLibrary.getInstance("/System/Library/Frameworks/AVFoundation.framework/AVFoundation")

    private val msgSendFn: Function = objc.getFunction("objc_msgSend")
    private val getClassFn: Function = objc.getFunction("objc_getClass")
    private val selFn: Function = objc.getFunction("sel_registerName")

    /**
     * The one place the two Apple architectures diverge for our purposes.
     *
     * On x86_64 a struct larger than 16 bytes is returned through a hidden pointer
     * argument, and Objective-C exposes that as a *different entry point*:
     * `objc_msgSend_stret`. On arm64 there is no such split — the indirect return
     * uses `x8` and plain `objc_msgSend` handles it. Sending a `CMTime`-returning
     * message through the wrong one silently returns garbage rather than failing,
     * which is exactly the kind of bug that costs an afternoon, so the choice is
     * made once here.
     */
    private val msgSendStructFn: Function =
        if (System.getProperty("os.arch")?.lowercase()?.contains("aarch64") == true) {
            msgSendFn
        } else {
            objc.getFunction("objc_msgSend_stret")
        }

    /**
     * Selectors and classes are process-wide and immortal once registered, so the
     * lookups are worth caching — [currentPositionMs] runs off a poll loop and
     * would otherwise hash a string every tick.
     */
    private val selectors = HashMap<String, Pointer>()
    private val classes = HashMap<String, Pointer>()

    /** Forces both libraries to load, so a failure surfaces at probe time. */
    fun load() {
        objc.name
        avFoundation.name
    }

    @Synchronized
    fun cls(name: String): Pointer = classes.getOrPut(name) {
        getClassFn.invokePointer(arrayOf<Any>(name))
            ?: error("Objective-C class $name not found")
    }

    @Synchronized
    fun sel(name: String): Pointer = selectors.getOrPut(name) {
        selFn.invokePointer(arrayOf<Any>(name))
            ?: error("selector $name could not be registered")
    }

    private fun args(target: Pointer, selector: String, extra: Array<out Any?>): Array<Any?> =
        Array(extra.size + 2) { i ->
            when (i) {
                0 -> target
                1 -> sel(selector)
                else -> extra[i - 2]
            }
        }

    fun send(target: Pointer, selector: String, vararg extra: Any?): Pointer? =
        msgSendFn.invokePointer(args(target, selector, extra))

    fun sendVoid(target: Pointer, selector: String, vararg extra: Any?) {
        msgSendFn.invokeVoid(args(target, selector, extra))
    }

    fun sendLong(target: Pointer, selector: String, vararg extra: Any?): Long =
        msgSendFn.invokeLong(args(target, selector, extra))

    fun sendBoolean(target: Pointer, selector: String, vararg extra: Any?): Boolean =
        sendLong(target, selector, *extra) != 0L

    fun sendFloat(target: Pointer, selector: String, vararg extra: Any?): Float =
        msgSendFn.invokeFloat(args(target, selector, extra))

    fun sendTime(target: Pointer, selector: String, vararg extra: Any?): CMTime =
        msgSendStructFn.invoke(CMTime::class.java, args(target, selector, extra)) as CMTime

    // ── Foundation conveniences ───────────────────────────────────────────────

    /**
     * An owned `NSString`. `alloc`/`init…` rather than the `stringWith…`
     * convenience so the result is not autoreleased — there is no pool running on
     * a JVM thread, so an autoreleased object would leak with no drain to reclaim
     * it. Callers must [release] it.
     */
    fun nsString(value: String): Pointer =
        send(send(cls("NSString"), "alloc")!!, "initWithUTF8String:", value)!!

    fun javaString(nsString: Pointer?): String? {
        if (nsString == null) return null
        return send(nsString, "UTF8String")?.getString(0)
    }

    fun retain(obj: Pointer): Pointer = send(obj, "retain")!!

    fun release(obj: Pointer?) {
        if (obj != null) sendVoid(obj, "release")
    }

    /**
     * Runs [block] inside an `NSAutoreleasePool`.
     *
     * AVFoundation's factory methods (`+playerItemWithURL:`, `+playerWithPlayerItem:`)
     * return autoreleased objects. A JVM thread has no ambient pool, so without one
     * of these they are never reclaimed. Anything that must outlive the block has to
     * be [retain]ed inside it.
     */
    inline fun <T> autoreleasing(block: () -> T): T {
        val pool = send(cls("NSAutoreleasePool"), "alloc")!!
        send(pool, "init")
        return try {
            block()
        } finally {
            sendVoid(pool, "drain")
        }
    }
}

/**
 * `CMTime`, laid out to match `CMTime.h`.
 *
 * A rational time — [value] over [timescale] — rather than a scalar, so that a
 * frame boundary is exactly representable. [flags] bit 0 is `kCMTimeFlags_Valid`;
 * an invalid or indefinite time (a live stream's duration, an unloaded item's) has
 * it clear or reports a zero [timescale], and both mean "no answer yet" here.
 */
internal class CMTime : Structure(), Structure.ByValue {

    @JvmField var value: Long = 0

    @JvmField var timescale: Int = 0

    @JvmField var flags: Int = 0

    @JvmField var epoch: Long = 0

    override fun getFieldOrder(): List<String> = FIELD_ORDER

    /** `NaN` when the time carries no usable answer, never an exception. */
    val seconds: Double
        get() = if (!isValid) Double.NaN else value.toDouble() / timescale

    val isValid: Boolean
        get() = flags and FLAG_VALID != 0 && timescale != 0

    /** Milliseconds, or `null` when the time is not a finite answer. */
    fun toMillisOrNull(): Long? {
        val s = seconds
        return if (s.isNaN() || s.isInfinite()) null else (s * 1000.0).toLong()
    }

    companion object {
        private val FIELD_ORDER = listOf("value", "timescale", "flags", "epoch")
        private const val FLAG_VALID = 1

        /**
         * A time in seconds at a fixed 600 timescale — the classic QuickTime base,
         * divisible by 24, 25, 30 and 60, so common frame rates land exactly on it.
         */
        fun fromSeconds(seconds: Double): CMTime = CMTime().apply {
            timescale = 600
            value = (seconds * timescale).toLong()
            flags = FLAG_VALID
        }

        fun fromMillis(millis: Long): CMTime = fromSeconds(millis / 1000.0)
    }
}
