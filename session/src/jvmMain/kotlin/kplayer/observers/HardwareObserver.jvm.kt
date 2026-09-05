package kplayer.observers

import com.sun.jna.Pointer
import kplayer.interruption.PlaybackInterruptionHandler
import kplayer.observers.mac.CoreAudioOutput

/**
 * Desktop headphone detection: macOS through CoreAudio, nothing elsewhere yet.
 *
 * Windows would be `IMMNotificationClient` (a COM callback object) and Linux
 * PulseAudio/PipeWire sink events; neither is written, and both report as "no
 * observer" rather than pretending. A player on those platforms simply never
 * sees [kplayer.interruption.InterruptionCause.HeadphonesDisconnected], which is
 * the same position every desktop was in before this.
 */
actual fun createHardwareObserver(handler: PlaybackInterruptionHandler): HardwareObserver =
    if (isMac && CoreAudioOutput.isAvailable) {
        MacHardwareObserver(handler)
    } else {
        NoHardwareObserver
    }

private val isMac: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().let {
        it.contains("mac") || it.contains("darwin")
    }

private object NoHardwareObserver : HardwareObserver {
    override fun start() = Unit
    override fun stop() = Unit
}

/**
 * Watches the macOS default output route and reports the two changes that matter.
 *
 * The classification lives in [outputRouteInterruption], which is pure and
 * tested; this class is only the CoreAudio plumbing plus the "what was it
 * before?" bookkeeping that a diff needs.
 */
internal class MacHardwareObserver(
    private val handler: PlaybackInterruptionHandler,
) : HardwareObserver {

    /**
     * The last route we reported against. `null` until [start] reads one.
     *
     * `@Volatile` because CoreAudio invokes the listener on its own thread, not
     * the one that called [start].
     */
    @Volatile
    private var route: OutputRoute? = null

    private var registered: List<Pair<Int, Int>> = emptyList()

    /**
     * Held in a field, not passed inline, and this is load-bearing: JNA frees the
     * native trampoline behind a [CoreAudioOutput.PropertyListener] when the
     * Kotlin object becomes unreachable, and CoreAudio calling a freed trampoline
     * takes the whole process down. It has to outlive every registration.
     */
    private val listener = CoreAudioOutput.PropertyListener { _, _, _: Pointer?, _: Pointer? ->
        onRouteChanged()
        0 // noErr
    }

    override fun start() {
        if (registered.isNotEmpty()) return
        val initial = CoreAudioOutput.currentRoute()
        route = initial
        registered = CoreAudioOutput.addListener(listener, initial?.deviceId)
    }

    override fun stop() {
        CoreAudioOutput.removeListener(listener, registered)
        registered = emptyList()
        route = null
    }

    /**
     * Called on CoreAudio's thread, possibly several times for one physical
     * event — a single unplug can fire the device listener and the data-source
     * listener. Diffing against the stored route rather than reacting to the
     * notification itself is what collapses those into one interruption.
     */
    private fun onRouteChanged() {
        // No answer this time (CoreAudio mid-reconfiguration) is not a change:
        // keeping the previous route avoids inventing an unplug that never
        // happened, at the cost of catching it on the next notification.
        val current = CoreAudioOutput.currentRoute() ?: return
        val previous = route ?: run { route = current; return }
        if (previous == current) return
        route = current

        // Re-register on the new device so the 3.5mm jack on *it* is watched too;
        // the data-source listener was bound to the device that just went away.
        if (previous.deviceId != current.deviceId) {
            CoreAudioOutput.removeListener(listener, registered)
            registered = CoreAudioOutput.addListener(listener, current.deviceId)
        }

        outputRouteInterruption(previous, current)?.let(handler::onEvent)
    }
}
