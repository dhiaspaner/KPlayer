package kplayer.core.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Session reactivation and the interruption lifecycle are separate concepts.
 *
 * `AudioSession.interruptions` reaches `KMediaManager` as
 * `InterruptionCause.AudioFocusLoss`, so anything emitted here is judged against
 * `audioFocusPolicy`. Emitting an `Ended` nobody asked for clears that cause from
 * the handler's active set; failing to emit one leaves it stuck there forever.
 * Both directions are regressions this pins down.
 */
class IosAudioSessionInterruptionTest {

    /**
     * The session's observers are delivered on the main queue, so assertions have
     * to let the main run loop drain first — a Kotlin/Native test blocks the very
     * thread those blocks are queued on.
     */
    private fun pumpMainQueue(seconds: Double = 0.25) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private fun postInterruption(type: ULong, options: ULong? = null) {
        val userInfo = buildMap<Any?, Any?> {
            put(AVAudioSessionInterruptionTypeKey, type.toLong())
            options?.let { put(AVAudioSessionInterruptionOptionKey, it.toLong()) }
        }
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = AVAudioSessionInterruptionNotification,
            // Matched against the observer's registration, which uses the shared
            // instance — the same object the session under test holds.
            `object` = AVAudioSession.sharedInstance(),
            userInfo = userInfo,
        )
    }

    private fun postAppDidBecomeActive() {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            userInfo = null,
        )
    }

    private fun withSession(body: (IosAudioSession, List<AudioInterruption>) -> Unit) {
        val session = IosAudioSession()
        val received = mutableListOf<AudioInterruption>()
        // Unconfined so the collector is subscribed before the first post; a
        // SharedFlow with no subscriber drops what it is given.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { session.interruptions.collect { received += it } }
        try {
            body(session, received)
        } finally {
            scope.cancel()
            session.dispose()
        }
    }

    /**
     * The headline regression. Foregrounding used to emit
     * `Ended(systemAllowsResume = true)` on every activation, which cleared
     * `AudioFocusLoss` from the handler's active set — so a player paused by a
     * phone call could resume straight over the top of the call as soon as the
     * user glanced at the app.
     */
    @Test
    fun `foregrounding the app is not an interruption ending`() = withSession { _, received ->
        postAppDidBecomeActive()
        pumpMainQueue()

        assertTrue(received.isEmpty(), "expected no interruption event, got $received")
    }

    @Test
    fun `foregrounding repeatedly still produces nothing`() = withSession { _, received ->
        repeat(3) { postAppDidBecomeActive() }
        pumpMainQueue()

        assertTrue(received.isEmpty(), "expected no interruption event, got $received")
    }

    @Test
    fun `an interruption began is reported`() = withSession { _, received ->
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        pumpMainQueue()

        assertEquals(listOf(AudioInterruption.Began), received)
    }

    /**
     * The other direction: an `Ended` must be emitted even when reactivation
     * fails, because the `Ended` is what clears `AudioFocusLoss` from the
     * handler's active set. It used to be emitted only `if (reactivate())`, so a
     * failed reactivation left the cause active with nothing able to end it and
     * blocked every later auto-resume for the life of the player.
     *
     * No configuration was ever acquired here, so reactivation cannot succeed —
     * which makes this deterministic and pins the `systemAllowsResume` value too:
     * the system said resume, we could not, so the answer is no.
     */
    @Test
    fun `an interruption ended is reported even when reactivation fails`() = withSession { _, received ->
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        pumpMainQueue()

        postInterruption(
            AVAudioSessionInterruptionTypeEnded,
            options = AVAudioSessionInterruptionOptionShouldResume,
        )
        pumpMainQueue()

        assertEquals(
            listOf(
                AudioInterruption.Began,
                AudioInterruption.Ended(systemAllowsResume = false),
            ),
            received,
        )
    }

    @Test
    fun `an interruption ended without shouldResume does not allow resuming`() = withSession { _, received ->
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        postInterruption(AVAudioSessionInterruptionTypeEnded)
        pumpMainQueue()

        assertEquals(
            listOf(
                AudioInterruption.Began,
                AudioInterruption.Ended(systemAllowsResume = false),
            ),
            received,
        )
    }

    /**
     * Once iOS has ended the interruption properly, foregrounding has nothing
     * left to recover — the fallback must not add a second `Ended` for a cause
     * that is no longer active.
     */
    @Test
    fun `foregrounding after a completed interruption adds nothing`() = withSession { _, received ->
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        postInterruption(AVAudioSessionInterruptionTypeEnded)
        pumpMainQueue()
        val afterInterruption = received.size

        postAppDidBecomeActive()
        pumpMainQueue()

        assertEquals(afterInterruption, received.size, "got extra events: $received")
    }

    /**
     * `reacquire()` is recovery, not resumption. It is called directly by
     * `DefaultPlaybackInterruptionHandler` before resuming, and if it emitted
     * anything the handler would be re-entered from inside its own resume path.
     */
    @Test
    fun `reacquire emits nothing`() = withSession { session, received ->
        session.reacquire()
        pumpMainQueue()

        assertTrue(received.isEmpty(), "expected no interruption event, got $received")
    }

    /**
     * `acquire` is not an interruption event either — it is the ordinary "I am
     * about to play" call, made on every `play()`.
     */
    @Test
    fun `acquire emits nothing`() = withSession { session, received ->
        session.acquire(AudioSessionConfig(AudioSessionMode.Music))
        pumpMainQueue()

        assertTrue(received.isEmpty(), "expected no interruption event, got $received")
    }
}
