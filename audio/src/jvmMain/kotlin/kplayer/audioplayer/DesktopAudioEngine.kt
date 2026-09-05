package kplayer.audioplayer

import kplayer.core.player.MediaEngine
import org.freedesktop.gstreamer.Gst

/**
 * Picks the desktop audio backend for the running OS.
 *
 * Compose Media Player's desktop support is not one player but three — Media
 * Foundation on Windows, GStreamer on Linux, AVFoundation on macOS — chosen at
 * runtime. This is that dispatcher, and the seam is what makes each one a drop-in:
 * a backend only has to satisfy [MediaEngine].
 *
 * **Today every OS gets [GStreamerAudioEngine].** `playbin` auto-plugs a platform
 * audio sink (`wasapisink`, `osxaudiosink`, `pulsesink`), so it genuinely works on all
 * three — it just requires the GStreamer natives to be installed. The
 * Media Foundation and AVFoundation specialisations, which would remove that
 * requirement, are **not written yet**; see the branches below for where they go.
 */
internal object DesktopAudioEngines {

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
     * Whether a desktop engine can actually be created — i.e. the GStreamer natives
     * are present. Checked before constructing a player so the failure is one clear
     * message instead of an `UnsatisfiedLinkError` from inside `play()`.
     */
    val isAvailable: Boolean by lazy {
        runCatching { Gst.init("kplayer") }.isSuccess
    }

    val unavailableReason: String
        get() = "GStreamer native libraries were not found. Install them with " + when (os) {
            Os.WINDOWS -> "the GStreamer MSI from gstreamer.freedesktop.org"
            Os.MAC -> "`brew install gstreamer gst-plugins-base gst-plugins-good`"
            Os.LINUX -> "`apt install libgstreamer1.0-0 gstreamer1.0-plugins-base gstreamer1.0-plugins-good`"
            Os.OTHER -> "your platform's GStreamer package"
        }

    fun create(): MediaEngine = when (os) {
        // TODO(desktop): a MediaFoundationAudioEngine over JNA would drop the
        //  GStreamer install requirement on Windows.
        Os.WINDOWS, -> GStreamerAudioEngine()

        // TODO(desktop): an AvFoundationAudioEngine (Rococoa / objc_msgSend) would
        //  drop it on macOS, and share its event mapping with :audio's AvAudioEngine.
        Os.MAC -> GStreamerAudioEngine()

        Os.LINUX, Os.OTHER -> GStreamerAudioEngine()
    }
}
