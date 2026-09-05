package kplayer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kplayer.core.audio.AudioInterruption
import kplayer.core.audio.AudioSession
import kplayer.core.audio.AudioSessionConfig

class FakeAudioSession : AudioSession {

    private val _interruptions = MutableSharedFlow<AudioInterruption>()

    override val interruptions: Flow<AudioInterruption> =
        _interruptions

    var lastRequestedConfig: AudioSessionConfig? = null
        private set

    /** Toggles whether (re)acquire grants ownership, for testing denied resumes. */
    var grantOwnership: Boolean = true

    override fun acquire(config: AudioSessionConfig): Boolean {
        lastRequestedConfig = config
        return grantOwnership
    }

    override fun reacquire(): Boolean = grantOwnership

    override fun release() = Unit

    suspend fun emitInterruption(interruption: AudioInterruption) {
        _interruptions.emit(interruption)
    }

}
