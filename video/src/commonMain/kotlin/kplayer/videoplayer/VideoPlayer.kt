package kplayer.videoplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource


expect fun VideoPlayer(
    interruptionConfig: StateFlow<InterruptionConfig> = MutableStateFlow(InterruptionConfig.StrictManualResume),
    audioSessionMode: AudioSessionMode
): MediaPlayer<MediaSource, VideoPlayerState>
