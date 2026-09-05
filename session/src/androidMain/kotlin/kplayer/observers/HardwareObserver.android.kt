package kplayer.observers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import kplayer.appContext
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler

actual fun createHardwareObserver(handler: PlaybackInterruptionHandler): HardwareObserver =
    AndroidHardwareObserver(handler)

internal class AndroidHardwareObserver(
    private val handler: PlaybackInterruptionHandler,
) : HardwareObserver {

    private var receiver: BroadcastReceiver? = null

    override fun start() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // Android has no matching "becoming available" broadcast, so this
                // interruption only ever begins (mirrors prior behavior — no
                // auto-resume on reconnect on Android).
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                    handler.onEvent(InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected))
            }
        }.also {
            appContext.registerReceiver(
                it,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    override fun stop() {
        receiver?.let { appContext.unregisterReceiver(it) }
        receiver = null
    }
}
