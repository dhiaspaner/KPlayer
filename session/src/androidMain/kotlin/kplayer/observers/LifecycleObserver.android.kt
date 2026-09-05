package kplayer.observers

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler

actual fun createLifecycleObserver(handler: PlaybackInterruptionHandler): LifecycleObserver =
    AndroidLifecycleObserver(handler)

internal class AndroidLifecycleObserver(
    private val handler: PlaybackInterruptionHandler,
) : LifecycleObserver {

    private var observer: DefaultLifecycleObserver? = null

    override fun start() {
        observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) =
                handler.onEvent(InterruptionEvent.Began(InterruptionCause.AppBackgrounded))

            override fun onStart(owner: LifecycleOwner) =
                handler.onEvent(InterruptionEvent.Ended(InterruptionCause.AppBackgrounded))
        }.also { ProcessLifecycleOwner.get().lifecycle.addObserver(it) }
    }

    override fun stop() {
        observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        observer = null
    }
}
