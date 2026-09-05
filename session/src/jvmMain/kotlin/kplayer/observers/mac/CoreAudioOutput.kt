package kplayer.observers.mac

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kplayer.observers.OutputRoute

/**
 * The slice of CoreAudio needed to watch the default output device.
 *
 * Plain C functions, so JNA calls them directly — no Objective-C runtime, and in
 * particular **no blocks**. That matters: `NSNotificationCenter` would be the
 * obvious way to observe on macOS, but its block-based API is unreachable from
 * JNA, which can neither define an Objective-C class nor synthesise a block.
 * CoreAudio's listener takes an ordinary C function pointer instead, which is
 * exactly what a JNA [Callback] compiles to — so this is not a workaround, it is
 * the one observation API on macOS that JNA can actually use.
 */
internal object CoreAudioOutput {

    private val library: NativeLibrary? by lazy {
        runCatching {
            NativeLibrary.getInstance("/System/Library/Frameworks/CoreAudio.framework/CoreAudio")
        }.getOrNull()
    }

    val isAvailable: Boolean get() = library != null

    /** `kAudioObjectSystemObject`. */
    private const val SYSTEM_OBJECT = 1

    // FourCC selectors, spelled as their integer values so no runtime packing is
    // needed. The comment beside each is the literal CoreAudio writes.
    private const val DEFAULT_OUTPUT_DEVICE = 0x644F7574 // 'dOut'
    private const val TRANSPORT_TYPE = 0x7472616E        // 'tran'
    private const val DATA_SOURCE = 0x73737263           // 'ssrc'
    private const val SCOPE_GLOBAL = 0x676C6F62          // 'glob'
    private const val SCOPE_OUTPUT = 0x6F757470          // 'outp'

    /**
     * `AudioObjectPropertyListenerProc`.
     *
     * A JNA [Callback] is a real C function pointer, which is why this works at
     * all. It must be kept strongly reachable for as long as CoreAudio holds it —
     * JNA frees the native trampoline when the Kotlin object is collected, and a
     * callback invoked after that crashes the process rather than throwing. The
     * observer holds the reference in a field for precisely this reason.
     */
    fun interface PropertyListener : Callback {
        fun invoke(objectId: Int, addressCount: Int, addresses: Pointer?, clientData: Pointer?): Int
    }

    /** `AudioObjectPropertyAddress { UInt32 selector; UInt32 scope; UInt32 element; }`. */
    private fun address(selector: Int, scope: Int): Memory = Memory(12).apply {
        setInt(0L, selector)
        setInt(4L, scope)
        setInt(8L, 0) // kAudioObjectPropertyElementMain
    }

    /** @return the property's `UInt32` value, or `null` if the object has no such property. */
    private fun readInt(objectId: Int, selector: Int, scope: Int): Int? {
        val target = library ?: return null
        val size = Memory(4).apply { setInt(0L, 4) }
        val out = Memory(4).apply { clear() }
        val status = target.getFunction("AudioObjectGetPropertyData").invokeInt(
            arrayOf(objectId, address(selector, scope), 0, Pointer.NULL, size, out),
        )
        return if (status == 0) out.getInt(0) else null
    }

    private fun hasProperty(objectId: Int, selector: Int, scope: Int): Boolean {
        val target = library ?: return false
        return target.getFunction("AudioObjectHasProperty")
            .invokeInt(arrayOf(objectId, address(selector, scope))) != 0
    }

    /**
     * Reads the whole current route in one go.
     *
     * @return `null` when there is no default output at all — a machine with no
     *   audio hardware, or CoreAudio mid-reconfiguration. Callers treat that as
     *   "no answer this time" and keep the previous route rather than inventing a
     *   change.
     */
    fun currentRoute(): OutputRoute? {
        val deviceId = readInt(SYSTEM_OBJECT, DEFAULT_OUTPUT_DEVICE, SCOPE_GLOBAL) ?: return null
        val transport = readInt(deviceId, TRANSPORT_TYPE, SCOPE_GLOBAL) ?: return null
        // Absent on most external devices — only the built-in output distinguishes
        // speaker from headphone jack this way — so its absence is normal, not a
        // failure.
        val dataSource = if (hasProperty(deviceId, DATA_SOURCE, SCOPE_OUTPUT)) {
            readInt(deviceId, DATA_SOURCE, SCOPE_OUTPUT)
        } else {
            null
        }
        return OutputRoute(deviceId, transport, dataSource)
    }

    /**
     * Registers [listener] for both signals that can change the route.
     *
     * Two registrations, because one does not cover the other: the *system*
     * object reports a change of default device (AirPods, USB, HDMI), while the
     * *device* reports a change of data source (the 3.5mm jack, where the device
     * itself never changes).
     *
     * @return the addresses that were successfully registered, to be handed back
     *   to [removeListener]. Empty if nothing could be registered.
     */
    fun addListener(listener: PropertyListener, deviceId: Int?): List<Pair<Int, Int>> {
        val target = library ?: return emptyList()
        val registered = mutableListOf<Pair<Int, Int>>()

        fun register(objectId: Int, selector: Int, scope: Int) {
            val status = target.getFunction("AudioObjectAddPropertyListener")
                .invokeInt(arrayOf(objectId, address(selector, scope), listener, Pointer.NULL))
            if (status == 0) registered += objectId to selector
        }

        register(SYSTEM_OBJECT, DEFAULT_OUTPUT_DEVICE, SCOPE_GLOBAL)
        if (deviceId != null && hasProperty(deviceId, DATA_SOURCE, SCOPE_OUTPUT)) {
            register(deviceId, DATA_SOURCE, SCOPE_OUTPUT)
        }
        return registered
    }

    fun removeListener(listener: PropertyListener, registered: List<Pair<Int, Int>>) {
        val target = library ?: return
        registered.forEach { (objectId, selector) ->
            val scope = if (selector == DATA_SOURCE) SCOPE_OUTPUT else SCOPE_GLOBAL
            target.getFunction("AudioObjectRemovePropertyListener")
                .invokeInt(arrayOf(objectId, address(selector, scope), listener, Pointer.NULL))
        }
    }
}
