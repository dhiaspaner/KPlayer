package kplayer.core.audio

import android.media.AudioAttributes as PlatformAudioAttributes

/**
 * Android mapping from [AudioSessionMode] to `android.media` audio attributes,
 * used for focus arbitration by [AndroidAudioSession].
 *
 * The matching ExoPlayer-side mapping (`androidx.media3.common.AudioAttributes`,
 * applied to the actual output stream) lives in the `:video` module as
 * `kplayer.videoplayer.exoPlayerAudioAttributesFor`. The two must agree about
 * what kind of content is playing — [AudioSessionMode] is the shared contract
 * that keeps them in step, so a new mode must be handled in both places.
 */
internal fun usageFor(mode: AudioSessionMode): Int =
    when (mode) {
        AudioSessionMode.VoiceCommunication -> PlatformAudioAttributes.USAGE_VOICE_COMMUNICATION
        else -> PlatformAudioAttributes.USAGE_MEDIA
    }

internal fun contentTypeFor(mode: AudioSessionMode): Int =
    when (mode) {
        AudioSessionMode.Music -> PlatformAudioAttributes.CONTENT_TYPE_MUSIC
        AudioSessionMode.Speech -> PlatformAudioAttributes.CONTENT_TYPE_SPEECH
        AudioSessionMode.Movie -> PlatformAudioAttributes.CONTENT_TYPE_MOVIE
        AudioSessionMode.VoiceCommunication -> PlatformAudioAttributes.CONTENT_TYPE_SPEECH
    }

internal fun platformAudioAttributesFor(mode: AudioSessionMode): PlatformAudioAttributes =
    PlatformAudioAttributes.Builder()
        .setUsage(usageFor(mode))
        .setContentType(contentTypeFor(mode))
        .build()
