package kplayer.engine.dsl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSession
import kplayer.core.audio.AudioSessionConfig
import kplayer.core.audio.AudioSessionMode
import kplayer.core.audio.createAudioSession
import kplayer.engine.AudioSessionCoordinator
import kplayer.engine.KMediaManager
import kplayer.interruption.DefaultPlaybackInterruptionHandler
import kplayer.interruption.InterruptionConfig
import kplayer.interruption.PlaybackInterruptionHandler
import kplayer.observers.DefaultObservers
import kplayer.observers.InterruptionObserver
import kplayer.core.state.MediaSource
import kplayer.core.state.PlayerState

class KMediaManagerBuilder<S : PlayerState<S>> {

    private lateinit var playerFactory: () -> MediaPlayer<MediaSource, S>
    private lateinit var interruptionConfig: StateFlow<InterruptionConfig>   // single source of truth
    private lateinit var mode: AudioSessionMode                              // required, no default
    private var audioSessionFactory: () -> AudioSession = { createAudioSession() }

    private var observersFactory: (PlaybackInterruptionHandler) -> List<InterruptionObserver> =
        { handler -> DefaultObservers(handler) }

    fun player(factory: () -> MediaPlayer<MediaSource, S>) =
        apply { playerFactory = factory }

    fun interruptionConfig(config: StateFlow<InterruptionConfig>) =
        apply { interruptionConfig = config }

    fun mode(mode: AudioSessionMode) =
        apply { this.mode = mode }

    fun audioSession(factory: () -> AudioSession) =
        apply { audioSessionFactory = factory }

    fun observers(factory: (PlaybackInterruptionHandler) -> List<InterruptionObserver>) = apply { observersFactory = factory }

    fun build(): MediaPlayer<MediaSource, S> {

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = playerFactory()

        // the ONE place allowed to import both AudioFocusPolicy and AudioSessionConfig
        val sessionConfig: StateFlow<AudioSessionConfig> = interruptionConfig
            .map { it.audioFocusPolicy.toAudioSessionConfig(mode) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = interruptionConfig.value.audioFocusPolicy.toAudioSessionConfig(mode)
            )

        // Shared between the coordinator (owns config) and the handler (resume
        // re-acquires ownership via session.reacquire()).
        val audioSession = audioSessionFactory()
        val audioSessionCoordinator = AudioSessionCoordinator(
            session = audioSession,
            config = sessionConfig,   // plain audio.core type — no AudioFocusPolicy import here
        )

        val handler = DefaultPlaybackInterruptionHandler(
            config = interruptionConfig,
            player = player,
            audioSession = audioSession,
            // The manager's own scope, so release() cancels the handler's
            // keep-playing watch along with everything else.
            scope = scope,
        )
        val observers = observersFactory(handler)

        return KMediaManager(
            player = player,
            playbackInterruptionHandler = handler,
            observers = observers,
            scope = scope,
            audioSessionCoordinator = audioSessionCoordinator,
        )
    }
}
