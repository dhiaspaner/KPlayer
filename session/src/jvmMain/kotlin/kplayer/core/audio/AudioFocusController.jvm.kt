package kplayer.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Desktop has no audio-session ownership to arbitrate.
 *
 * Windows, macOS and Linux all let every process play audio simultaneously and
 * mix it — there is no equivalent of Android audio focus or `AVAudioSession`
 * interruptions, so there is nothing to acquire and nothing that can interrupt us.
 * This is therefore the *correct* implementation and not a stub: always grant, and
 * never report an interruption.
 *
 * It used to be `TODO()`, which meant every desktop player crashed on its first
 * `play()`. `AudioCoexistence` is ignored for the same reason: `Duck` and
 * `Exclusive` have no desktop meaning, and honouring them would require mixing
 * control the platform does not expose.
 */
actual fun createAudioSession(): AudioSession = DesktopAudioSession

internal object DesktopAudioSession : AudioSession {

    override val interruptions: Flow<AudioInterruption> = emptyFlow()

    override fun acquire(config: AudioSessionConfig): Boolean = true

    override fun reacquire(): Boolean = true

    override fun release() = Unit
}
