package kplayer.videoplayer

import kplayer.core.player.MediaEngine
import kplayer.videoplayer.mac.ObjC
import kplayer.videoplayer.win.MediaFoundation
import org.freedesktop.gstreamer.Gst

/**
 * Picks the desktop video backend for the running OS.
 *
 * The twin of `:audio`'s `DesktopAudioEngines`, with the opposite bias: audio can
 * lean on GStreamer everywhere because `playbin` auto-plugs a platform audio sink,
 * whereas video wants the OS's own stack so there is a native surface to render
 * into later. So each OS gets its own engine, and there is no fallback —
 * an OS without one is reported, not approximated.
 *
 * | OS | Engine | State |
 * |---|---|---|
 * | macOS | [AvFoundationVideoEngine] | verified on arm64 |
 * | Windows | [MediaFoundationVideoEngine] | **written but never executed** — `IMFMediaEngine`, frame-server mode |
 * | Linux | [GStreamerVideoEngine] | **written but never executed** |
 *
 * [isAvailable] is checked before a player is constructed, so a missing native
 * stack becomes one `PlaybackFeedback.Rejected` carrying [unavailableReason]
 * rather than an `UnsatisfiedLinkError` from inside `play()`.
 */
internal object DesktopVideoEngines {

    private enum class Os { WINDOWS, MAC, LINUX, OTHER }

    private val os: Os by lazy {
        val name = System.getProperty("os.name")?.lowercase().orEmpty()
        when {
            name.contains("win") -> Os.WINDOWS
            name.contains("mac") || name.contains("darwin") -> Os.MAC
            name.contains("nux") || name.contains("nix") -> Os.LINUX
            else -> Os.OTHER
        }
    }

    /**
     * Whether an engine can actually be created here.
     *
     * Probed by loading the native libraries, not by trusting `os.name`: a macOS
     * JVM always has AVFoundation, but a Windows N edition genuinely may not have
     * MFPlay, and finding that out at construction time is the difference between
     * a message and a crash.
     */
    val isAvailable: Boolean by lazy {
        when (os) {
            Os.MAC -> runCatching { ObjC.load() }.isSuccess
            Os.WINDOWS -> MediaFoundation.isAvailable
            // Gst.init is idempotent and is what :audio's probe uses, so a process
            // running both an audio and a video player initialises GStreamer once.
            Os.LINUX -> runCatching { Gst.init("kplayer") }.isSuccess
            Os.OTHER -> false
        }
    }

    val unavailableReason: String
        get() = when (os) {
            Os.MAC ->
                "AVFoundation could not be loaded through the Objective-C runtime."

            Os.WINDOWS ->
                "Media Foundation (mfplat.dll / ole32.dll / oleaut32.dll) was not found. On " +
                    "Windows N/KN editions, install the Media Feature Pack."

            Os.LINUX ->
                "GStreamer native libraries were not found. Install them with " +
                    "`apt install libgstreamer1.0-0 gstreamer1.0-plugins-base " +
                    "gstreamer1.0-plugins-good`."

            Os.OTHER ->
                "Desktop video is implemented for macOS, Windows and Linux only."
        }

    fun create(): MediaEngine = when (os) {
        Os.MAC -> AvFoundationVideoEngine()
        Os.WINDOWS -> MediaFoundationVideoEngine()
        Os.LINUX -> GStreamerVideoEngine()
        // Unreachable: isAvailable is false here, and the caller checks it before
        // constructing a player.
        Os.OTHER -> error(unavailableReason)
    }
}
