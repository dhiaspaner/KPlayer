package kplayer.videoplayer

import kotlinx.coroutines.flow.StateFlow
import kplayer.IosVideoPlayer
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource

actual fun VideoPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode
): MediaPlayer<MediaSource, VideoPlayerState> = MediaPlayer {
    player {
        // AVAudioSession is a process-wide singleton: the category/mode
        // IosAudioSession applies from `audioFocusConfig` on play() already
        // governs this player's actual output — no per-instance wiring needed.
        IosVideoPlayer()
    }

    interruptionConfig(interruptionConfig)
    mode(audioSessionMode)
}