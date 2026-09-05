package kplayer.core.state

import kplayer.core.MediaPlayer


/**
 * Read-side conveniences over the engine's [PlaybackState].
 *
 * The UI consumes the engine's own state object directly — there is no flattened
 * copy in between. That removes a projection layer that could go stale, and the
 * derived values a control template actually wants are cheap enough to compute
 * at read time. They live here rather than on [PlaybackState] so `:core` stays
 * free of presentation concerns.
 *
 * `:ui` uses these directly rather than defining its own — a second definition
 * of "is it playing" is a second thing to keep in step with [PlaybackStatus].
 */

/** True only while frames are actually advancing. */
val PlaybackState.isPlaying: Boolean
    get() = status == PlaybackStatus.Playing

/**
 * The same question asked of a player rather than a state snapshot.
 *
 * An extension rather than a member of [MediaPlayer] so there is exactly one
 * definition of "playing" in the library, expressed once above: an
 * implementation cannot override this into disagreeing with its own
 * [PlaybackState].
 */
val MediaPlayer<*, *>.isPlaying: Boolean
    get() = state.value.isPlaying

/** Covers both the initial load and a mid-playback rebuffer. */
val PlaybackState.isBuffering: Boolean
    get() = status == PlaybackStatus.Buffering

/** True once the engine has reported a failure; [PlaybackState.errorMessage] has the detail. */
val PlaybackState.hasError: Boolean
    get() = status == PlaybackStatus.Error

/** True once the engine knows how long the media is — before that, seeking is meaningless. */
val PlaybackState.isSeekable: Boolean
    get() = durationMs > 0L

/** Playback progress in `0f..1f`; `0f` while the duration is still unknown. */
val PlaybackState.progress: Float
    get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** Buffered fraction in `0f..1f`, for a secondary seek-bar track. */
val PlaybackState.bufferedProgress: Float
    get() = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
