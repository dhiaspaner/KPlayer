package kplayer.audioplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource

/**
 * Builds a fully-wired audio player: the platform backend (ExoPlayer on Android,
 * `AVPlayer` on iOS) wrapped in a `KMediaManager` that owns the audio session,
 * the interruption handler and the system observers.
 *
 * The default [interruptionConfig] is [InterruptionConfig.MediaPlayerDefault]
 * rather than video's `StrictManualResume`: a music or podcast listener expects
 * playback to come back on its own after a phone call, where a viewer generally
 * does not.
 *
 * @param audioSessionMode the *kind* of content, which decides the native audio
 *   category and focus attributes — [AudioSessionMode.Speech] for podcasts and
 *   audiobooks, [AudioSessionMode.Music] for music.
 */
expect fun AudioPlayer(
    interruptionConfig: StateFlow<InterruptionConfig> = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
    audioSessionMode: AudioSessionMode = AudioSessionMode.Music,
): MediaPlayer<MediaSource, AudioPlayerState>
