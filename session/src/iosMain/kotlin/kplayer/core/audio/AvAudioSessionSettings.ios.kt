package kplayer.core.audio

import platform.AVFAudio.AVAudioSessionCategory
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryOptions
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionMode
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionModeVoiceChat

/**
 * Everything `AVAudioSession.setCategory` needs, as one value.
 *
 * The whole point of naming it is testability: [toAvSettings] is a pure function
 * from an [AudioSessionConfig] to this, so every mode/coexistence combination can
 * be asserted without a session, a device, or an activation that would fail in a
 * test process.
 */
internal data class AvAudioSessionSettings(
    val category: AVAudioSessionCategory,
    val mode: AVAudioSessionMode,
    val options: AVAudioSessionCategoryOptions,
)

/**
 * The three axes map independently and then combine — no nested conditionals,
 * and no axis reading another's value except where the platform forbids a
 * combination outright (see [optionsFor]).
 */
internal fun AudioSessionConfig.toAvSettings(): AvAudioSessionSettings {
    val category = categoryFor(mode)
    return AvAudioSessionSettings(
        category = category,
        mode = avModeFor(mode),
        options = optionsFor(coexistence, output, category),
    )
}

/**
 * Only [AudioSessionMode.VoiceCommunication] opens an input, and only it needs
 * `.playAndRecord`; everything else is playback-only and must stay that way —
 * `.playAndRecord` costs a microphone permission prompt and routes to the
 * receiver by default.
 */
private fun categoryFor(mode: AudioSessionMode): AVAudioSessionCategory =
    when (mode) {
        AudioSessionMode.Music,
        AudioSessionMode.Speech,
        AudioSessionMode.Movie,
        -> AVAudioSessionCategoryPlayback

        AudioSessionMode.VoiceCommunication -> AVAudioSessionCategoryPlayAndRecord
    }

private fun avModeFor(mode: AudioSessionMode): AVAudioSessionMode =
    when (mode) {
        AudioSessionMode.Music -> AVAudioSessionModeDefault
        AudioSessionMode.Speech -> AVAudioSessionModeSpokenAudio
        AudioSessionMode.Movie -> AVAudioSessionModeMoviePlayback
        AudioSessionMode.VoiceCommunication -> AVAudioSessionModeVoiceChat
    }

/**
 * Coexistence and routing are independent bits, OR-ed together.
 *
 * `MixWithOthers` also stops iOS posting an interruption when another app
 * starts, which is what "don't arbitrate focus" should mean on this platform.
 *
 * `DefaultToSpeaker` is gated on `.playAndRecord` because iOS rejects
 * `setCategory` outright with it on any other category — the whole call fails, so
 * the session would end up with no configuration at all rather than with one
 * ignored option. Gating on the *category* rather than on the mode keeps the two
 * in step if the category mapping ever changes.
 */
private fun optionsFor(
    coexistence: AudioCoexistence,
    output: AudioOutputPreference,
    category: AVAudioSessionCategory,
): AVAudioSessionCategoryOptions {
    val coexistenceOption = when (coexistence) {
        AudioCoexistence.Exclusive -> 0uL
        AudioCoexistence.Mix -> AVAudioSessionCategoryOptionMixWithOthers
        AudioCoexistence.Duck -> AVAudioSessionCategoryOptionDuckOthers
    }

    val routingOption = when (output) {
        AudioOutputPreference.System -> 0uL
        AudioOutputPreference.Speaker ->
            if (category == AVAudioSessionCategoryPlayAndRecord) {
                AVAudioSessionCategoryOptionDefaultToSpeaker
            } else {
                0uL
            }
    }

    return coexistenceOption or routingOption
}
