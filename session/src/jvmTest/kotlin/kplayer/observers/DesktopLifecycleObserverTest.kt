package kplayer.observers

import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop "backgrounded" rule: **no window of this app is active**.
 *
 * Driven through the observer's own bookkeeping rather than through real AWT
 * events, because activating a window programmatically depends on the desktop
 * environment's cooperation and is flaky on every OS — and the interesting
 * behaviour is the debounce, which is pure timing.
 *
 * Runs everywhere; nothing here needs a display.
 */
class DesktopLifecycleObserverTest {

    private class RecordingHandler : PlaybackInterruptionHandler {
        val events: MutableList<InterruptionEvent> = Collections.synchronizedList(mutableListOf())
        override fun onEvent(event: InterruptionEvent) {
            events += event
        }

        fun snapshot(): List<InterruptionEvent> = synchronized(events) { events.toList() }
    }

    private val backgrounded = InterruptionEvent.Began(InterruptionCause.AppBackgrounded)
    private val foregrounded = InterruptionEvent.Ended(InterruptionCause.AppBackgrounded)

    /** Short enough to keep the suite quick, long enough to out-wait a coalesced pair. */
    private val settleMs = 60L

    private fun withObserver(body: (DesktopLifecycleObserver, RecordingHandler) -> Unit) {
        val handler = RecordingHandler()
        val observer = DesktopLifecycleObserver(handler, settleDelayMs = settleMs)
        try {
            body(observer, handler)
        } finally {
            observer.stop()
        }
    }

    /** Waits past the settle window so the decision has certainly been made. */
    private fun awaitSettle() = Thread.sleep(settleMs * 4)

    @Test
    fun `losing the last window reports backgrounded`() = withObserver { observer, handler ->
        val window = Any()
        observer.onWindowActivated(window)
        awaitSettle()
        handler.events.clear()

        observer.onWindowDeactivated(window)
        awaitSettle()

        assertEquals(listOf(backgrounded), handler.snapshot())
    }

    @Test
    fun `regaining a window reports foregrounded`() = withObserver { observer, handler ->
        val window = Any()
        observer.onWindowActivated(window)
        observer.onWindowDeactivated(window)
        awaitSettle()
        handler.events.clear()

        observer.onWindowActivated(window)
        awaitSettle()

        assertEquals(listOf(foregrounded), handler.snapshot())
    }

    /**
     * The reason the debounce exists. Moving between two of the app's own windows
     * fires `WINDOW_DEACTIVATED` for the first and `WINDOW_ACTIVATED` for the
     * second with a gap in between, so the naive reading is a background followed
     * immediately by a foreground — and every such pair would push an
     * interruption through the policy engine and, under a pausing policy, stutter
     * playback.
     */
    @Test
    fun `switching between two of the app's own windows reports nothing`() =
        withObserver { observer, handler ->
            val first = Any()
            val second = Any()
            observer.onWindowActivated(first)
            awaitSettle()
            handler.events.clear()

            // Deactivate then activate, with no wait between: exactly what AWT
            // emits when the user clicks the app's second window.
            observer.onWindowDeactivated(first)
            observer.onWindowActivated(second)
            awaitSettle()

            assertTrue(
                handler.snapshot().isEmpty(),
                "an internal window switch is not a background: got ${handler.snapshot()}",
            )
        }

    @Test
    fun `the reverse ordering is also coalesced`() = withObserver { observer, handler ->
        val first = Any()
        val second = Any()
        observer.onWindowActivated(first)
        awaitSettle()
        handler.events.clear()

        // AWT gives no ordering guarantee between the two windows' events, so the
        // activate-then-deactivate order has to be just as quiet.
        observer.onWindowActivated(second)
        observer.onWindowDeactivated(first)
        awaitSettle()

        assertTrue(handler.snapshot().isEmpty(), "got ${handler.snapshot()}")
    }

    @Test
    fun `repeated deactivations report backgrounded only once`() = withObserver { observer, handler ->
        val window = Any()
        observer.onWindowActivated(window)
        awaitSettle()
        handler.events.clear()

        observer.onWindowDeactivated(window)
        awaitSettle()
        observer.onWindowDeactivated(window)
        awaitSettle()

        // A second Began would stack in the handler's active set and need a second
        // Ended to clear, blocking auto-resume for good.
        assertEquals(listOf(backgrounded), handler.snapshot())
    }

    @Test
    fun `a full round trip produces one matched pair`() = withObserver { observer, handler ->
        val window = Any()
        observer.onWindowActivated(window)
        awaitSettle()
        handler.events.clear()

        observer.onWindowDeactivated(window)
        awaitSettle()
        observer.onWindowActivated(window)
        awaitSettle()

        assertEquals(listOf(backgrounded, foregrounded), handler.snapshot())
    }

    @Test
    fun `stopping silences the observer`() {
        val handler = RecordingHandler()
        val observer = DesktopLifecycleObserver(handler, settleDelayMs = settleMs)
        val window = Any()
        observer.onWindowActivated(window)
        awaitSettle()
        handler.events.clear()

        observer.stop()
        observer.onWindowDeactivated(window)
        awaitSettle()

        assertTrue(
            handler.snapshot().isEmpty(),
            "a stopped observer must not report: got ${handler.snapshot()}",
        )
    }
}
