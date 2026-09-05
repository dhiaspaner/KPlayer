package kplayer.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.audio.AudioInterruption
import kplayer.core.audio.AudioSession
import kplayer.core.audio.AudioSessionConfig

class AudioSessionCoordinator(
    private val session: AudioSession,
    private val config: StateFlow<AudioSessionConfig>,
) {
    val interruptions: Flow<AudioInterruption> = session.interruptions
    fun acquire(): Boolean = session.acquire(config.value)

    fun release() = session.release()
}
