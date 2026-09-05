package kplayer.observers

import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import kotlinx.browser.document
import org.w3c.dom.events.Event

/**
 * `visibilitychange` is the web's "app backgrounded".
 *
 * It fires when the tab is hidden — switched away from, minimised, or the phone
 * locked — which is the closest analogue to `ProcessLifecycleOwner.onStop` on
 * Android and `UIApplicationDidEnterBackground` on iOS. Deliberately not `blur`:
 * that fires when the page merely loses keyboard focus, which happens constantly
 * (clicking the address bar) and would pause playback for no reason.
 *
 * Note that a hidden tab is *allowed* to keep playing audio; nothing here forces a
 * pause. Whether backgrounding should pause is `BackgroundPolicy`'s decision, same
 * as on every other platform.
 */
actual fun createLifecycleObserver(handler: PlaybackInterruptionHandler): LifecycleObserver =
    WebLifecycleObserver(handler)

/**
 * `Document.hidden` is not in `kotlinx-browser`'s bindings, and reading it through
 * JS interop is cheaper than depending on a binding that may or may not be there.
 */
private fun isDocumentHidden(): Boolean = js("document.hidden")

internal class WebLifecycleObserver(
    private val handler: PlaybackInterruptionHandler,
) : LifecycleObserver {

    private val listener: (Event) -> Unit = {
        if (isDocumentHidden()) {
            handler.onEvent(InterruptionEvent.Began(InterruptionCause.AppBackgrounded))
        } else {
            handler.onEvent(InterruptionEvent.Ended(InterruptionCause.AppBackgrounded))
        }
    }

    override fun start() {
        document.addEventListener("visibilitychange", listener)
    }

    override fun stop() {
        document.removeEventListener("visibilitychange", listener)
    }
}
