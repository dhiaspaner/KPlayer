package kplayer.interruption

import kplayer.core.event.PlaybackEvent

/**
 * A began/ended notification for an [InterruptionCause].
 *
 * Every interruption source (audio session, lifecycle, hardware, and future
 * ones) reports through this single shape instead of a bespoke event per
 * source, so the handler can treat them uniformly.
 */
sealed interface InterruptionEvent : PlaybackEvent {

    /** The interruption started (e.g. phone call began, app backgrounded). */
    data class Began(val cause: InterruptionCause) : InterruptionEvent

    /**
     * The interruption ended (e.g. call finished, app foregrounded, headphones
     * reconnected).
     *
     * @param systemAllowsResume the OS "safe to resume" hint — iOS
     * `AVAudioSession` `.shouldResume`, Android `AUDIOFOCUS_GAIN`. Sources that
     * have no such hint (lifecycle, hardware) pass `true`.
     */
    data class Ended(
        val cause: InterruptionCause,
        val systemAllowsResume: Boolean = true,
    ) : InterruptionEvent

    /** Transient ducking began — lower volume, do not pause. Carries no cause. */
    data object DuckBegan : InterruptionEvent

    /** Transient ducking ended — restore volume. */
    data object DuckEnded : InterruptionEvent
}
