package kplayer.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The browser owns audio arbitration, so there is nothing for us to acquire.
 *
 * A page cannot request or lose "audio focus": the browser decides autoplay
 * eligibility from its own gesture policy, and when another tab or app takes over
 * it simply pauses the element and fires `pause` — which reaches us through the
 * engine's own listener, not through here. Always grant, never interrupt.
 *
 * The gesture policy is the one real difference from a native platform: a `play()`
 * before any user interaction is rejected by the browser, and that surfaces as a
 * failure from the engine rather than as a denied session.
 */
actual fun createAudioSession(): AudioSession = WebAudioSession

internal object WebAudioSession : AudioSession {

    override val interruptions: Flow<AudioInterruption> = emptyFlow()

    override fun acquire(config: AudioSessionConfig): Boolean = true

    override fun reacquire(): Boolean = true

    override fun release() = Unit
}
