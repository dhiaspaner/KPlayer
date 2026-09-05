package kplayer.engine.dsl

import kplayer.core.audio.AudioCoexistence
import kplayer.core.audio.AudioSessionConfig
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.AudioFocusPolicy

/**
 * Derives the native-facing [AudioSessionConfig] from an app-facing [AudioFocusPolicy].
 *
 * This is the ONLY place `AudioFocusPolicy` (interruption) and `AudioSessionConfig`
 * (audio.core) are allowed to appear in the same file. `audio.core` and
 * `interruption` must never import each other directly — this pure function is
 * the seam between them, called once at composition time by [KMediaManagerBuilder].
 *
 * Exhaustive `when`, no `else` branch: adding a new [AudioFocusPolicy] case must
 * fail to compile here until this mapping is updated deliberately.
 */
internal fun AudioFocusPolicy.toAudioSessionConfig(mode: AudioSessionMode): AudioSessionConfig =
    when (this) {
        // Never reacts to focus, so coexist instead of competing for it.
        AudioFocusPolicy.Ignore -> AudioSessionConfig(mode, AudioCoexistence.Mix)
        // All exclusive playback requests; resume behavior is decided post-hoc, not at request time.
        AudioFocusPolicy.RestoreIfPlayingBefore -> AudioSessionConfig(mode, AudioCoexistence.Exclusive)
        AudioFocusPolicy.AlwaysResume -> AudioSessionConfig(mode, AudioCoexistence.Exclusive)
        AudioFocusPolicy.PauseAndStayPaused -> AudioSessionConfig(mode, AudioCoexistence.Exclusive)
    }
