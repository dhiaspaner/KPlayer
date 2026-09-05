package kplayer.core.audio

/**
 * A platform-neutral audio-session interruption, produced by [AudioSession].
 *
 * Both platforms map their native concepts into this shape — Android
 * `AudioManager` focus changes and iOS `AVAudioSession` interruptions — so no
 * "audio focus" terminology leaks past the audio.core boundary.
 */
sealed interface AudioInterruption {

    /** The session lost the ability to play (focus loss, phone call, Siri, …). */
    data object Began : AudioInterruption

    /**
     * The OS no longer requires the interruption.
     *
     * systemAllowsResume=true only means the platform considers
     * playback permissible again. It does not imply that playback
     * should automatically resume.
     */
    data class Ended(val systemAllowsResume: Boolean = true) : AudioInterruption

    /**
     * Another app took transient focus we may duck under (lower our volume)
     * rather than pause for. Paired with [DuckEnded] when that app releases it.
     */
    data object DuckBegan : AudioInterruption

    /** The transient ducking condition ended — our volume may be restored. */
    data object DuckEnded : AudioInterruption
}
