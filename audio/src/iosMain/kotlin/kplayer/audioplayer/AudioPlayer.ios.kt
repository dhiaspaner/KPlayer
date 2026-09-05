package kplayer.audioplayer

import kotlinx.coroutines.flow.StateFlow
import kplayer.IosAudioPlayer
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource

actual fun AudioPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode,
): MediaPlayer<MediaSource, AudioPlayerState> = MediaPlayer {

    player {
        // AVAudioSession is a process-wide singleton: the category/mode
        // IosAudioSession applies on acquire() already governs this player's
        // actual output — no per-instance wiring needed.
        IosAudioPlayer()
    }

    interruptionConfig(interruptionConfig)
    mode(audioSessionMode)
}
