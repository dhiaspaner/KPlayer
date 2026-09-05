package kplayer.videoplayer

import androidx.media3.common.C
import kplayer.core.audio.AudioSessionMode
import androidx.media3.common.AudioAttributes as ExoPlayerAudioAttributes

/**
 * Maps [AudioSessionMode] to the ExoPlayer audio attributes applied to the
 * actual output stream.
 *
 * The focus-arbitration counterpart (`android.media.AudioAttributes`, used by
 * `AndroidAudioSession`) lives in the `:audio` module as
 * `kplayer.core.audio.platformAudioAttributesFor`. The two must agree about
 * what kind of content is playing — [AudioSessionMode] is the shared contract
 * that keeps them in step, so a new mode must be handled in both places.
 */
private fun exoUsageFor(mode: AudioSessionMode): Int =
    when (mode) {
        AudioSessionMode.VoiceCommunication -> C.USAGE_VOICE_COMMUNICATION
        else -> C.USAGE_MEDIA
    }

private fun exoContentTypeFor(mode: AudioSessionMode): Int =
    when (mode) {
        AudioSessionMode.Music -> C.AUDIO_CONTENT_TYPE_MUSIC
        AudioSessionMode.Speech -> C.AUDIO_CONTENT_TYPE_SPEECH
        AudioSessionMode.Movie -> C.AUDIO_CONTENT_TYPE_MOVIE
        AudioSessionMode.VoiceCommunication -> C.AUDIO_CONTENT_TYPE_SPEECH
    }

internal fun exoPlayerAudioAttributesFor(mode: AudioSessionMode): ExoPlayerAudioAttributes =
    ExoPlayerAudioAttributes.Builder()
        .setUsage(exoUsageFor(mode))
        .setContentType(exoContentTypeFor(mode))
        .build()
