package kplayer.audioplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kplayer.core.player.EngineMediaPlayer
import kplayer.core.player.PlaybackRetryPolicy
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.core.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The backend contract, tested with no device and no media.
 *
 * [EngineMediaPlayer] holds everything the platform classes used to duplicate, so
 * these tests cover both Android and iOS behaviour: all that is left in
 * `ExoAudioEngine` / `AvAudioEngine` is native translation. The engine's events are
 * emitted by hand through [FakeMediaEngine]'s `emit…` calls, which also makes
 * orderings a real engine produces only rarely — a failure mid-buffer, a completion
 * straight after a pause — cheap to assert on.
 *
 * Both scopes use `UnconfinedTestDispatcher` so actions and transitions run inline;
 * the position-sync loop's `delay` still runs on virtual time, so [advanceTimeBy]
 * drives it.
 */
class EngineMediaPlayerTest {

    private val source = MediaSource.Url("episode.mp3")

    private class Fixture(
        val engine: FakeMediaEngine,
        val player: EngineMediaPlayer<AudioPlayerState>,
        private val source: MediaSource,
        private val scopes: List<CoroutineScope>,
    ) {
        val state get() = player.state.value

        fun close() = scopes.forEach { it.cancel() }

        /** Loads and reaches Playing, the way both real engines report it. */
        fun reachPlaying(durationMs: Long = 100_000L) {
            player.load(source)
            engine.emitReady(durationMs)
            engine.emitPlaying(true)
        }
    }

    /**
     * Cleanup runs in a `finally` on purpose. The position-sync loop shares the
     * test scheduler, so a failed assertion that skipped cancellation would leave
     * `runTest` advancing virtual time against an infinite `delay` loop — the test
     * would hang instead of reporting the failure.
     */
    private fun withPlayer(
        rejectSources: Set<MediaSource> = emptySet(),
        retryPolicy: PlaybackRetryPolicy = PlaybackRetryPolicy.None,
        body: TestScope.(Fixture) -> Unit,
    ): TestResult = runTest(timeout = 10.seconds) {
        val engine = FakeMediaEngine(rejectSources)
        val actionScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val machineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(
            engine = engine,
            player = EngineMediaPlayer(
                engine = engine,
                initialState = AudioPlayerState(),
                scope = actionScope,
                retryPolicy = retryPolicy,
            ),
            source = source,
            scopes = listOf(actionScope, machineScope),
        )
        try {
            body(fixture)
        } finally {
            fixture.close()
        }
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    @Test
    fun `an event reported before the first command is not lost`() = withPlayer { f ->
        // The engine's flow does not replay, so the player has to be subscribed by
        // the time its constructor returns. A subscription merely scheduled on the
        // action scope would drop this — and with it any fault a native player
        // reports while it is still being built.
        f.engine.emitError("engine died on construction")

        assertEquals(PlaybackStatus.Error, f.state.status)
        assertEquals(emptyList(), f.engine.calls)
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    @Test
    fun `load sets the source then prepares`() = withPlayer { f ->
        f.player.load(source)

        assertEquals(listOf("setSource($source)", "prepare"), f.engine.calls)
        assertEquals(PlaybackStatus.Buffering, f.state.status)
        assertEquals(source, f.state.source)
    }

    @Test
    fun `a source the engine rejects fails instead of hanging in buffering`() =
        withPlayer(rejectSources = setOf(source)) { f ->
            f.player.load(source)

            // The regression this guards: announcing LoadRequested before validating
            // would strand the player in Buffering with nothing to complete it.
            assertEquals(PlaybackStatus.Error, f.state.status)
            assertEquals("Invalid source: $source", f.state.errorMessage)
            // A source the engine cannot represent is a bad source, not a mystery:
            // the classification is what stops a retry policy from trying again.
            assertIs<PlaybackError.Source>(f.state.error)
            assertEquals(0, f.engine.prepareCount)
        }

    // ── Auto-play ─────────────────────────────────────────────────────────────

    @Test
    fun `ready auto-plays when playWhenReady and records duration`() = withPlayer { f ->
        f.player.load(source)
        f.engine.emitReady(durationMs = 100_000)

        assertTrue(f.state.playWhenReady)
        assertEquals(1, f.engine.playCount)
        assertEquals(100_000L, f.state.durationMs)
        // Still Ready, not Playing: status follows the engine's confirmation, never
        // the command we issued.
        assertEquals(PlaybackStatus.Ready, f.state.status)

        f.engine.emitPlaying(true)
        assertEquals(PlaybackStatus.Playing, f.state.status)
    }

    @Test
    fun `a live stream reports zero duration without failing`() = withPlayer { f ->
        f.player.load(source)
        f.engine.emitReady(durationMs = 0)

        assertEquals(PlaybackStatus.Ready, f.state.status)
        assertEquals(0L, f.state.durationMs)
    }

    // ── Buffering ─────────────────────────────────────────────────────────────

    @Test
    fun `repeated buffering reports are inert`() = withPlayer { f ->
        f.reachPlaying()

        f.engine.emitBuffering(true)
        val afterFirst = f.state
        assertEquals(PlaybackStatus.Buffering, afterFirst.status)

        // A real engine re-enters buffering freely; only the first report may move
        // us, and the rest must not produce a second BufferingStarted.
        f.engine.emitBuffering(true)
        f.engine.emitBuffering(true)

        assertEquals(afterFirst, f.state)
    }

    @Test
    fun `ready after a stall resumes at the same position`() = withPlayer { f ->
        f.reachPlaying()
        f.engine.positionMs = 50_000
        advanceTimeBy(600)

        f.engine.emitBuffering(true)
        assertEquals(PlaybackStatus.Buffering, f.state.status)

        f.engine.emitReady(100_000)
        f.engine.emitPlaying(true)

        assertEquals(PlaybackStatus.Playing, f.state.status)
        assertEquals(50_000L, f.state.positionMs)
    }

    // ── Position sync ─────────────────────────────────────────────────────────

    @Test
    fun `position syncs from the engine while playing and stops on pause`() = withPlayer { f ->
        f.reachPlaying()

        f.engine.positionMs = 1_000
        advanceTimeBy(600)
        assertEquals(1_000L, f.state.positionMs)

        f.engine.positionMs = 2_000
        advanceTimeBy(500)
        assertEquals(2_000L, f.state.positionMs)

        f.engine.emitPlaying(false)
        assertEquals(PlaybackStatus.Paused, f.state.status)

        // Loop cancelled: further engine movement is no longer picked up.
        f.engine.positionMs = 9_000
        advanceTimeBy(2_000)
        assertEquals(2_000L, f.state.positionMs)
    }

    @Test
    fun `position sync stops at completion`() = withPlayer { f ->
        f.reachPlaying()

        f.engine.emitCompleted()
        assertEquals(PlaybackStatus.Completed, f.state.status)
        // Completion is the end of the media by definition, so the position says
        // so rather than keeping wherever the polling interval last landed.
        assertEquals(100_000L, f.state.positionMs)

        // Loop cancelled: the engine moving on its own is no longer picked up.
        f.engine.positionMs = 99_000
        advanceTimeBy(2_000)
        assertEquals(100_000L, f.state.positionMs)
    }

    @Test
    fun `restarting after completion rewinds and plays again`() = withPlayer { f ->
        f.reachPlaying()
        f.engine.emitCompleted()
        assertEquals(100_000L, f.state.positionMs)

        // What a Restart button does. The regression this guards: Completed
        // accepted nothing but a reload, so the seek was dropped and the seek bar
        // stayed pinned at the end while the media played from the beginning.
        f.player.seekTo(0)
        assertEquals(0L, f.state.positionMs)

        f.player.play()
        f.engine.emitPlaying(true)
        assertEquals(PlaybackStatus.Playing, f.state.status)

        f.engine.positionMs = 3_000
        advanceTimeBy(600)
        assertEquals(3_000L, f.state.positionMs)
    }

    @Test
    fun `seek reports the target immediately`() = withPlayer { f ->
        f.reachPlaying()

        f.player.seekTo(42_000)

        assertEquals(42_000L, f.engine.lastSeekMs)
        assertEquals(42_000L, f.state.positionMs)
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    @Test
    fun `stop pauses and rewinds and resets position`() = withPlayer { f ->
        f.reachPlaying()
        f.engine.positionMs = 30_000
        advanceTimeBy(600)
        assertEquals(30_000L, f.state.positionMs)

        f.player.stop()

        assertEquals(PlaybackStatus.Stopped, f.state.status)
        assertEquals(0L, f.state.positionMs)
        assertEquals(0L, f.engine.lastSeekMs)
        assertEquals(1, f.engine.pauseCount)
    }

    @Test
    fun `release pauses then releases the engine`() = withPlayer { f ->
        f.reachPlaying()

        f.player.release()

        assertTrue(f.engine.released)
        assertEquals(PlaybackStatus.Released, f.state.status)
        // Paused before released, so the engine never tears down mid-playback.
        assertTrue(f.engine.calls.indexOf("pause") < f.engine.calls.indexOf("release"))
    }

    @Test
    fun `volume is clamped before reaching the engine`() = withPlayer { f ->
        f.player.setVolume(2f)
        assertEquals(1f, f.engine.volume)
        assertEquals(1f, f.state.volume)

        f.player.setVolume(-1f)
        assertEquals(0f, f.engine.volume)
        assertEquals(0f, f.state.volume)
    }

    @Test
    fun `speed reaches the engine and the state`() = withPlayer { f ->
        f.player.setPlaybackSpeed(1.5f)

        assertEquals(1.5f, f.engine.speed)
        assertEquals(1.5f, f.state.playbackSpeed)
    }

    // ── Failure ───────────────────────────────────────────────────────────────

    @Test
    fun `an error mid-buffer surfaces and stops syncing`() = withPlayer { f ->
        f.reachPlaying()
        f.engine.emitBuffering(true)

        f.engine.emitError("Network died")

        assertEquals(PlaybackStatus.Error, f.state.status)
        assertEquals("Network died", f.state.errorMessage)

        f.engine.positionMs = 77_000
        advanceTimeBy(2_000)
        assertEquals(0L, f.state.positionMs)
    }

    @Test
    fun `completion straight after a pause still completes`() = withPlayer { f ->
        f.reachPlaying()

        // The ordering ExoAudioEngine suppresses and AvAudioEngine can still
        // produce: not-playing immediately followed by end-of-media.
        f.engine.emitPlaying(false)
        f.engine.emitCompleted()

        assertEquals(PlaybackStatus.Completed, f.state.status)
    }

    @Test
    fun `loading again after an error clears it`() = withPlayer { f ->
        f.player.load(source)
        f.engine.emitError("Network died")

        val next = MediaSource.Url("next.mp3")
        f.player.load(next)

        assertEquals(PlaybackStatus.Buffering, f.state.status)
        assertNull(f.state.errorMessage)
        assertEquals(next, f.state.source)
        assertFalse(f.engine.released)
    }

    // ── The error boundary ────────────────────────────────────────────────────

    @Test
    fun `an action that throws becomes a described failure instead of escaping`() =
        withPlayer { f ->
            f.engine.throwingCalls += "play"

            f.player.play()

            // Uncaught, this would have died inside the action scope and left the
            // player silently stuck in Ready.
            assertEquals(PlaybackStatus.Error, f.state.status)
            assertIs<PlaybackError.Unknown>(f.state.error)
            assertEquals("play failed", f.state.errorMessage)
        }

    @Test
    fun `a cancellation is not mistaken for a playback failure`() = withPlayer { f ->
        f.engine.throwingCalls += "play"
        f.engine.throwableFor = { CancellationException(it) }

        f.player.play()

        // The regression `runCatching` invites: it catches CancellationException
        // like any other throwable, which would report an Error nobody hit and stop
        // release() from tearing the player down.
        assertEquals(PlaybackStatus.Idle, f.state.status)
        assertNull(f.state.error)
    }

    @Test
    fun `the boundary survives the failure and keeps taking actions`() = withPlayer { f ->
        f.engine.throwingCalls += "play"
        f.player.play()

        f.engine.throwingCalls -= "play"
        f.player.load(source)

        // The action scope was not cancelled by the throw, so the next command runs.
        assertEquals(PlaybackStatus.Buffering, f.state.status)
        assertNull(f.state.error)
    }

    /**
     * What the injectable `errorMapper` used to be asked to preserve, now that
     * classification is `Throwable.toPlaybackError()` and no caller configures it:
     * the description keeps the throwable that produced it, so a caller still has
     * the stack that actually broke rather than a string copied out of it.
     */
    @Test
    fun `a described failure carries the throwable it came from`() = withPlayer { f ->
        val thrown = IllegalStateException("codec went away")
        f.engine.throwingCalls += "setVolume"
        f.engine.throwableFor = { thrown }

        f.player.setVolume(0.4f)

        assertIs<PlaybackError.Unknown>(f.state.error)
        assertSame(thrown, f.state.error?.cause)
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Test
    fun `by default nothing is retried`() = withPlayer { f ->
        f.engine.throwingCalls += "prepare"

        f.player.load(source)
        advanceTimeBy(60_000)

        assertEquals(PlaybackStatus.Error, f.state.status)
        assertEquals(1, f.engine.calls.count { it == "prepare" })
    }

    @Test
    fun `a retry re-executes the very action that failed`() = withPlayer(
        retryPolicy = PlaybackRetryPolicy.transient(maxAttempts = 3, initialDelay = 500.milliseconds),
    ) { f ->
        f.engine.throwingCalls += "seekTo"

        f.player.seekTo(42_000)
        assertEquals(listOf(42_000L), f.engine.calls.seeks())

        // Same action, same argument — the policy never had to know how to rebuild it.
        advanceTimeBy(600)
        assertEquals(listOf(42_000L, 42_000L), f.engine.calls.seeks())
    }

    @Test
    fun `a retry budget is spent and then the failure stands`() = withPlayer(
        retryPolicy = PlaybackRetryPolicy.transient(maxAttempts = 3, initialDelay = 500.milliseconds),
    ) { f ->
        f.engine.throwingCalls += "play"

        f.player.play()
        assertEquals(1, f.engine.calls.count { it == "play" })

        advanceTimeBy(600)
        assertEquals(2, f.engine.calls.count { it == "play" })

        // Backoff doubles, so the second wait is 1000ms.
        advanceTimeBy(1_100)
        assertEquals(3, f.engine.calls.count { it == "play" })

        advanceTimeBy(60_000)
        assertEquals(3, f.engine.calls.count { it == "play" })
        assertEquals(PlaybackStatus.Error, f.state.status)
    }

    @Test
    fun `a retry that works leaves no trace of the failure`() = withPlayer(
        retryPolicy = PlaybackRetryPolicy.transient(initialDelay = 500.milliseconds),
    ) { f ->
        f.engine.throwingCalls += "prepare"
        f.player.load(source)
        assertEquals(PlaybackStatus.Error, f.state.status)

        f.engine.throwingCalls -= "prepare"
        advanceTimeBy(600)
        assertEquals(PlaybackStatus.Buffering, f.state.status)

        f.engine.emitReady(durationMs = 100_000)
        assertEquals(PlaybackStatus.Ready, f.state.status)
        assertNull(f.state.error)
    }

    @Test
    fun `a failure the policy calls permanent is not retried`() = withPlayer(
        rejectSources = setOf(source),
        retryPolicy = PlaybackRetryPolicy.transient(initialDelay = 500.milliseconds),
    ) { f ->
        f.player.load(source)
        advanceTimeBy(60_000)

        // PlaybackError.Source: the same URL will be just as invalid in 500ms.
        assertEquals(1, f.engine.calls.count { it.startsWith("setSource") })
        assertEquals(PlaybackStatus.Error, f.state.status)
    }

    @Test
    fun `an engine fault takes the same route as a thrown one`() = withPlayer(
        retryPolicy = PlaybackRetryPolicy.transient(maxAttempts = 2, initialDelay = 500.milliseconds),
    ) { f ->
        f.reachPlaying()

        f.engine.emitError(PlaybackError.Network(message = "connection reset"))

        assertEquals(PlaybackStatus.Error, f.state.status)
        assertIs<PlaybackError.Network>(f.state.error)

        // A faulted engine has thrown its prepared item away, so recovery is a
        // reload rather than a repeat of whatever was last commanded.
        advanceTimeBy(600)
        assertEquals(PlaybackStatus.Buffering, f.state.status)
        assertEquals(2, f.engine.prepareCount)

        // Budget of 2 spent across the original fault and its one retry.
        f.engine.emitError(PlaybackError.Network(message = "connection reset"))
        advanceTimeBy(60_000)
        assertEquals(2, f.engine.prepareCount)
    }

    @Test
    fun `reaching the engine again starts a fresh retry budget`() = withPlayer(
        retryPolicy = PlaybackRetryPolicy.transient(maxAttempts = 2, initialDelay = 500.milliseconds),
    ) { f ->
        f.reachPlaying()
        f.engine.emitError(PlaybackError.Network(message = "first drop"))
        advanceTimeBy(600)
        assertEquals(2, f.engine.prepareCount)

        // The reload works this time; an outage ten minutes later is a new incident,
        // not the tail of the old one, so it gets its own attempt.
        f.engine.emitReady(durationMs = 100_000)
        f.engine.emitPlaying(true)
        f.engine.emitError(PlaybackError.Network(message = "second drop"))
        advanceTimeBy(600)

        assertEquals(3, f.engine.prepareCount)
    }

    /** The positions of the `seekTo(...)` entries in a call log, in order. */
    private fun List<String>.seeks(): List<Long> =
        filter { it.startsWith("seekTo(") }
            .map { it.removePrefix("seekTo(").removeSuffix(")").toLong() }
}
