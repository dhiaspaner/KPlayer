package kplayer.videoplayer.win

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * A synthesised `IMFMediaEngineNotify` COM object — the one piece of native
 * callback surface this engine cannot avoid, because
 * `IMFMediaEngineClassFactory::CreateInstance` rejects `MF_MEDIA_ENGINE_CALLBACK`
 * being absent even in frame-server mode. See [MediaFoundation]'s class doc.
 *
 * `EventNotify` does nothing but return `S_OK`: `MediaFoundationVideoEngine` polls
 * every fact it needs (ready state, paused, ended, error, frame availability)
 * instead of reacting to events, exactly as it did against MFPlay. Building this
 * sink is unavoidable, but *using* it is not — keeping it inert is what keeps the
 * engine's actual logic in one place, poll-driven, rather than split across a
 * poll loop and an event handler that can each observe a different truth.
 *
 * ### The vtable
 *
 * JNA has no way to *implement* a COM interface — it can only call one. This
 * builds the vtable a COM caller expects by hand: a [Structure] whose fields are
 * [Callback]s (which JNA converts to native function pointers when the structure
 * is written to memory) laid out in `IMFMediaEngineNotify`'s declared order —
 * `QueryInterface`, `AddRef`, `Release`, then `EventNotify` — and [nativeThis] is a
 * pointer-to-pointer-to-that-vtable, which is exactly what a COM interface pointer
 * *is*. Native code dereferences [nativeThis] to find the vtable, then calls
 * whichever slot it wants with [nativeThis] as the first (`this`) argument.
 *
 * Every [Callback] and the [Structure] behind them are held as instance fields, not
 * locals — a callback JNA can no longer find a Java reference to is free to be
 * collected, and a GC'd callback trampoline is a jump into freed memory the moment
 * native code calls it.
 *
 * `AddRef`/`Release` both return a constant `1` rather than tracking a real count.
 * This object's lifetime is owned by the engine that holds it, not by COM
 * refcounting — [MediaFoundationVideoEngine] releases the underlying
 * `IMFMediaEngine` in `release()`, and this sink becomes eligible for GC once
 * nothing references it after that. A fixed non-zero count is the standard
 * simplification for a sink with no independent lifetime of its own.
 */
internal class MediaEngineNotify {

    private fun interface QueryInterfaceCallback : Callback {
        fun invoke(self: Pointer, riid: Pointer, ppv: Pointer): Int
    }

    private fun interface AddRefCallback : Callback {
        fun invoke(self: Pointer): Int
    }

    private fun interface ReleaseCallback : Callback {
        fun invoke(self: Pointer): Int
    }

    private fun interface EventNotifyCallback : Callback {
        fun invoke(self: Pointer, event: Int, param1: Long, param2: Int): Int
    }

    /** `IMFMediaEngineNotify`'s vtable, in its header's declaration order. */
    private class Vtbl : Structure() {
        @JvmField var queryInterface: QueryInterfaceCallback? = null
        @JvmField var addRef: AddRefCallback? = null
        @JvmField var release: ReleaseCallback? = null
        @JvmField var eventNotify: EventNotifyCallback? = null

        override fun getFieldOrder(): List<String> = FIELD_ORDER

        companion object {
            private val FIELD_ORDER = listOf("queryInterface", "addRef", "release", "eventNotify")
        }
    }

    private val queryInterfaceCallback = QueryInterfaceCallback { self, riid, ppv ->
        val known = MediaFoundation.matchesIid(riid, MediaFoundation.IID_IUNKNOWN) ||
            MediaFoundation.matchesIid(riid, MediaFoundation.IID_IMFMEDIAENGINENOTIFY)
        if (known) {
            ppv.setPointer(0, self)
            MediaFoundation.QUERY_INTERFACE_S_OK
        } else {
            ppv.setPointer(0, Pointer.NULL)
            MediaFoundation.QUERY_INTERFACE_E_NOINTERFACE
        }
    }

    private val addRefCallback = AddRefCallback { 1 }

    private val releaseCallback = ReleaseCallback { 1 }

    private val eventNotifyCallback = EventNotifyCallback { _, _, _, _ -> MediaFoundation.QUERY_INTERFACE_S_OK }

    private val vtbl = Vtbl().apply {
        queryInterface = queryInterfaceCallback
        addRef = addRefCallback
        release = releaseCallback
        eventNotify = eventNotifyCallback
        write()
    }

    /**
     * The `IMFMediaEngineNotify*` to hand to `MF_MEDIA_ENGINE_CALLBACK` — a pointer
     * to a pointer to [vtbl]'s native memory, matching a COM interface pointer's
     * layout exactly.
     */
    val nativeThis: Memory = Memory(Native.POINTER_SIZE.toLong()).apply {
        setPointer(0, vtbl.pointer)
    }
}
