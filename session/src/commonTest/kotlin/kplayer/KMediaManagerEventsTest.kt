package kplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kplayer.core.audio.AudioSession
import kplayer.core.audio.AudioSessionConfig
import kplayer.core.audio.AudioSessionMode
import kplayer.engine.AudioSessionCoordinator
import kplayer.engine.KMediaManager
import kplayer.core.event.PlaybackAction
import kplayer.core.event.PlaybackEvent
import kplayer.interruption.DefaultPlaybackInterruptionHandler
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.PlaybackError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The merged event stream: a caller holding only the manager still hears both the
 * wrapped player and the manager itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KMediaManagerEventsTest {

    @Test
    fun `a denied audio session is reported on the manager's events`() = runTest {
        val session = FakeAudioSession().apply { grantOwnership = false }
        val player = FakePlayer()
        val manager = createManager(player, session)

        val seen = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { manager.events.collect { seen += it } }
        runCurrent()

        manager.play()
        runCurrent()

        assertEquals<List<PlaybackEvent>>(
            listOf(PlaybackEvent.Failure(PlaybackError.AudioSessionDenied)),
            seen,
        )
        // Denial means the command never reached the player at all.
        assertEquals(0, player.playCallCount)
    }

    @Test
    fun `the wrapped player's own events reach the manager's subscriber`() = runTest {
        val session = FakeAudioSession()
        val player = FakePlayer()
        val manager = createManager(player, session)

        val seen = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { manager.events.collect { seen += it } }
        runCurrent()

        player.emitEvent(PlaybackEvent.Ready(durationMs = 60_000L))
        player.emitEvent(PlaybackEvent.PlaybackStarted)
        runCurrent()

        assertEquals<List<PlaybackEvent>>(
            listOf(PlaybackEvent.Ready(60_000L), PlaybackEvent.PlaybackStarted),
            seen,
        )
    }

    @Test
    fun `both sources interleave on one stream`() = runTest {
        val session = FakeAudioSession()
        val player = FakePlayer()
        val manager = createManager(player, session)

        val seen = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { manager.events.collect { seen += it } }
        runCurrent()

        player.emitEvent(PlaybackEvent.PlaybackStarted)
        runCurrent()

        session.grantOwnership = false
        manager.play()
        runCurrent()

        assertEquals<List<PlaybackEvent>>(
            listOf(
                PlaybackEvent.PlaybackStarted,
                PlaybackEvent.Failure(PlaybackError.AudioSessionDenied),
            ),
            seen,
        )
    }

    /**
     * `onAction` must stay on the decorated path — the MVI entry point arbitrates
     * the audio session exactly like the direct call does.
     */
    @Test
    fun `an action goes through audio-session arbitration too`() = runTest {
        val session = FakeAudioSession().apply { grantOwnership = false }
        val player = FakePlayer()
        val manager = createManager(player, session)

        val seen = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { manager.events.collect { seen += it } }
        runCurrent()

        manager.onAction(PlaybackAction.Play)
        runCurrent()

        assertEquals(
            0,
            player.playCallCount,
            "onAction(Play) reached the player despite a denied audio session. " +
                "KMediaManager must override onAction and delegate to super: " +
                "without it, `by player` forwards the action straight to the wrapped " +
                "player, whose default onAction calls its own play().",
        )
        assertTrue(
            seen.contains(PlaybackEvent.Failure(PlaybackError.AudioSessionDenied)),
            "onAction(Play) bypassed the audio session; saw $seen",
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.createManager(
    player: FakePlayer,
    session: AudioSession,
): KMediaManager<FakePlaybackState, FakePlayer> {
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
    return manager
}
