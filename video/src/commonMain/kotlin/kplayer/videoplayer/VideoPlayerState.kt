package kplayer.videoplayer

import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.core.state.PlaybackStatus
import kplayer.core.state.PlayerState

/**
 * The single source of truth a video player exposes to the UI.
 *
 * Identical to `AudioPlayerState` apart from [activeSubtitle] — the flat shape is
 * load-bearing, since `:ui`'s previews and `FakeVideoPlayer` construct and `copy()`
 * this directly.
 */
data class VideoPlayerState(
    override val status: PlaybackStatus = PlaybackStatus.Idle,
    override val playWhenReady: Boolean = true,
    override val positionMs: Long = 0L,
    override val durationMs: Long = 0L,
    override val bufferedPositionMs: Long = 0L,
    override val playbackSpeed: Float = 1f,
    override val volume: Float = 1f,
    override val error: PlaybackError? = null,
    override val source: MediaSource? = null,
    val activeSubtitle: String? = null,
) : PlayerState<VideoPlayerState> {

    /**
     * Forwards to [copy]; defaults live on [PlayerState.copyBase].
     *
     * [activeSubtitle] is absent on purpose — the shared state machine does not
     * know about it, and reaches it through the `reduceCustom` / `onLoad` hooks
     * `AbstractVideoPlayer` supplies instead.
     */
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
