package kplayer.observers

import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import kplayer.observers.mac.CoreAudioOutput
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CoreAudio half, against this machine's real audio hardware.
 *
 * [OutputRouteTest] covers what a route change *means*; this covers whether the
 * bindings can read a route and register a listener at all — the part that fails
 * silently if a FourCC is wrong or a struct is the wrong size, because CoreAudio
 * returns a status code rather than throwing.
 *
 * It cannot test the interesting event: no test can unplug headphones. What it
 * can do is prove the observer starts, stops, and reports nothing while the route
 * is not changing — a listener that fired spuriously would pause playback for no
 * reason, which is worse than not detecting an unplug at all.
 *
 * **Skips on non-macOS.**
 */
class MacHardwareObserverTest {

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    private class RecordingHandler : PlaybackInterruptionHandler {
        val events: MutableList<InterruptionEvent> = Collections.synchronizedList(mutableListOf())
        override fun onEvent(event: InterruptionEvent) {
            events += event
        }
    }

    @Test
    fun `the current output route is readable`() {
        if (!isMac) return println("skipped: not macOS")

        val route = assertNotNull(CoreAudioOutput.currentRoute(), "no default output device")

        // A wrong selector or a mis-sized property address returns a non-zero
        // status and a zero value rather than failing, so zero is the tell.
        assertTrue(route.deviceId > 0, "implausible device id ${route.deviceId}")
        assertTrue(route.transportType != 0, "transport type came back empty")
    }

    @Test
    fun `the desktop factory selects the CoreAudio observer on macOS`() {
        if (!isMac) return println("skipped: not macOS")

        assertTrue(CoreAudioOutput.isAvailable, "CoreAudio should load on macOS")
        assertTrue(
            createHardwareObserver(RecordingHandler()) is MacHardwareObserver,
            "macOS should get the real observer rather than the no-op",
        )
    }

    @Test
    fun `starting and stopping registers and unregisters cleanly`() {
        if (!isMac) return println("skipped: not macOS")

        val handler = RecordingHandler()
        val observer = MacHardwareObserver(handler)

        observer.start()
        // Starting twice must not double-register: the second listener would
        // never be removed, and CoreAudio would keep calling a trampoline JNA had
        // already freed.
        observer.start()
        Thread.sleep(200)
        observer.stop()
        observer.stop()

        assertEquals(
            emptyList(),
            handler.events.toList(),
            "an unchanged route must produce no interruption",
        )
    }

    @Test
    fun `a stable route reports nothing over time`() {
        if (!isMac) return println("skipped: not macOS")

        val handler = RecordingHandler()
        val observer = MacHardwareObserver(handler)
        try {
            observer.start()
            Thread.sleep(500)
        } finally {
            observer.stop()
        }

        // Nobody touched the audio hardware during this test, so any event here
        // is the observer inventing one.
        assertTrue(
            handler.events.isEmpty(),
            "expected no events for an untouched route, got ${handler.events}",
        )
    }
}
