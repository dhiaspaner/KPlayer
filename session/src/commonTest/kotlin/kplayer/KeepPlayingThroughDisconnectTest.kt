package kplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kplayer.interruption.AudioFocusPolicy
import kplayer.interruption.BackgroundPolicy
import kplayer.interruption.DefaultPlaybackInterruptionHandler
import kplayer.interruption.HeadphonesPolicy
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionConfig
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import kplayer.core.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The half of "ignore the headphones" that the policy alone cannot deliver.
 *
 * `Ignore` and `ContinuePlayback` are answered by not pausing, and that is the
 * whole story on Android and the web, where the route change is only a
 * notification. On iOS `AVPlayer` stops itself when the output device it was
 * playing to disappears: the player reaches `Paused` with no library call
 * behind it, so the policy is overruled a moment after it decided. The handler
 * watches briefly for exactly that pause and undoes it.
 *
 * [FakePlayer.platformPause] stands in for AVFoundation here — it moves the
 * player to `Paused` without counting as a call, which is what makes "the
 * library never paused" assertable.
 *
 * The tests that matter most are the ones where the recovery must stay out of
 * the way: a pause it undoes wrongly is worse than the bug it fixes.
 */
class KeepPlayingThroughDisconnectTest {

    private companion object {
        const val WINDOW_MS = 500L
    }

    private fun ignoringHeadphones(
        headphonesPolicy: HeadphonesPolicy = HeadphonesPolicy.ContinuePlayback,
        audioFocusPolicy: AudioFocusPolicy = AudioFocusPolicy.RestoreIfPlayingBefore,
    ) = InterruptionConfig(
        backgroundPolicy = BackgroundPolicy.KeepState,
        audioFocusPolicy = audioFocusPolicy,
        headphonesPolicy = headphonesPolicy,
    )

    private val disconnect = InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected)

    /**
     * The handler's scope dispatches inline, as `Dispatchers.Main.immediate`
     * does in production, so the watch is already collecting by the time the
     * platform pause lands. Its `delay` still runs on virtual time, which is
     * what [advanceTimeBy] drives — and the scope is cancelled in a `finally`,
     * since an abandoned watch would otherwise outlive its test.
     */
    private fun withHandler(
        config: InterruptionConfig = ignoringHeadphones(),
        start: FakePlayer.() -> Unit = { loadAndPlay() },
        body: TestScope.(PlaybackInterruptionHandler, FakePlayer) -> Unit,
    ): TestResult = runTest {
        val player = FakePlayer().also(start)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val handler = DefaultPlaybackInterruptionHandler(
            config = MutableStateFlow(config),
            player = player,
            audioSession = FakeAudioSession(),
            scope = scope,
            keepPlayingWindowMs = WINDOW_MS,
        )
        try {
            body(handler, player)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a platform pause after an ignored disconnect is undone`() = withHandler { h, player ->
        h.onEvent(disconnect)
        // AVFoundation pausing itself, a moment after the route change.
        player.platformPause()

        assertEquals(PlaybackStatus.Playing, player.state.value.status)
        assertEquals(2, player.playCallCount) // the initial play, then the recovery
        assertEquals(0, player.pauseCallCount) // and the library never paused
    }

    @Test
    fun `the same holds for Ignore`() =
        withHandler(ignoringHeadphones(HeadphonesPolicy.Ignore)) { h, player ->
            h.onEvent(disconnect)
            player.platformPause()

            assertEquals(PlaybackStatus.Playing, player.state.value.status)
            assertEquals(0, player.pauseCallCount)
        }

    @Test
    fun `nothing is issued on a platform that leaves playback alone`() = withHandler { h, player ->
        h.onEvent(disconnect)
        advanceTimeBy(WINDOW_MS + 1)

        // Android and the web only notify; the window expires having done nothing.
        assertEquals(1, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `a pause arriving after the window is left alone`() = withHandler { h, player ->
        h.onEvent(disconnect)
        advanceTimeBy(WINDOW_MS + 1)
        // Far too late to be the route change — this is the user pressing Pause.
        player.pause()

        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        assertEquals(1, player.playCallCount)
    }

    @Test
    fun `a pause the policy itself asked for is never undone`() =
        withHandler(ignoringHeadphones(HeadphonesPolicy.PauseAndRequireManualResume)) { h, player ->
            h.onEvent(disconnect)
            advanceTimeBy(WINDOW_MS + 1)

            assertEquals(PlaybackStatus.Paused, player.state.value.status)
            assertEquals(1, player.pauseCallCount)
            assertEquals(1, player.playCallCount)
        }

    @Test
    fun `an interruption that outranks the disconnect keeps the player paused`() =
        withHandler { h, player ->
            h.onEvent(disconnect)
            // A call arriving in the same breath as the unplug. Its policy
            // pauses, and that decision has to survive a watch that is looking
            // for precisely this transition.
            h.onEvent(InterruptionEvent.Began(InterruptionCause.AudioFocusLoss))
            advanceTimeBy(WINDOW_MS + 1)

            assertEquals(PlaybackStatus.Paused, player.state.value.status)
            assertEquals(1, player.pauseCallCount)
            assertEquals(1, player.playCallCount)

            // And the interruption that caused the pause still resumes it.
            h.onEvent(
                InterruptionEvent.Ended(InterruptionCause.AudioFocusLoss, systemAllowsResume = true)
            )
            assertEquals(PlaybackStatus.Playing, player.state.value.status)
        }

    @Test
    fun `backgrounding under KeepState is not recovered from`() = withHandler { h, player ->
        h.onEvent(InterruptionEvent.Began(InterruptionCause.AppBackgrounded))
        // An OS that does not allow background playback stops us. Playing again
        // would be fighting a rule no policy can overrule.
        player.platformPause()
        advanceTimeBy(WINDOW_MS + 1)

        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        assertEquals(1, player.playCallCount)
    }

    @Test
    fun `an ignored focus loss is not recovered from`() =
        withHandler(ignoringHeadphones(audioFocusPolicy = AudioFocusPolicy.Ignore)) { h, player ->
            h.onEvent(InterruptionEvent.Began(InterruptionCause.AudioFocusLoss))
            // Another app has the output. Playing over it is rude and futile.
            player.platformPause()
            advanceTimeBy(WINDOW_MS + 1)

            assertEquals(PlaybackStatus.Paused, player.state.value.status)
            assertEquals(1, player.playCallCount)
        }

    @Test
    fun `a disconnect while already paused never starts playback`() =
        withHandler(start = { loadAndPause() }) { h, player ->
            h.onEvent(disconnect)
            advanceTimeBy(WINDOW_MS + 1)

            assertEquals(PlaybackStatus.Paused, player.state.value.status)
            assertEquals(1, player.playCallCount) // the initial load only
        }

    @Test
    fun `a disconnect while buffering never starts playback`() =
        withHandler(start = { loadAndBuffer() }) { h, player ->
            h.onEvent(disconnect)
            player.platformPause() // even if the platform moves it to Paused
            advanceTimeBy(WINDOW_MS + 1)

            assertEquals(PlaybackStatus.Paused, player.state.value.status)
            assertEquals(0, player.playCallCount)
        }

    @Test
    fun `without a scope the handler stays synchronous and recovers nothing`() = runTest {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = DefaultPlaybackInterruptionHandler(
            config = MutableStateFlow(ignoringHeadphones()),
            player = player,
            audioSession = FakeAudioSession(),
            // No scope: the opt-out every other handler test runs under, and the
            // reason none of them had to change.
        )

        h.onEvent(disconnect)
        player.platformPause()
        advanceTimeBy(WINDOW_MS + 1)

        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        assertEquals(1, player.playCallCount)
    }
}
