package kplayer.videoplayer.win

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString

/**
 * `IMFMediaEngine`, reached through JNA as a raw COM vtable, in **frame-server
 * mode** — the Media Engine decodes and reports frames but draws nothing itself,
 * which is what lets `MediaFoundationVideoEngine` hand pixels to `:ui` exactly as
 * `AvFoundationVideoEngine` and `GStreamerVideoEngine` do.
 *
 * > **Unverified — written on macOS and never executed.** The vtable indices below
 * > are the risk: COM dispatch is positional, so a wrong slot calls the wrong
 * > function rather than failing cleanly. Every index and GUID here was checked
 * > against the real `mfmediaengine.h` / `wincodec.h` headers (via the mingw-w64
 * > mirror) rather than recalled, which is more scrutiny than this file's MFPlay
 * > predecessor had — but "checked against a header" is not "run". `isAvailable`
 * > will not select this engine unless the DLLs load, but "loads" is not "works".
 * > See `video/README.md` § Desktop.
 *
 * ### Why `IMFMediaEngine` and not MFPlay
 *
 * This engine used to wrap MFPlay (`IMFPMediaPlayer`), which can be driven with a
 * **null callback** and polled through `GetState` — no synthesised COM vtable
 * needed. The cost was that MFPlay draws straight into an `HWND` and exposes no
 * frame API at all, so unlike macOS and Linux, Windows had no [VideoFrameSource]:
 * `:ui` had to embed a native window instead (`NativeWindowVideoSurface`, since
 * removed), which cannot be blurred, clipped or drawn under other Compose content.
 *
 * `IMFMediaEngine`'s **frame-server mode** is the fix, and it turns out to need
 * less native surface than feared:
 *  - `IMFMediaEngineClassFactory::CreateInstance` *requires* a callback
 *    ([MediaEngineNotify]) even in frame-server mode — there is no escaping the
 *    synthesised vtable MFPlay let this engine avoid. It is an `IMFMediaEngineNotify`
 *    sink and nothing else: every fact this engine needs is polled, exactly as it
 *    was against MFPlay, so `EventNotify` does nothing but return `S_OK`.
 *  - [transferVideoFrame] copies into a **WIC bitmap**, not a Direct3D surface.
 *    `TransferVideoFrame`'s destination can be "a DXGI surface or WIC bitmap", and
 *    `MF_MEDIA_ENGINE_DXGI_MANAGER` is documented as *optional* in frame-server
 *    mode — so this file never touches Direct3D, a device, or a swap chain. A WIC
 *    bitmap is plain CPU memory from the moment it is created, which is what
 *    lets [lockBitmapForReading] hand raw bytes straight to [kplayer.videoplayer.frame.FrameBuffer].
 */
internal object MediaFoundation {

    const val S_OK = 0
    private const val E_NOINTERFACE = 0x80004002.toInt()
    private const val CLSCTX_INPROC_SERVER = 0x1

    /** `MF_VERSION` for Windows 7 and later (`MF_SDK_VERSION << 16 | MF_API_VERSION`). */
    private const val MF_VERSION = 0x00020070

    /** `DXGI_FORMAT_B8G8R8A8_UNORM` — matches [kplayer.videoplayer.frame.VideoFrame]'s BGRA contract exactly. */
    private const val DXGI_FORMAT_B8G8R8A8_UNORM = 0x57

    private const val WIC_BITMAP_CACHE_ON_DEMAND = 0x1
    private const val WIC_BITMAP_LOCK_READ = 0x1

    // ── Libraries ───────────────────────────────────────────────────────────────

    private val ole32: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("ole32") }.getOrNull() }
    private val mfplat: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("mfplat") }.getOrNull() }
    private val oleaut32: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("oleaut32") }.getOrNull() }

    /**
     * `ole32`, `mfplat` and `oleaut32` resolve. Says nothing about whether
     * `CoCreateInstance(CLSID_MFMediaEngineClassFactory, …)` — resolved through COM
     * activation, not `GetProcAddress`, so there is no fourth library to probe here
     * — actually succeeds.
     */
    val isAvailable: Boolean
        get() = ole32 != null && mfplat != null && oleaut32 != null

    /** Per-thread COM + Media Foundation bring-up. Idempotent; safe to call every [MediaFoundationVideoEngine.prepare]. */
    fun initializeThread() {
        ole32?.getFunction("CoInitializeEx")?.invokeInt(arrayOf(Pointer.NULL, 2))
        mfplat?.getFunction("MFStartup")?.invokeInt(arrayOf(MF_VERSION, 0))
    }

    // ── GUIDs ───────────────────────────────────────────────────────────────────
    // Every value below was read out of the real mfmediaengine.h / wincodec.h
    // headers (via https://github.com/mingw-w64/mingw-w64), not recalled — see the
    // class doc. `guid()` lays out DEFINE_GUID(name, l, w1, w2, b0..b7)'s fields in
    // the order a REFGUID/REFIID parameter expects them in memory.

    private fun guid(data1: Long, data2: Int, data3: Int, vararg data4: Int): Memory {
        require(data4.size == 8)
        val memory = Memory(16)
        memory.setInt(0, data1.toInt())
        memory.setShort(4, data2.toShort())
        memory.setShort(6, data3.toShort())
        for (i in 0 until 8) memory.setByte(8L + i, data4[i].toByte())
        return memory
    }

    internal val IID_IUNKNOWN: Memory by lazy {
        guid(0x00000000, 0x0000, 0x0000, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46)
    }
    internal val IID_IMFMEDIAENGINENOTIFY: Memory by lazy {
        guid(0xfee7c112, 0xe776, 0x42b5, 0x9b, 0xbf, 0x00, 0x48, 0x52, 0x4e, 0x2b, 0xd5)
    }
    private val IID_IMFMEDIAENGINECLASSFACTORY: Memory by lazy {
        guid(0x4d645ace, 0x26aa, 0x4688, 0x9b, 0xe1, 0xdf, 0x35, 0x16, 0x99, 0x0b, 0x93)
    }
    private val CLSID_MFMEDIAENGINECLASSFACTORY: Memory by lazy {
        guid(0xb44392da, 0x499b, 0x446b, 0xa4, 0xcb, 0x00, 0x5f, 0xea, 0xd0, 0xe6, 0xd5)
    }
    private val MF_MEDIA_ENGINE_CALLBACK: Memory by lazy {
        guid(0xc60381b8, 0x83a4, 0x41f8, 0xa3, 0xd0, 0xde, 0x05, 0x07, 0x68, 0x49, 0xa9)
    }
    private val MF_MEDIA_ENGINE_VIDEO_OUTPUT_FORMAT: Memory by lazy {
        guid(0x5066893c, 0x8cf9, 0x42bc, 0x8b, 0x8a, 0x47, 0x22, 0x12, 0xe5, 0x27, 0x26)
    }
    private val CLSID_WICIMAGINGFACTORY: Memory by lazy {
        guid(0xcacaf262, 0x9370, 0x4615, 0xa1, 0x3b, 0x9f, 0x55, 0x39, 0xda, 0x4c, 0x0a)
    }
    private val IID_IWICIMAGINGFACTORY: Memory by lazy {
        guid(0xec5ec8a9, 0xc395, 0x4314, 0x9c, 0x77, 0x54, 0xd7, 0xa9, 0x35, 0xff, 0x70)
    }
    private val GUID_WICPIXELFORMAT_32BPPBGRA: Memory by lazy {
        guid(0x6fddc324, 0x4e03, 0x4bfe, 0xb1, 0x85, 0x3d, 0x77, 0x76, 0x8d, 0xc9, 0x0f)
    }

    /** Whether [riid] is one this engine's [MediaEngineNotify] sink answers to. */
    internal fun matchesIid(riid: Pointer, iid: Memory): Boolean {
        for (i in 0 until 16) {
            if (riid.getByte(i.toLong()) != iid.getByte(i.toLong())) return false
        }
        return true
    }

    internal const val QUERY_INTERFACE_S_OK = S_OK
    internal const val QUERY_INTERFACE_E_NOINTERFACE = E_NOINTERFACE

    // ── Raw vtable dispatch ───────────────────────────────────────────────────────
    // The same technique the MFPlay engine used: a COM interface pointer points at
    // a pointer to its vtable, `this` is always the first argument, and `null`
    // must reach JNA as `Pointer.NULL` rather than a typed Kotlin null — seeing
    // Any? here is why args is untyped, not an oversight.

    private fun function(self: Pointer, index: Int): Function {
        val vtable = self.getPointer(0)
        return Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE))
    }

    private fun withSelf(self: Pointer, args: Array<out Any?>): Array<Any?> =
        Array(args.size + 1) { i -> if (i == 0) self else args[i - 1] }

    private fun callHr(self: Pointer, index: Int, vararg args: Any?): Int =
        function(self, index).invokeInt(withSelf(self, args))

    private fun callInt(self: Pointer, index: Int, vararg args: Any?): Int = callHr(self, index, *args)

    private fun callDouble(self: Pointer, index: Int, vararg args: Any?): Double =
        function(self, index).invokeDouble(withSelf(self, args))

    /** `IUnknown::Release` is always slot 2, for any COM object. */
    private fun releaseUnknown(self: Pointer) {
        callInt(self, 2)
    }

    // ── COM / Media Foundation bootstrap ─────────────────────────────────────────

    private fun coCreateInstance(clsid: Memory, iid: Memory): Pointer? {
        val library = ole32 ?: return null
        val out = Memory(Native.POINTER_SIZE.toLong())
        val hr = library.getFunction("CoCreateInstance").invokeInt(
            arrayOf(clsid, Pointer.NULL, CLSCTX_INPROC_SERVER, iid, out),
        )
        return if (hr == S_OK) out.getPointer(0) else null
    }

    private fun createAttributes(initialSize: Int): Pointer? {
        val library = mfplat ?: return null
        val out = Memory(Native.POINTER_SIZE.toLong())
        val hr = library.getFunction("MFCreateAttributes").invokeInt(arrayOf(out, initialSize))
        return if (hr == S_OK) out.getPointer(0) else null
    }

    private fun sysAllocString(value: String): Pointer? =
        oleaut32?.getFunction("SysAllocString")?.invokePointer(arrayOf(WString(value)))

    private fun sysFreeString(bstr: Pointer) {
        oleaut32?.getFunction("SysFreeString")?.invoke(Void.TYPE, arrayOf(bstr))
    }

    // IMFAttributes vtable slots (after the 3 IUnknown entries) — see
    // `mfobjects.h`'s `IMFAttributes` declaration order.
    private object AttrV {
        const val SET_UINT32 = 21
        const val SET_UNKNOWN = 27
    }

    // IMFMediaEngineClassFactory vtable slots.
    private object FactoryV {
        const val CREATE_INSTANCE = 3
    }

    /**
     * Builds a frame-server-mode `IMFMediaEngine`: no `MF_MEDIA_ENGINE_PLAYBACK_HWND`
     * / `_VISUAL` attribute is set, so the engine renders nothing itself and every
     * frame must be pulled with [transferVideoFrame].
     *
     * @param notify the sink `MF_MEDIA_ENGINE_CALLBACK` requires even here — see the
     *   class doc. Must outlive the returned engine.
     */
    fun createMediaEngine(notify: MediaEngineNotify): Pointer? {
        val factory = coCreateInstance(CLSID_MFMEDIAENGINECLASSFACTORY, IID_IMFMEDIAENGINECLASSFACTORY)
            ?: return null
        try {
            val attributes = createAttributes(2) ?: return null
            try {
                if (!callHr(attributes, AttrV.SET_UNKNOWN, MF_MEDIA_ENGINE_CALLBACK, notify.nativeThis)
                        .let { it == S_OK }
                ) {
                    return null
                }
                if (callHr(
                        attributes,
                        AttrV.SET_UINT32,
                        MF_MEDIA_ENGINE_VIDEO_OUTPUT_FORMAT,
                        DXGI_FORMAT_B8G8R8A8_UNORM,
                    ) != S_OK
                ) {
                    return null
                }

                val out = Memory(Native.POINTER_SIZE.toLong())
                val flags = 0 // neither MF_MEDIA_ENGINE_AUDIOONLY nor _WAITFORSTABLE_STATE
                return if (callHr(factory, FactoryV.CREATE_INSTANCE, flags, attributes, out) == S_OK) {
                    out.getPointer(0)
                } else {
                    null
                }
            } finally {
                releaseUnknown(attributes)
            }
        } finally {
            releaseUnknown(factory)
        }
    }

    // IMFMediaEngine vtable slots (after the 3 IUnknown entries) — see the class
    // doc for how these were checked.
    private object EngineV {
        const val GET_ERROR = 3
        const val SET_SOURCE = 6
        const val LOAD = 12
        const val GET_READY_STATE = 14
        const val GET_CURRENT_TIME = 16
        const val SET_CURRENT_TIME = 17
        const val GET_DURATION = 19
        const val IS_PAUSED = 20
        const val SET_PLAYBACK_RATE = 24
        const val IS_ENDED = 27
        const val PLAY = 32
        const val PAUSE = 33
        const val SET_VOLUME = 37
        const val HAS_VIDEO = 38
        const val GET_NATIVE_VIDEO_SIZE = 40
        const val SHUTDOWN = 42
        const val TRANSFER_VIDEO_FRAME = 43
        const val ON_VIDEO_STREAM_TICK = 44
    }

    /** `MF_MEDIA_ENGINE_READY_HAVE_METADATA` — durations and native size become readable at this point. */
    const val READY_HAVE_METADATA = 1

    /** `MF_MEDIA_ENGINE_READY_HAVE_FUTURE_DATA` — enough is buffered that playback need not stall. */
    const val READY_HAVE_FUTURE_DATA = 3

    fun setSource(engine: Pointer, url: String): Boolean {
        val bstr = sysAllocString(url) ?: return false
        // [in] BSTR is borrowed for the call's duration under COM convention — a
        // callee that needs to keep the URL copies it, so freeing here is safe.
        return try {
            callHr(engine, EngineV.SET_SOURCE, bstr) == S_OK
        } finally {
            sysFreeString(bstr)
        }
    }

    /** Begins the asynchronous load `setSource` only registered the URL for. */
    fun load(engine: Pointer): Boolean = callHr(engine, EngineV.LOAD) == S_OK

    fun play(engine: Pointer): Boolean = callHr(engine, EngineV.PLAY) == S_OK

    fun pause(engine: Pointer): Boolean = callHr(engine, EngineV.PAUSE) == S_OK

    fun setCurrentTimeSeconds(engine: Pointer, seconds: Double): Boolean =
        callHr(engine, EngineV.SET_CURRENT_TIME, seconds) == S_OK

    fun currentTimeSeconds(engine: Pointer): Double = callDouble(engine, EngineV.GET_CURRENT_TIME)

    /** `NaN` when no data is available yet; `+Inf` for an unbounded (live) resource. */
    fun durationSeconds(engine: Pointer): Double = callDouble(engine, EngineV.GET_DURATION)

    fun setPlaybackRate(engine: Pointer, rate: Double): Boolean =
        callHr(engine, EngineV.SET_PLAYBACK_RATE, rate) == S_OK

    fun setVolume(engine: Pointer, volume: Double): Boolean =
        callHr(engine, EngineV.SET_VOLUME, volume) == S_OK

    fun hasVideo(engine: Pointer): Boolean = callInt(engine, EngineV.HAS_VIDEO) != 0

    fun isPaused(engine: Pointer): Boolean = callInt(engine, EngineV.IS_PAUSED) != 0

    fun isEnded(engine: Pointer): Boolean = callInt(engine, EngineV.IS_ENDED) != 0

    /** An `MF_MEDIA_ENGINE_READY` value — compare against [READY_HAVE_METADATA] / [READY_HAVE_FUTURE_DATA]. */
    fun readyState(engine: Pointer): Int = callInt(engine, EngineV.GET_READY_STATE)

    /** `null` before metadata loads, or for an audio-only source. */
    fun nativeVideoSize(engine: Pointer): IntArray? {
        val width = Memory(4)
        val height = Memory(4)
        if (callHr(engine, EngineV.GET_NATIVE_VIDEO_SIZE, width, height) != S_OK) return null
        val w = width.getInt(0)
        val h = height.getInt(0)
        return if (w > 0 && h > 0) intArrayOf(w, h) else null
    }

    /**
     * `OnVideoStreamTick` — `true` when a frame newer than the one already taken
     * is ready. Consumes that "new" signal, unlike [transferVideoFrame], which
     * can be called at any time regardless: see [kplayer.videoplayer.frame.PixelSource].
     */
    fun onVideoStreamTick(engine: Pointer): Boolean {
        val pts = Memory(8)
        return callHr(engine, EngineV.ON_VIDEO_STREAM_TICK, pts) == S_OK
    }

    /**
     * Blits the current frame into [destination] — a WIC bitmap — scaled to
     * `(0,0)-(width,height)` with no letterboxing, since [destination] is always
     * created at the source's own native size.
     */
    fun transferVideoFrame(engine: Pointer, destination: Pointer, width: Int, height: Int): Boolean {
        // RECT{left,top,right,bottom}, zeroed then right/bottom set — the full
        // destination bitmap, no offset.
        val dstRect = Memory(16).apply {
            clear()
            setInt(8, width)
            setInt(12, height)
        }
        // MFARGB{B,G,R,A}: opaque black, though nothing should ever show it since
        // the destination rect matches the native size exactly.
        val borderColor = Memory(4).apply {
            setByte(0, 0)
            setByte(1, 0)
            setByte(2, 0)
            setByte(3, 0xFF.toByte())
        }
        return callHr(engine, EngineV.TRANSFER_VIDEO_FRAME, destination, Pointer.NULL, dstRect, borderColor) == S_OK
    }

    /** `MF_MEDIA_ENGINE_ERR_NOERROR` (0) when nothing has failed. */
    fun errorCode(engine: Pointer): Int {
        val out = Memory(Native.POINTER_SIZE.toLong())
        if (callHr(engine, EngineV.GET_ERROR, out) != S_OK) return 0
        val error = out.getPointer(0) ?: return 0
        return try {
            // IMFMediaError::GetErrorCode, slot 3 — its own tiny vtable, not worth a
            // named object for the one call this engine makes on it.
            callInt(error, 3)
        } finally {
            releaseUnknown(error)
        }
    }

    fun shutdown(engine: Pointer) {
        callHr(engine, EngineV.SHUTDOWN)
        releaseUnknown(engine)
    }

    // ── WIC: the frame-server destination surface ────────────────────────────────

    private object WicFactoryV {
        const val CREATE_BITMAP = 17
    }

    private object WicBitmapV {
        // IWICBitmap extends IWICBitmapSource (5 own methods), so its own slots
        // start at 3 + 5 = 8.
        const val LOCK = 8
    }

    private object WicLockV {
        const val GET_STRIDE = 4
        const val GET_DATA_POINTER = 5
    }

    fun createWicFactory(): Pointer? = coCreateInstance(CLSID_WICIMAGINGFACTORY, IID_IWICIMAGINGFACTORY)

    fun createBitmap(factory: Pointer, width: Int, height: Int): Pointer? {
        val out = Memory(Native.POINTER_SIZE.toLong())
        val hr = callHr(
            factory,
            WicFactoryV.CREATE_BITMAP,
            width,
            height,
            GUID_WICPIXELFORMAT_32BPPBGRA,
            WIC_BITMAP_CACHE_ON_DEMAND,
            out,
        )
        return if (hr == S_OK) out.getPointer(0) else null
    }

    /** Locks the whole bitmap for reading. Release with [releaseUnknown] once the bytes are copied out. */
    fun lockBitmapForReading(bitmap: Pointer): Pointer? {
        val out = Memory(Native.POINTER_SIZE.toLong())
        // A null WICRect locks the entire bitmap.
        val hr = callHr(bitmap, WicBitmapV.LOCK, Pointer.NULL, WIC_BITMAP_LOCK_READ, out)
        return if (hr == S_OK) out.getPointer(0) else null
    }

    fun lockStride(lock: Pointer): Int {
        val out = Memory(4)
        return if (callHr(lock, WicLockV.GET_STRIDE, out) == S_OK) out.getInt(0) else 0
    }

    /** The locked pixels, and how many bytes are readable from them. */
    fun lockDataPointer(lock: Pointer): Pair<Pointer, Int>? {
        val size = Memory(4)
        val dataOut = Memory(Native.POINTER_SIZE.toLong())
        if (callHr(lock, WicLockV.GET_DATA_POINTER, size, dataOut) != S_OK) return null
        val data = dataOut.getPointer(0) ?: return null
        return data to size.getInt(0)
    }

    /** Releases any COM object by its `IUnknown` slot — a bitmap, a lock, an engine already shut down aside. */
    fun release(comObject: Pointer) = releaseUnknown(comObject)
}
