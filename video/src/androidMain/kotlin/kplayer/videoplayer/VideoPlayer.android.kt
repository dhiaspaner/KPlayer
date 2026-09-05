package kplayer.videoplayer

import kotlinx.coroutines.flow.StateFlow
import kplayer.AndroidVideoPlayer
import kplayer.core.MediaPlayer
import kplayer.appContext
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource

actual fun VideoPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode
): MediaPlayer<MediaSource, VideoPlayerState> = MediaPlayer {

    interruptionConfig(interruptionConfig)

    player {
        AndroidVideoPlayer(appContext)
    }

    mode(audioSessionMode)
}