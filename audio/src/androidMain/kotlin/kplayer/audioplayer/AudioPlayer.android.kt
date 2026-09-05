package kplayer.audioplayer

import kotlinx.coroutines.flow.StateFlow
import kplayer.AndroidAudioPlayer
import kplayer.core.MediaPlayer
import kplayer.appContext
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource

actual fun AudioPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode,
): MediaPlayer<MediaSource, AudioPlayerState> = MediaPlayer {

    interruptionConfig(interruptionConfig)

    player {
        AndroidAudioPlayer(appContext, audioSessionMode)
    }

    mode(audioSessionMode)
}
