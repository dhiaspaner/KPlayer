package kplayer.observers

import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReason
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.darwin.NSObjectProtocol
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler

actual fun createHardwareObserver(handler: PlaybackInterruptionHandler): HardwareObserver =
    IosHardwareObserver(handler)

/**
 * The **only** route-change observer in the library's iOS path.
 *
 * `AVAudioSessionRouteChangeNotification` is the one signal iOS gives for "the
 * headphones came out", and it arrives here as an
 * [InterruptionCause.HeadphonesDisconnected] — never as a pause. What happens
 * next is `DefaultPlaybackInterruptionHandler`'s decision against the configured
 * `headphonesPolicy`, exactly as `ACTION_AUDIO_BECOMING_NOISY` is on Android:
 *
 * ```
 * route change → HeadphonesDisconnected → handler → policy → pause / continue
 * ```
 *
 * `IosAudioSession` used to observe the same notification and emit an
 * `AudioInterruption.Began` for it, which reaches the handler as
 * `AudioFocusLoss` and is judged against the *focus* policy. A disconnect
 * therefore paused whatever `headphonesPolicy` said — including `Ignore`. That
 * observer is gone; see the note in its place for why nothing replaced it.
 */
internal class IosHardwareObserver(
    private val handler: PlaybackInterruptionHandler,
) : HardwareObserver {

    private var observer: NSObjectProtocol? = null

    override fun start() {
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            // Delivered inline on the posting thread rather than hopped to the
            // main queue: the handler only reads a StateFlow and issues player
            // actions, and every backend dispatches those onto its own
            // main-bound scope, so nothing here touches AVFoundation off-main.
            queue = null,
        ) { notification ->
            val userInfo = notification?.userInfo ?: return@addObserverForName
            val reason = (userInfo[AVAudioSessionRouteChangeReasonKey] as? NSNumber)
                ?.unsignedIntegerValue ?: return@addObserverForName

            routeChangeInterruption(reason)?.let(handler::onEvent)
        }
    }

    override fun stop() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
    }
}

/**
 * The interruption a route change represents, or `null` for the ones that are
 * not interruptions at all.
 *
 * Only two of the seven reasons mean anything to playback, and being explicit
 * about that is the point: a category change, a wake from sleep or a
 * configuration change must not read as an unplug. `OldDeviceUnavailable` is
 * the previous output going away — headphones out, AirPods in the case, a
 * Bluetooth speaker switched off — and `NewDeviceAvailable` is the reverse, so
 * a policy that resumes on reconnect has an `Ended` to act on.
 *
 * Pulled out of the notification block so the classification can be tested
 * without an audio session, a player, or a device.
 */
internal fun routeChangeInterruption(reason: AVAudioSessionRouteChangeReason): InterruptionEvent? =
    when (reason) {
        AVAudioSessionRouteChangeReasonOldDeviceUnavailable ->
            InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected)

        AVAudioSessionRouteChangeReasonNewDeviceAvailable ->
            InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected)

        else -> null
    }
