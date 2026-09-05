package kplayer.core.state

import kplayer.core.state.PlaybackError

/**
 * A [PlaybackState] a shared state machine can update.
 *
 * `PlaybackStateMachine`'s core operation is "the same state, with a new status" —
 * which on a `data class` is `copy()`, and `copy()` is not polymorphic. Without a
 * way to express it, every backend has to carry its own copy of the machine; that
 * is exactly what `:video` and `:audio` used to do.
 *
 * [Self] is the implementing type, so [copyBase] returns the caller's own state
 * type rather than a widened one. Kotlin has no self types, so the recursion is
 * declared by hand: `data class VideoPlayerState : PlayerState<VideoPlayerState>`.
 *
 * Deliberately a **separate** interface rather than a change to [PlaybackState]:
 * `MediaPlayer<S, T : PlaybackState>`, `KMediaManager` and `KMediaManagerBuilder`
 * keep their existing generics, and consumers that only read state — the whole
 * `:ui` module — never see this type.
 */
interface PlayerState<Self : PlayerState<Self>> : PlaybackState {

    /**
     * Copy of the fields the shared state machine owns. Implementations forward to
     * their own `copy()`, which is why their flat `data class` shape survives.
     *
     * Defaults live here and cannot be restated by an override — Kotlin inherits
     * them. Passing an argument explicitly is distinguishable from omitting it, so
     * `copyBase(error = null)` clears the error rather than reading as
     * "unchanged"; the `Buffering` node relies on that when a new source loads.
     */
    fun copyBase(
        status: PlaybackStatus = this.status,
        playWhenReady: Boolean = this.playWhenReady,
        positionMs: Long = this.positionMs,
        durationMs: Long = this.durationMs,
        bufferedPositionMs: Long = this.bufferedPositionMs,
        playbackSpeed: Float = this.playbackSpeed,
        volume: Float = this.volume,
        error: PlaybackError? = this.error,
        source: MediaSource? = this.source,
    ): Self
}
