package kplayer.core.state

import kplayer.core.state.PlaybackError
import kplayer.core.state.toDisplayMessage


/**
 * Public, platform-agnostic playback state exposed to shared code.
 */
interface PlaybackState {
    val status: PlaybackStatus
    val playWhenReady: Boolean
    val positionMs: Long
    val durationMs: Long
    val bufferedPositionMs: Long
    val playbackSpeed: Float

    /** Output volume in [0, 1]. Lowered transiently while audio is ducked. */
    val volume: Float

    val source: MediaSource?

    /**
     * Why playback failed, or `null` when it has not. Structured rather than a
     * string so a caller can react to the *kind* of failure — offer "retry" for a
     * dropped connection and "this file cannot be played" for a codec it will never
     * decode — instead of pattern-matching prose.
     */
    val error: PlaybackError?

    /**
     * [error] rendered for display, for the common case of a UI that only wants
     * something to put on screen. Derived, so implementations carry [error] alone.
     */
    val errorMessage: String? get() = error?.toDisplayMessage()
}