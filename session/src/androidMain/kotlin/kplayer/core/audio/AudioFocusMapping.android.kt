package kplayer.core.audio

import android.media.AudioManager

/**
 * What a given [AudioCoexistence] asks the platform for, as plain data.
 *
 * Separated from the request-building in [AndroidAudioSession] so the decision
 * can be asserted without an `AudioManager`, a `Context` or a device — the fields
 * are `Int` constants and a `Boolean`, which a host-side unit test reads
 * directly.
 *
 * @param requestsFocus false means "do not arbitrate at all": hold no focus
 *   request, so we never pause anyone and are never told to stop.
 * @param focusGain the `AudioManager.AUDIOFOCUS_GAIN*` constant to request.
 *   Meaningless when [requestsFocus] is false.
 */
internal data class AndroidFocusPlan(
    val requestsFocus: Boolean,
    val focusGain: Int,
)

/**
 * Focus follows [AudioCoexistence] and nothing else.
 *
 * [AudioSessionMode] deliberately does not appear here. It used to:
 * `VoiceCommunication` mapped to `AUDIOFOCUS_GAIN_TRANSIENT` on the theory that
 * voice is transient, which was wrong twice over. An ongoing communication
 * session is not transient — it lasts as long as the call — and because the mode
 * check came first in the `when`, `VoiceCommunication` combined with
 * [AudioCoexistence.Duck] silently dropped the duck and requested plain transient
 * focus instead. Mode belongs to `AudioAttributes`; see [platformAudioAttributesFor].
 */
internal fun focusPlanFor(coexistence: AudioCoexistence): AndroidFocusPlan =
    when (coexistence) {
        AudioCoexistence.Exclusive -> AndroidFocusPlan(
            requestsFocus = true,
            focusGain = AudioManager.AUDIOFOCUS_GAIN,
        )

        AudioCoexistence.Mix -> AndroidFocusPlan(
            requestsFocus = false,
            focusGain = AudioManager.AUDIOFOCUS_GAIN,
        )

        AudioCoexistence.Duck -> AndroidFocusPlan(
            requestsFocus = true,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
    }

/**
 * Translates `AudioManager` focus callbacks into [AudioInterruption]s, holding
 * the one piece of state the translation needs: whether we are currently ducked.
 *
 * `AUDIOFOCUS_GAIN` ends both a pause and a duck, so the matching "ended" event
 * cannot be chosen from the callback alone. Pulled out of [AndroidAudioSession]
 * so the state machine is testable with plain ints:
 *
 * ```
 * CAN_DUCK                  → DuckBegan          (once; repeats are absorbed)
 * GAIN            while ducked → DuckEnded
 * GAIN            otherwise    → Ended(systemAllowsResume = true)
 * LOSS / LOSS_TRANSIENT while ducked → DuckEnded, then Began
 * LOSS / LOSS_TRANSIENT otherwise    → Began
 * ```
 *
 * The DuckEnded-before-Began ordering matters: it restores our volume before we
 * pause, so resuming later does not come back quiet.
 */
internal class FocusChangeTranslator {

    var isDucked: Boolean = false
        private set

    fun translate(focusChange: Int): List<AudioInterruption> = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN ->
            if (isDucked) {
                isDucked = false
                listOf(AudioInterruption.DuckEnded)
            } else {
                listOf(AudioInterruption.Ended(systemAllowsResume = true))
            }

        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            if (isDucked) {
                isDucked = false
                listOf(AudioInterruption.DuckEnded, AudioInterruption.Began)
            } else {
                listOf(AudioInterruption.Began)
            }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            // Already ducked: the system re-notifying is not a second duck, and
            // emitting DuckBegan twice would have the handler capture the
            // already-lowered volume as the level to restore to.
            if (isDucked) {
                emptyList()
            } else {
                isDucked = true
                listOf(AudioInterruption.DuckBegan)
            }

        else -> emptyList()
    }

    /**
     * Winds up an in-progress duck for a session that is abandoning focus.
     *
     * Returns the [AudioInterruption.DuckEnded] the caller still owes, or nothing
     * if we were not ducked. Once focus is abandoned no further callback can
     * arrive, so without this the duck would never be closed: the handler would
     * hold the pre-duck volume forever and every later playback would come back
     * quiet, while a subsequent `AUDIOFOCUS_GAIN` would read as the end of a duck
     * that belonged to a previous session.
     */
    fun endDuck(): List<AudioInterruption> =
        if (isDucked) {
            isDucked = false
            listOf(AudioInterruption.DuckEnded)
        } else {
            emptyList()
        }
}
