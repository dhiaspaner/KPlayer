package kplayer.observers

import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReason
import platform.AVFAudio.AVAudioSessionRouteChangeReasonCategoryChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOverride
import platform.AVFAudio.AVAudioSessionRouteChangeReasonRouteConfigurationChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonWakeFromSleep
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS half of the headphones path: a route-change notification in, an
 * [InterruptionEvent] out.
 *
 * Nothing here needs an `AVPlayer`, an audio session or a device — the observer
 * reads notifications and hands the result to a [PlaybackInterruptionHandler],
 * which is the seam the policy engine sits behind. What the policy then *does*
 * with the event is `DefaultPlaybackInterruptionHandler`'s business and is
 * tested in `commonTest`, on the JVM, for every platform at once.
 */
class IosHardwareObserverTest {

    private class RecordingHandler : PlaybackInterruptionHandler {
        val events = mutableListOf<InterruptionEvent>()
        override fun onEvent(event: InterruptionEvent) {
            events += event
        }
    }

    /**
     * The observer registers with `queue = null`, so the notification is
     * delivered inline and [RecordingHandler.events] is up to date on return.
     */
    private fun postRouteChange(reason: AVAudioSessionRouteChangeReason) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = AVAudioSessionRouteChangeNotification,
            `object` = null,
            userInfo = mapOf<Any?, Any?>(
                AVAudioSessionRouteChangeReasonKey to reason.toLong(),
            ),
        )
    }

    private fun withObserver(body: (RecordingHandler) -> Unit) {
        val handler = RecordingHandler()
        val observer = IosHardwareObserver(handler)
        observer.start()
        try {
            body(handler)
        } finally {
            observer.stop()
        }
    }

    @Test
    fun `an unavailable output device is reported as a headphones disconnect`() = withObserver { handler ->
        postRouteChange(AVAudioSessionRouteChangeReasonOldDeviceUnavailable)

        // Specifically not AudioFocusLoss: the cause is what selects the policy,
        // and only this one is judged against headphonesPolicy.
        assertEquals(
            listOf<InterruptionEvent>(InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected)),
            handler.events,
        )
    }

    @Test
    fun `a new output device ends the headphones interruption`() = withObserver { handler ->
        postRouteChange(AVAudioSessionRouteChangeReasonNewDeviceAvailable)

        assertEquals(
            listOf<InterruptionEvent>(InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected)),
            handler.events,
        )
    }

    @Test
    fun `unrelated route changes are not interruptions`() = withObserver { handler ->
        listOf(
            AVAudioSessionRouteChangeReasonCategoryChange,
            AVAudioSessionRouteChangeReasonOverride,
            AVAudioSessionRouteChangeReasonWakeFromSleep,
            AVAudioSessionRouteChangeReasonRouteConfigurationChange,
        ).forEach(::postRouteChange)

        // Switching category or waking from sleep is not an unplug; treating any
        // route change as one is how a player ends up pausing at random.
        assertTrue(handler.events.isEmpty(), "expected no events, got ${handler.events}")
    }

    @Test
    fun `a route change with no reason is ignored`() = withObserver { handler ->
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = AVAudioSessionRouteChangeNotification,
            `object` = null,
            userInfo = null,
        )

        assertTrue(handler.events.isEmpty(), "expected no events, got ${handler.events}")
    }

    @Test
    fun `a stopped observer reports nothing`() {
        val handler = RecordingHandler()
        val observer = IosHardwareObserver(handler)

        observer.start()
        observer.stop()
        postRouteChange(AVAudioSessionRouteChangeReasonOldDeviceUnavailable)

        assertTrue(handler.events.isEmpty(), "expected no events, got ${handler.events}")
    }

    // ── Classification, without the notification centre ────────────────────────

    @Test
    fun `only the two device-availability reasons map to an interruption`() {
        assertEquals(
            InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected),
            routeChangeInterruption(AVAudioSessionRouteChangeReasonOldDeviceUnavailable),
        )
        assertEquals(
            InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected),
            routeChangeInterruption(AVAudioSessionRouteChangeReasonNewDeviceAvailable),
        )
        assertEquals(null, routeChangeInterruption(AVAudioSessionRouteChangeReasonCategoryChange))
        assertEquals(null, routeChangeInterruption(AVAudioSessionRouteChangeReasonOverride))
        assertEquals(null, routeChangeInterruption(AVAudioSessionRouteChangeReasonWakeFromSleep))
        assertEquals(
            null,
            routeChangeInterruption(AVAudioSessionRouteChangeReasonRouteConfigurationChange),
        )
    }
}
