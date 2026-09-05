package kplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kplayer.core.audio.AudioInterruption
import kplayer.core.audio.AudioSession
import kplayer.core.audio.AudioSessionConfig
import kplayer.core.audio.AudioSessionMode
import kplayer.engine.AudioSessionCoordinator
import kplayer.engine.KMediaManager
import kplayer.interruption.DefaultPlaybackInterruptionHandler
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class KMediaManagerAudioFocusTest {

    @Test
    fun `audio focus loss pauses playback`() = runTest {
        val focusController = FakeAudioSession()
        val player = FakePlayer()

        val manager = createManager(
            player,
            focusController
        )
        advanceUntilIdle()

        player.loadAndPlay()

        assertEquals(
            PlaybackStatus.Playing,
            player.state.value.status
        )

        focusController.emitInterruption(
            AudioInterruption.Began
        )
        advanceUntilIdle()

        assertEquals(
            PlaybackStatus.Paused,
            player.state.value.status
        )

        manager.release()
    }

    @Test
    fun `audio focus regain resumes playback`() = runTest {
        val focusController = FakeAudioSession()
        val player = FakePlayer()

        val manager = createManager(
            player,
            focusController
        )
        advanceUntilIdle()

        player.loadAndPlay()

        focusController.emitInterruption(
            AudioInterruption.Began
        )
        advanceUntilIdle()

        assertEquals(
            PlaybackStatus.Paused,
            player.state.value.status
        )

        focusController.emitInterruption(
            AudioInterruption.Ended(systemAllowsResume = true)
        )
        advanceUntilIdle()

        assertEquals(
            PlaybackStatus.Playing,
            player.state.value.status
        )

        manager.release()
    }

    @Test
    fun `transient audio focus loss pauses playback`() = runTest {
        val focusController = FakeAudioSession()
        val player = FakePlayer()

        val manager = createManager(
            player,
            focusController
        )
        advanceUntilIdle()

        player.loadAndPlay()

        focusController.emitInterruption(
            AudioInterruption.Began
        )
        advanceUntilIdle()

        assertEquals(
            PlaybackStatus.Paused,
            player.state.value.status
        )

        manager.release()
    }

    @Test
    fun `duck lowers volume without pausing and restores on end`() = runTest {
        val focusController = FakeAudioSession()
        val player = FakePlayer()

        val manager = createManager(
            player,
            focusController
        )
        advanceUntilIdle()

        player.loadAndPlay()

        // MediaPlayerDefault duckPolicy = LowerVolume(0.2)
        focusController.emitInterruption(AudioInterruption.DuckBegan)
        advanceUntilIdle()

        assertEquals(PlaybackStatus.Playing, player.state.value.status) // never pauses
        assertEquals(0.2f, player.state.value.volume)

        focusController.emitInterruption(AudioInterruption.DuckEnded)
        advanceUntilIdle()

        assertEquals(PlaybackStatus.Playing, player.state.value.status)
        assertEquals(1f, player.state.value.volume) // restored

        manager.release()
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.createManager(
    player: FakePlayer,
    focusController: AudioSession
): KMediaManager<FakePlaybackState, FakePlayer> {
    val manager = KMediaManager(
        player = player,
        playbackInterruptionHandler = DefaultPlaybackInterruptionHandler(
            config = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
            player = player,
            audioSession = focusController,
        ),
        observers = emptyList(),
        audioSessionCoordinator = AudioSessionCoordinator(
            session = focusController,
            config = MutableStateFlow(AudioSessionConfig(mode = AudioSessionMode.Music)),
        ),
        scope = backgroundScope,
    )
    // Run the freshly-launched collector until it actually subscribes to
    // focusChanges, otherwise an emit() before that point (unbuffered
    // MutableSharedFlow) is silently dropped.
    runCurrent()
    return manager
}