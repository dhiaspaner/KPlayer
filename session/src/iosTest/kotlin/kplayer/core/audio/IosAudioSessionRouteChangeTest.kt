package kplayer.core.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionType
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReason
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression: an audio session must not turn a route change into an
 * interruption.
 *
 * `AudioSession.interruptions` is read by `KMediaManager` as
 * `InterruptionCause.AudioFocusLoss` — there is nothing else it could be — so
 * anything emitted here is judged against `audioFocusPolicy`, which pauses by
 * default. This session used to emit `Began` for `OldDeviceUnavailable`, so an
 * unplug paused playback through the focus policy no matter what
 * `headphonesPolicy` said, `Ignore` included. The unplug belongs to
 * `IosHardwareObserver` and its own cause; this session reports interruptions.
 */
class IosAudioSessionRouteChangeTest {

    /**
     * The session's own observers are delivered on the main queue, so the
     * assertions have to let the main run loop drain first — a Kotlin/Native
     * test blocks the very thread those blocks are queued on.
     */
    private fun pumpMainQueue(seconds: Double = 0.25) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private fun postToSession(name: String?, key: String?, value: ULong) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = name!!,
            // Matched against the observer's registration, which uses the shared
            // instance — the same object the session under test holds.
            `object` = AVAudioSession.sharedInstance(),
            userInfo = mapOf<Any?, Any?>(key to value.toLong()),
        )
    }

    private fun postRouteChange(reason: AVAudioSessionRouteChangeReason) =
        postToSession(
            AVAudioSessionRouteChangeNotification,
            AVAudioSessionRouteChangeReasonKey,
            reason,
        )

    private fun postInterruption(type: AVAudioSessionInterruptionType) =
        postToSession(
            AVAudioSessionInterruptionNotification,
            AVAudioSessionInterruptionTypeKey,
            type,
        )

    private fun withSession(body: (List<AudioInterruption>) -> Unit) {
        val session = IosAudioSession()
        val received = mutableListOf<AudioInterruption>()
        // Unconfined so the collector is subscribed before the first post; a
        // SharedFlow with no subscriber drops what it is given.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { session.interruptions.collect { received += it } }
        try {
            body(received)
        } finally {
            scope.cancel()
            session.dispose()
        }
    }

    @Test
    fun `an unavailable output device produces no audio session interruption`() = withSession { received ->
        postRouteChange(AVAudioSessionRouteChangeReasonOldDeviceUnavailable)
        pumpMainQueue()

        assertTrue(received.isEmpty(), "expected no interruption, got $received")
    }

    @Test
    fun `a genuine interruption still arrives`() = withSession { received ->
        // The control for the test above: same notification centre, same queue,
        // same pump. Without this, "nothing was received" would also pass on a
        // harness that delivers nothing at all.
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        pumpMainQueue()

        assertEquals(listOf(AudioInterruption.Began), received)
    }

    @Test
    fun `an unavailable output device does not disturb a session already interrupted`() = withSession { received ->
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        pumpMainQueue()
        assertEquals(listOf(AudioInterruption.Began), received)

        postRouteChange(AVAudioSessionRouteChangeReasonOldDeviceUnavailable)
        pumpMainQueue()

        // Still one: unplugging headphones during a phone call must not stack a
        // second focus loss that the matching Ended could never clear.
        assertEquals(listOf(AudioInterruption.Began), received)
    }
}
