package kplayer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kplayer.core.MediaPlayer
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.core.state.PlaybackState
import kplayer.core.state.PlaybackStatus
import kplayer.core.state.PlayerState

/** Minimal concrete [PlaybackState] for tests — `:core` owns no player implementation. */
data class FakePlaybackState(
    override val status: PlaybackStatus = PlaybackStatus.Idle,
    override val playWhenReady: Boolean = true,
    override val positionMs: Long = 0L,
    override val durationMs: Long = 0L,
    override val bufferedPositionMs: Long = 0L,
    override val playbackSpeed: Float = 1f,
    override val volume: Float = 1f,
    override val error: PlaybackError? = null,
    override val source: MediaSource? = null,
) : PlayerState<FakePlaybackState> {

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

/**
 * Test double for [kplayer.core.MediaPlayer] that applies status transitions directly.
 *
 * Deliberately does *not* use a real state machine: the interruption engine under
 * test only ever observes `status` and `volume` and calls `play()` / `pause()` /
 * `setVolume()`, so binding these tests to a particular backend's state machine
 * (which lives in `:video`) would couple `:core`'s tests to a module above it.
 *
 * Usage:
 * ```
 * val player = FakePlayer()
 * player.loadAndPlay()                         // → Playing
 * handler.onEvent(InterruptionEvent.Began(InterruptionCause.AppBackgrounded))
 * assertEquals(PlaybackStatus.Paused, player.state.value.status)
 * ```
 */
class FakePlayer : MediaPlayer<MediaSource, FakePlaybackState> {

    var playCallCount = 0
        private set
    var pauseCallCount = 0
        private set
    var setVolumeCallCount = 0
        private set

    private val _state = MutableStateFlow(FakePlaybackState())
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    /** Report a fact, the way a real backend does from a native callback. */
    fun emitEvent(event: PlaybackEvent) {
        _events.tryEmit(event)
    }

    // ── MediaPlayer ───────────────────────────────────────────────────────────

    /** Mirrors a real backend: reaching Ready auto-starts when `playWhenReady`. */
    override fun load(source: MediaSource) {
        _state.update {
            it.copy(
                source = source,
                status = if (it.playWhenReady) PlaybackStatus.Playing else PlaybackStatus.Ready,
            )
        }
    }

    override fun play() {
        playCallCount++
        _state.update { it.copy(status = PlaybackStatus.Playing) }
    }

    override fun pause() {
        pauseCallCount++
        _state.update { it.copy(status = PlaybackStatus.Paused) }
    }

    override fun stop() {
        _state.update { it.copy(status = PlaybackStatus.Stopped, positionMs = 0L) }
    }

    override fun release() {
        _state.update { it.copy(status = PlaybackStatus.Released) }
    }

    override fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        _state.update { it.copy(playbackSpeed = speed) }
    }

    override fun setVolume(volume: Float) {
        setVolumeCallCount++
        _state.update { it.copy(volume = volume) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Loads a source and immediately reaches [PlaybackStatus.Playing]. */
    fun loadAndPlay(
        source: MediaSource = MediaSource.Url("test.mp4"),
        durationMs: Long = 60_000L,
    ) {
        playCallCount++
        _state.update {
            it.copy(source = source, durationMs = durationMs, status = PlaybackStatus.Playing)
        }
    }

    /**
     * A pause the library did not ask for — what `AVPlayer` does to itself when
     * the output route disappears. Deliberately not [pause]: the whole point of
     * the tests using this is that no call was made.
     */
    fun platformPause() {
        _state.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /**
     * Stops at [PlaybackStatus.Buffering] — a source that is still loading, so
     * an interruption arriving now finds nothing to pause.
     */
    fun loadAndBuffer(
        source: MediaSource = MediaSource.Url("test.mp4"),
    ) {
        _state.update { it.copy(source = source, status = PlaybackStatus.Buffering) }
    }

    /** Reaches [PlaybackStatus.Paused] — useful as a precondition for resume tests. */
    fun loadAndPause(
        source: MediaSource = MediaSource.Url("test.mp4"),
        durationMs: Long = 60_000L,
    ) {
        loadAndPlay(source, durationMs)
        // Not pause(): this is the starting condition, not a pause under test,
        // so it must not count towards pauseCallCount.
        _state.update { it.copy(status = PlaybackStatus.Paused) }
    }
}
