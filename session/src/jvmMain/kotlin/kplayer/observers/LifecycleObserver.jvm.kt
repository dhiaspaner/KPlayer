package kplayer.observers

import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop app lifecycle, from AWT rather than from any OS API.
 *
 * "Backgrounded" means something different on a desktop than on a phone: the
 * process keeps running and keeps its audio, so the honest equivalent is **no
 * window of this app is active** — the user has switched to something else.
 * `WINDOW_ACTIVATED` / `WINDOW_DEACTIVATED` say exactly that, on every desktop
 * OS, with no native code at all.
 *
 * Deliberately *not* `NSApplicationDidResignActiveNotification`, even on macOS.
 * It carries the same meaning, but reaching it from JNA is impossible —
 * `NSNotificationCenter`'s block API cannot be synthesised — and it would work on
 * one OS out of three. AWT is both more portable and less code.
 *
 * Note what this does **not** do: it never pauses anything. It reports the event,
 * and `DefaultPlaybackInterruptionHandler` decides against `backgroundPolicy` —
 * whose default, `KeepState`, keeps playing. A desktop media player that stopped
 * every time you changed window would be broken, and the policy layer is what
 * prevents that.
 */
actual fun createLifecycleObserver(handler: PlaybackInterruptionHandler): LifecycleObserver =
    DesktopLifecycleObserver(handler)

internal class DesktopLifecycleObserver(
    private val handler: PlaybackInterruptionHandler,
    /**
     * How long the app must stay window-less before it counts as backgrounded.
     *
     * Not a nicety. Moving between two of the app's own windows fires
     * `WINDOW_DEACTIVATED` for the first and `WINDOW_ACTIVATED` for the second,
     * with no ordering guarantee and a gap in between — so the naive reading is
     * that the app was backgrounded and immediately foregrounded again. Every
     * such pair would push an interruption through the policy engine and, under a
     * pausing policy, visibly stutter playback. Waiting for the dust to settle
     * costs a fifth of a second of latency on a real switch away.
     */
    private val settleDelayMs: Long = 200L,
) : LifecycleObserver {

    /** Windows currently activated. Touched only on the AWT event thread. */
    private val activeWindows = mutableSetOf<Any>()

    /** What we last told the handler, so transitions are reported once each. */
    private var reportedBackgrounded = false

    private var pending: ScheduledFuture<*>? = null

    /**
     * Set by [stop], and the reason [scheduleSettle] can be called after teardown
     * at all: removing the AWT listener does not recall events already queued for
     * dispatch, and `WINDOW_CLOSED` arrives during app shutdown — precisely when
     * this observer has just been stopped. Without the guard those late events
     * hit a shut-down executor and throw `RejectedExecutionException` on the AWT
     * event thread, which is not a place an exception should ever come from.
     */
    @Volatile
    private var stopped = false

    private val settleTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kplayer-lifecycle-settle").apply { isDaemon = true }
    }

    private val listener = AWTEventListener { event ->
        if (event !is WindowEvent) return@AWTEventListener
        when (event.id) {
            WindowEvent.WINDOW_ACTIVATED -> onWindowActivated(event.window)

            WindowEvent.WINDOW_DEACTIVATED,
            // A window that goes away without deactivating first would otherwise
            // leave the app looking permanently foregrounded.
            WindowEvent.WINDOW_CLOSED,
            -> onWindowDeactivated(event.window)
        }
    }

    /**
     * The bookkeeping, separated from the AWT plumbing above so the debounce can
     * be driven from a test without a window manager — activating a real window
     * programmatically depends on the desktop environment's cooperation and is
     * flaky everywhere.
     */
    internal fun onWindowActivated(window: Any) {
        activeWindows += window
        scheduleSettle()
    }

    internal fun onWindowDeactivated(window: Any) {
        activeWindows -= window
        scheduleSettle()
    }

    override fun start() {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK)
    }

    override fun stop() {
        stopped = true
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        pending?.cancel(false)
        pending = null
        settleTimer.shutdownNow()
        activeWindows.clear()
    }

    private fun scheduleSettle() {
        if (stopped) return
        pending?.cancel(false)
        // The flag is checked above, but stop() can still land between that check
        // and this line — the executor is shut down either way, and a late event
        // is nothing to report, so dropping it is the whole handling.
        pending = runCatching {
            settleTimer.schedule(::settle, settleDelayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * Reports the transition, if there is one.
     *
     * Reads [activeWindows] from the timer thread rather than the AWT thread. The
     * race is benign: a set that changes underneath produces one more settle,
     * which re-reads and reaches the same answer.
     */
    private fun settle() {
        val backgrounded = activeWindows.isEmpty()
        if (backgrounded == reportedBackgrounded) return
        reportedBackgrounded = backgrounded

        handler.onEvent(
            if (backgrounded) {
                InterruptionEvent.Began(InterruptionCause.AppBackgrounded)
            } else {
                InterruptionEvent.Ended(InterruptionCause.AppBackgrounded)
            },
        )
    }
}
