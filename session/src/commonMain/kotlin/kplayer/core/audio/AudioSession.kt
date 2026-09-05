package kplayer.core.audio

import kotlinx.coroutines.flow.Flow

/**
 * Platform-neutral owner of the right to play audio. Acquires/releases audio
 * ownership and reports interruptions — it makes no playback decisions.
 *
 * Must be called at actual playback start/stop, not at player init/teardown.
 */
interface AudioSession {

    val interruptions: Flow<AudioInterruption>

    /**
     * Call this immediately before starting or resuming playback.
     * @param config describes the content type/behavior for this session.
     * Returns true if ownership was granted and playback may proceed.
     */
    fun acquire(config: AudioSessionConfig): Boolean

    /**
     * Re-acquire ownership after an interruption, reusing the last [acquire]
     * config. Returns true if granted (playback may resume), false if denied
     * (another app holds ownership) — the caller should stay paused.
     */
    fun reacquire(): Boolean

    fun release()
}

expect fun createAudioSession(): AudioSession
