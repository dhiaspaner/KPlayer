package kplayer.audioplayer

import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.core.state.PlaybackStatus
import kplayer.core.state.PlayerState

/**
 * The single source of truth an audio player exposes to callers.
 *
 * Carries only the fields every player has: `VideoPlayerState` adds
 * `activeSubtitle` because video has a surface to draw cues on, and audio has no
 * equivalent. Kept as its own type rather than shared with video so the two can
 * diverge — playlists, gapless, metadata — without either medium inheriting the
 * other's concerns.
 */
data class AudioPlayerState(
    override val status: PlaybackStatus = PlaybackStatus.Idle,
    override val playWhenReady: Boolean = true,
    override val positionMs: Long = 0L,
    override val durationMs: Long = 0L,
    override val bufferedPositionMs: Long = 0L,
    override val playbackSpeed: Float = 1f,
    override val volume: Float = 1f,
    override val error: PlaybackError? = null,
    override val source: MediaSource? = null,
) : PlayerState<AudioPlayerState> {

    /** Forwards to [copy]; defaults live on [PlayerState.copyBase]. */
    override fun copyBase(
        status: PlaybackStatus,
        playWhenReady: Boolean,
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long,
        playbackSpeed: Float,
        volume: Float,
        error: PlaybackError?,
        source: MediaSource?,
    ) = copy(
        status = status,
        playWhenReady = playWhenReady,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        playbackSpeed = playbackSpeed,
        volume = volume,
        error = error,
        source = source,
    )
}
