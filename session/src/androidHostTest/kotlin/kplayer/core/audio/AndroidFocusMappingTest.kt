package kplayer.core.audio

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android half of the audio-session mapping, asserted without a device.
 *
 * Everything here is `Int` constants and a small state machine, which is exactly
 * why the decisions were pulled out of `AndroidAudioSession`: `AudioManager` and
 * `AudioAttributes` are not mockable in a host test, but the constants they
 * define are compile-time inlined and the mapping functions are pure.
 */
class AndroidFocusMappingTest {

    // ── Coexistence → focus ───────────────────────────────────────────────────

    @Test
    fun `exclusive requests full audio focus`() {
        val plan = focusPlanFor(AudioCoexistence.Exclusive)

        assertTrue(plan.requestsFocus)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, plan.focusGain)
    }

    @Test
    fun `mix requests no audio focus at all`() {
        val plan = focusPlanFor(AudioCoexistence.Mix)

        assertFalse(
            plan.requestsFocus,
            "Mix must hold no focus request, so it neither pauses others nor is told to stop",
        )
    }

    @Test
    fun `duck requests transient focus that may duck`() {
        val plan = focusPlanFor(AudioCoexistence.Duck)

        assertTrue(plan.requestsFocus)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, plan.focusGain)
    }

    /**
     * The regression this refactor exists for. `VoiceCommunication` used to be
     * checked before coexistence and mapped to `AUDIOFOCUS_GAIN_TRANSIENT`, so an
     * ongoing communication session asked for transient focus, and a
     * `VoiceCommunication` + `Duck` config never reached the duck branch at all.
     */
    @Test
    fun `focus is decided by coexistence alone and never by the mode`() {
        AudioSessionMode.entries.forEach { mode ->
            AudioCoexistence.entries.forEach { coexistence ->
                assertEquals(
                    focusPlanFor(coexistence),
                    focusPlanFor(AudioSessionConfig(mode, coexistence).coexistence),
                    "$mode must not influence the focus plan for $coexistence",
                )
            }
        }
    }

    @Test
    fun `voice communication with duck still requests a ducking gain`() {
        val config = AudioSessionConfig(AudioSessionMode.VoiceCommunication, AudioCoexistence.Duck)

        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            focusPlanFor(config.coexistence).focusGain,
        )
    }

    @Test
    fun `voice communication with exclusive requests ordinary and not transient focus`() {
        val config = AudioSessionConfig(AudioSessionMode.VoiceCommunication)

        assertEquals(AudioManager.AUDIOFOCUS_GAIN, focusPlanFor(config.coexistence).focusGain)
    }

    // ── Mode → attributes ─────────────────────────────────────────────────────

    @Test
    fun `only voice communication uses the voice usage`() {
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, usageFor(AudioSessionMode.Music))
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, usageFor(AudioSessionMode.Speech))
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, usageFor(AudioSessionMode.Movie))
        assertEquals(
            android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION,
            usageFor(AudioSessionMode.VoiceCommunication),
        )
    }

    @Test
    fun `each mode maps to its own content type`() {
        assertEquals(
            android.media.AudioAttributes.CONTENT_TYPE_MUSIC,
            contentTypeFor(AudioSessionMode.Music),
        )
        assertEquals(
            android.media.AudioAttributes.CONTENT_TYPE_SPEECH,
            contentTypeFor(AudioSessionMode.Speech),
        )
        assertEquals(
            android.media.AudioAttributes.CONTENT_TYPE_MOVIE,
            contentTypeFor(AudioSessionMode.Movie),
        )
        assertEquals(
            android.media.AudioAttributes.CONTENT_TYPE_SPEECH,
            contentTypeFor(AudioSessionMode.VoiceCommunication),
        )
    }

    // ── Focus callbacks → interruptions ───────────────────────────────────────

    @Test
    fun `a plain loss begins an interruption`() {
        val translator = FocusChangeTranslator()

        assertEquals(
            listOf(AudioInterruption.Began),
            translator.translate(AudioManager.AUDIOFOCUS_LOSS),
        )
    }

    @Test
    fun `a gain with no duck in progress ends the interruption`() {
        val translator = FocusChangeTranslator()
        translator.translate(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(
            listOf(AudioInterruption.Ended(systemAllowsResume = true)),
            translator.translate(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `can-duck begins a duck`() {
        val translator = FocusChangeTranslator()

        assertEquals(
            listOf(AudioInterruption.DuckBegan),
            translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        assertTrue(translator.isDucked)
    }

    @Test
    fun `duck then gain ends the duck rather than the interruption`() {
        val translator = FocusChangeTranslator()
        translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(
            listOf(AudioInterruption.DuckEnded),
            translator.translate(AudioManager.AUDIOFOCUS_GAIN),
        )
        assertFalse(translator.isDucked)
    }

    @Test
    fun `duck then loss restores the volume before pausing`() {
        val translator = FocusChangeTranslator()
        translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        // Order is the assertion: DuckEnded first, so the handler puts the volume
        // back before the pause. Reversed, playback would resume quiet later on.
        assertEquals(
            listOf(AudioInterruption.DuckEnded, AudioInterruption.Began),
            translator.translate(AudioManager.AUDIOFOCUS_LOSS),
        )
        assertFalse(translator.isDucked)
    }

    @Test
    fun `duck then transient loss also restores the volume before pausing`() {
        val translator = FocusChangeTranslator()
        translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(
            listOf(AudioInterruption.DuckEnded, AudioInterruption.Began),
            translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun `a repeated can-duck is absorbed`() {
        val translator = FocusChangeTranslator()
        translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        // A second DuckBegan would have the handler capture the already-lowered
        // volume as the level to restore to, so the duck could never be undone.
        assertEquals(
            emptyList(),
            translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    @Test
    fun `abandoning focus while ducked closes the duck`() {
        val translator = FocusChangeTranslator()
        translator.translate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(listOf(AudioInterruption.DuckEnded), translator.endDuck())
        assertFalse(translator.isDucked)
    }

    @Test
    fun `abandoning focus while not ducked emits nothing`() {
        assertEquals(emptyList(), FocusChangeTranslator().endDuck())
    }
}
