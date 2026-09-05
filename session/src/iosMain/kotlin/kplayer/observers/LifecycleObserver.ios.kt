package kplayer.observers

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler

actual fun createLifecycleObserver(handler: PlaybackInterruptionHandler): LifecycleObserver =
    IosLifecycleObserver(handler)

internal class IosLifecycleObserver(
    private val handler: PlaybackInterruptionHandler,
) : LifecycleObserver {

    private var backgroundObserver: NSObjectProtocol? = null
    private var foregroundObserver: NSObjectProtocol? = null

    override fun start() {
        val center = NSNotificationCenter.defaultCenter
        backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> handler.onEvent(InterruptionEvent.Began(InterruptionCause.AppBackgrounded)) }

        foregroundObserver = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> handler.onEvent(InterruptionEvent.Ended(InterruptionCause.AppBackgrounded)) }
    }

    override fun stop() {
        val center = NSNotificationCenter.defaultCenter
        backgroundObserver?.let { center.removeObserver(it) }
        foregroundObserver?.let { center.removeObserver(it) }
        backgroundObserver = null
        foregroundObserver = null
    }
}
