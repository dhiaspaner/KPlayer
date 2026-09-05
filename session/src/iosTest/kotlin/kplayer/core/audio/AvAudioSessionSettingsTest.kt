package kplayer.core.audio

import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS half of the mapping, asserted as pure data.
 *
 * No `AVAudioSession` is configured or activated here — activation in a test
 * process is unreliable and would say nothing about whether the *mapping* is
 * right. That is the reason [AvAudioSessionSettings] exists as a value.
 */
class AvAudioSessionSettingsTest {

    private fun settings(
        mode: AudioSessionMode,
        coexistence: AudioCoexistence = AudioCoexistence.Exclusive,
        output: AudioOutputPreference = AudioOutputPreference.System,
    ) = AudioSessionConfig(mode, coexistence, output).toAvSettings()

    // ── Mode → category + mode ────────────────────────────────────────────────

    @Test
    fun `music is playback with the default mode`() {
        val result = settings(AudioSessionMode.Music)

        assertEquals(AVAudioSessionCategoryPlayback, result.category)
        assertEquals(AVAudioSessionModeDefault, result.mode)
    }

    @Test
    fun `speech is playback with the spoken audio mode`() {
        val result = settings(AudioSessionMode.Speech)

        assertEquals(AVAudioSessionCategoryPlayback, result.category)
        assertEquals(AVAudioSessionModeSpokenAudio, result.mode)
    }

    @Test
    fun `movie is playback with the movie playback mode`() {
        val result = settings(AudioSessionMode.Movie)

        assertEquals(AVAudioSessionCategoryPlayback, result.category)
        assertEquals(AVAudioSessionModeMoviePlayback, result.mode)
    }

    @Test
    fun `voice communication is play and record with the voice chat mode`() {
        val result = settings(AudioSessionMode.VoiceCommunication)

        assertEquals(AVAudioSessionCategoryPlayAndRecord, result.category)
        assertEquals(AVAudioSessionModeVoiceChat, result.mode)
    }

    // ── Coexistence → options ─────────────────────────────────────────────────

    @Test
    fun `music with exclusive sets no coexistence option`() {
        assertEquals(0uL, settings(AudioSessionMode.Music, AudioCoexistence.Exclusive).options)
    }

    @Test
    fun `music with mix sets mix with others`() {
        assertEquals(
            AVAudioSessionCategoryOptionMixWithOthers,
            settings(AudioSessionMode.Music, AudioCoexistence.Mix).options,
        )
    }

    @Test
    fun `music with duck sets duck others`() {
        assertEquals(
            AVAudioSessionCategoryOptionDuckOthers,
            settings(AudioSessionMode.Music, AudioCoexistence.Duck).options,
        )
    }

    /**
     * The axes are independent: changing coexistence must never move the
     * category or the mode, and vice versa.
     */
    @Test
    fun `coexistence never changes the category or the mode`() {
        AudioSessionMode.entries.forEach { mode ->
            val baseline = settings(mode, AudioCoexistence.Exclusive)
            AudioCoexistence.entries.forEach { coexistence ->
                val result = settings(mode, coexistence)
                assertEquals(baseline.category, result.category, "$mode / $coexistence")
                assertEquals(baseline.mode, result.mode, "$mode / $coexistence")
            }
        }
    }

    @Test
    fun `the mode never contributes a coexistence option`() {
        AudioSessionMode.entries.forEach { mode ->
            assertEquals(
                AVAudioSessionCategoryOptionDuckOthers,
                settings(mode, AudioCoexistence.Duck).options,
                "$mode with Duck must set exactly DuckOthers and nothing else",
            )
        }
    }

    // ── Output → routing option ───────────────────────────────────────────────

    @Test
    fun `voice communication routed to the speaker sets default to speaker`() {
        val result = settings(
            AudioSessionMode.VoiceCommunication,
            output = AudioOutputPreference.Speaker,
        )

        assertEquals(AVAudioSessionCategoryOptionDefaultToSpeaker, result.options)
    }

    @Test
    fun `voice communication combines the routing and coexistence options`() {
        val result = settings(
            AudioSessionMode.VoiceCommunication,
            coexistence = AudioCoexistence.Duck,
            output = AudioOutputPreference.Speaker,
        )

        assertEquals(
            AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionDefaultToSpeaker,
            result.options,
        )
    }

    /**
     * iOS rejects `setCategory` outright when `.defaultToSpeaker` accompanies
     * anything but `.playAndRecord` — the call fails and the session keeps
     * whatever configuration it had. Dropping the option is the only safe answer.
     */
    @Test
    fun `speaker is dropped for the playback-only modes`() {
        listOf(AudioSessionMode.Music, AudioSessionMode.Speech, AudioSessionMode.Movie)
            .forEach { mode ->
                assertEquals(
                    0uL,
                    settings(mode, output = AudioOutputPreference.Speaker).options,
                    "$mode is playback-only; DefaultToSpeaker would make setCategory fail",
                )
            }
    }

    @Test
    fun `the default output adds nothing`() {
        AudioSessionMode.entries.forEach { mode ->
            assertEquals(
                settings(mode, output = AudioOutputPreference.System).options,
                AudioSessionConfig(mode).toAvSettings().options,
                "$mode: System must be the default and must add no option",
            )
        }
    }
}
