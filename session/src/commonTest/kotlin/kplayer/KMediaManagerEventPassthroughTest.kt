package kplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kplayer.core.audio.AudioSessionConfig
import kplayer.core.audio.AudioSessionMode
import kplayer.engine.AudioSessionCoordinator
import kplayer.engine.KMediaManager
import kplayer.core.event.PlaybackEvent
import kplayer.interruption.DefaultPlaybackInterruptionHandler
import kplayer.interruption.InterruptionConfig
import kplayer.core.player.EngineMediaPlayer
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does everything reach a caller holding only the manager?
 *
 * The whole stack is real here — a [FakeMediaEngine] standing in for the native
 * player, a real [EngineMediaPlayer] on top of it, a real [KMediaManager] wrapping
 * that — so an event has to survive two merges to be seen: the player's (engine
 * facts and its own events into one stream) and the manager's (the player's stream
 * and its own).
 *
 * Three sources feed the one stream a caller subscribes to:
 *  - the **engine**, reporting what the native player did;
 *  - the **player**, raising what it did itself (`LoadRequested`, `PositionSynced`);
 *  - the **manager**, describing what it refused to let happen at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KMediaManagerEventPassthroughTest {

    private val source = MediaSource.Url("a.mp3")

    @Test
    fun `every fact the engine reports reaches the manager`() = runTest {
        withHarness { h ->
            // Loading first, so the engine reports into a player that is actually
            // waiting for it — the order a real backend produces.
            h.manager.load(source)
            assertEquals(listOf(PlaybackEvent.LoadRequested(source)), h.drain())

            // Ready clears the buffering that LoadRequested started, so two events
            // land for one engine report.
            h.engine.emitReady(60_000L)
            assertEquals(
                listOf(PlaybackEvent.BufferingEnded, PlaybackEvent.Ready(60_000L)),
                h.drain(),
            )

            // PlaybackStarted also starts the position loop, whose first tick is
            // immediate — a player-originated event riding on an engine fact.
            h.engine.emitPlaying(true)
            assertEquals(
                listOf(PlaybackEvent.PlaybackStarted, PlaybackEvent.PositionSynced(0L)),
                h.drain(),
            )

            h.engine.emitBuffering(true)
            assertEquals(listOf(PlaybackEvent.BufferingStarted), h.drain())

            h.engine.emitBuffering(false)
            assertEquals(listOf(PlaybackEvent.BufferingEnded), h.drain())

            h.engine.emitPlaying(false)
            assertEquals(listOf(PlaybackEvent.PlaybackPaused), h.drain())

            h.engine.emitCompleted()
            assertTrue(
                PlaybackEvent.PlaybackCompleted in h.drain(),
                "the engine's completion did not reach the manager",
            )

            h.engine.emitError(PlaybackError.Unknown("boom"))
            assertTrue(
                h.drain().any { it is PlaybackEvent.Failure },
                "the engine's failure did not reach the manager",
            )
        }
    }

    /**
     * A fact outside the shared vocabulary — video's subtitle cues — rides the same
     * stream untouched. Nothing between the engine and the caller needs to know
     * what it means.
     */
    @Test
    fun `a medium-specific engine event reaches the manager unchanged`() = runTest {
        withHarness { h ->
            h.engine.emitCustom(PlaybackEvent.SubtitleCueChanged("hello"))
            assertEquals(listOf(PlaybackEvent.SubtitleCueChanged("hello")), h.drain())
        }
    }

    @Test
    fun `events the player raises itself reach the manager`() = runTest {
        withHarness { h ->
            h.manager.load(source)
            assertEquals(listOf(PlaybackEvent.LoadRequested(source)), h.drain())

            h.manager.seekTo(1_234L)
            assertEquals(listOf(PlaybackEvent.PositionSynced(1_234L)), h.drain())

            h.manager.setPlaybackSpeed(2f)
            assertEquals(listOf(PlaybackEvent.SpeedChanged(2f)), h.drain())

            // Clamped by the player on the way through, and reported as clamped.
            h.manager.setVolume(1.5f)
            assertEquals(listOf(PlaybackEvent.VolumeChanged(1f)), h.drain())

            h.manager.stop()
            assertEquals(listOf(PlaybackEvent.StopRequested), h.drain())
        }
    }

    /**
     * Order holds *within* a source but not across them, and the difference is real
     * rather than incidental: the manager describes a denied session synchronously,
     * on the calling thread, while the player's actions run on its own scope. There
     * is no moment at which those two are sequenced against each other, so the test
     * asserts what is actually promised — everything arrives, and each source's own
     * events stay in order.
     */
    @Test
    fun `all three sources arrive on the one stream`() = runTest {
        withHarness { h ->
            h.manager.load(source)                              // player
            h.engine.emitReady(1_000L)                          // engine
            h.session.grantOwnership = false
            h.manager.play()                                    // manager

            val seen = h.drain()
            val denial = PlaybackEvent.Failure(PlaybackError.AudioSessionDenied)

            assertEquals(
                setOf(
                    PlaybackEvent.LoadRequested(source),
                    PlaybackEvent.BufferingEnded,
                    PlaybackEvent.Ready(1_000L),
                    denial,
                ),
                seen.toSet(),
            )
            assertEquals(
                listOf(
                    PlaybackEvent.LoadRequested(source),
                    PlaybackEvent.BufferingEnded,
                    PlaybackEvent.Ready(1_000L),
                ),
                seen.filterNot { it == denial },
                "the player's own events arrived out of order",
            )
        }
    }

    /**
     * The one engine report that is deliberately *not* passed on.
     *
     * `EngineMediaPlayer` collapses runs of buffering into one started/ended pair,
     * because ExoPlayer re-enters `STATE_BUFFERING` freely and iOS toggles
     * `playbackLikelyToKeepUp` on every hiccup. The manager sees what the player
     * accepted, which is also what `state` was computed from.
     */
    @Test
    fun `a repeated buffering report is collapsed before the manager sees it`() = runTest {
        withHarness { h ->
            h.manager.load(source)
            h.engine.emitReady(60_000L)
            h.engine.emitPlaying(true)
            h.drain()

            h.engine.emitBuffering(true)
            assertEquals(listOf(PlaybackEvent.BufferingStarted), h.drain())

            h.engine.emitBuffering(true)
            assertEquals(emptyList(), h.drain(), "a repeated stall should collapse")
        }
    }

    // ── Nothing is dropped under backpressure ─────────────────────────────────

    /**
     * The burst that a bounded buffer used to eat.
     *
     * Every event here is reported before the consumer runs even once, so the whole
     * run has to sit in the intake queue. With the old `MutableSharedFlow` +
     * `tryEmit` the 65th report onwards returned `false` and vanished, which is why
     * this count is well past any buffer size that was ever configured.
     */
    @Test
    fun `no engine event is dropped when the consumer is far behind`() = runTest {
        withHarness { h ->
            val burst = 1_000

            repeat(burst) { h.engine.emitCustom(PlaybackEvent.SubtitleCueChanged("cue $it")) }
            val seen = h.drain()

            assertEquals(burst, seen.size, "events were dropped between engine and manager")
            assertEquals(PlaybackEvent.SubtitleCueChanged("cue 0"), seen.first())
            assertEquals(PlaybackEvent.SubtitleCueChanged("cue ${burst - 1}"), seen.last())
        }
    }

    /** The same guarantee for the events the manager describes itself. */
    @Test
    fun `no denial is dropped when a burst of commands is refused`() = runTest {
        withHarness { h ->
            val burst = 500
            h.session.grantOwnership = false

            repeat(burst) { h.manager.play() }
            val seen = h.drain()

            assertEquals(
                List(burst) { PlaybackEvent.Failure(PlaybackError.AudioSessionDenied) },
                seen,
            )
        }
    }

    /**
     * Known gap, pinned so a fix is noticed here.
     *
     * `release()` cancels the manager's scope, and that scope is what runs the
     * merge — so the stream is already dead by the time the player gets round to
     * reporting `ReleaseRequested`. The action itself is not lost (the engine is
     * released); only the report of it is. A caller waiting on `events` for
     * confirmation of teardown waits forever.
     */
    @Test
    fun `ReleaseRequested does not reach the manager because release kills the stream`() =
        runTest {
            withHarness { h ->
                h.manager.release()

                assertEquals(emptyList(), h.drain())
                assertTrue("release" in h.engine.calls, "the engine was never released")
            }
        }
}

// ── Harness ───────────────────────────────────────────────────────────────────

private class Harness(
    val engine: FakeMediaEngine,
    val manager: KMediaManager<FakePlaybackState, EngineMediaPlayer<FakePlaybackState>>,
    val session: FakeAudioSession,
    private val seen: MutableList<PlaybackEvent>,
    private val advance: () -> Unit,
) {
    /**
     * Everything seen since the last call.
     *
     * Drains rather than accumulates so each step asserts on its own events; the
     * alternative is one growing expected-list per test, where a failure names the
     * whole history instead of the step that broke.
     */
    fun drain(): List<PlaybackEvent> {
        advance()
        val batch = seen.toList()
        seen.clear()
        return batch
    }
}

/**
 * Builds engine → player → manager and hands the body a recorder.
 *
 * The player gets its own scope, as it does in production: `KMediaManagerBuilder`
 * creates the manager's, while the backend brings its own. Sharing one here would
 * make `release()` cancel the player's in-flight actions too, which is not what
 * happens in a real app.
 *
 * Scopes are cancelled in a `finally`: the position-sync loop shares the test
 * scheduler, so a failed assertion that skipped this would leave `runTest`
 * advancing virtual time against an infinite `delay` — a hang instead of a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private inline fun TestScope.withHarness(body: (Harness) -> Unit) {
    val engine = FakeMediaEngine()
    val session = FakeAudioSession()
    val playerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
    val player = EngineMediaPlayer(engine, FakePlaybackState(), playerScope)
    val manager = KMediaManager(
        player = player,
        playbackInterruptionHandler = DefaultPlaybackInterruptionHandler(
            config = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
            player = player,
            audioSession = session,
        ),
        observers = emptyList(),
        audioSessionCoordinator = AudioSessionCoordinator(
            session = session,
            config = MutableStateFlow(AudioSessionConfig(mode = AudioSessionMode.Music)),
        ),
        scope = backgroundScope,
    )
    runCurrent()

    val seen = mutableListOf<PlaybackEvent>()
    backgroundScope.launch { manager.events.collect { seen += it } }
    runCurrent()

    try {
        // runCurrent, not advanceUntilIdle: as of coroutines 1.11 the latter does
        // not drive backgroundScope work, and every collector here lives there.
        body(Harness(engine, manager, session, seen) { runCurrent() })
    } finally {
        playerScope.cancel()
    }
}
