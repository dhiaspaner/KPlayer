package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kplayer.ui.model.VideoScalingMode
import kplayer.core.event.PlaybackAction
import kplayer.core.state.PlaybackState
import kplayer.core.state.isPlaying

/**
 * The player's state holder — everything a control needs to read, and every way
 * it can act, behind one object.
 *
 * Shaped like `LazyListState` or `PagerState`: a `@Stable` class the caller
 * creates with [rememberPlayerState], holds across recompositions, and can hoist
 * to drive the player from outside. Properties are read-only from the outside
 * and updated internally; everything that changes something is a function.
 *
 * It knows nothing about any player implementation. It holds an immutable
 * [PlaybackState] and forwards [PlaybackAction]s to a lambda, so the backend
 * behind it may be ExoPlayer, AVPlayer, a Cast session, or a `when` block in a
 * test. That is also why it is directly constructible: staging a control gallery
 * or a screenshot test needs a state literal and an action handler, not a fake
 * engine.
 *
 * ```kotlin
 * val state = rememberPlayerState(
 *     playbackState = uiState.playback,
 *     onPlaybackAction = viewModel::onPlaybackAction,
 * )
 *
 * FlexibleVideoPlayer(state = state) {
 *     // the slot's receiver is this PlayerState; the controls take it as a
 *     // parameter, so capture it before entering any layout scope
 *     val state = this
 *     PlayPauseButton(state)
 *     TimeSeekBar(state)
 * }
 * ```
 *
 * It draws nothing itself. The built-in controls live beside it in
 * `PlayerControls.kt` as ordinary composables that take one of these as a
 * parameter, so a hand-written control is written exactly the way they are —
 * see that file.
 *
 * Carries no `BoxScope` — it is plain data, constructible outside any particular
 * layout. A template that wants `Modifier.align(...)` opens its own `Box`, the
 * same way any other Compose layout code would.
 */
@Stable
class PlayerState<S : PlaybackState>(
    playbackState: S,
    /** Hoisted presentation state: scaling, controls visibility, fullscreen. */
    val uiState: PlayerUiStateHolder,
    /** The seek bar's in-flight drag; see [SeekInteractionState]. */
    val seekInteractionState: SeekInteractionState,
    onPlaybackAction: (PlaybackAction) -> Unit,
) {

    // Snapshot-backed, so a template reading `playbackState` is subscribed to it
    // and recomposes on the next engine emission. A plain field would leave the
    // controls frozen at whatever the first composition saw.
    private var _playbackState by mutableStateOf(playbackState)

    /** Latest engine state. Read-only here; the owner pushes updates in. */
    val playbackState: S get() = _playbackState

    // Kept mutable so rememberPlayerState can refresh it. Without that, a caller
    // passing a lambda literal would be bound forever to the one captured on
    // first composition — actions would silently go to a stale receiver.
    private var actionHandler: (PlaybackAction) -> Unit = onPlaybackAction

    /**
     * Publishes new engine state.
     *
     * Called for you by [rememberPlayerState] on every recomposition; call it
     * directly only when driving a [PlayerState] you built yourself.
     */
    fun updatePlaybackState(state: S) {
        _playbackState = state
    }

    internal fun updateActionHandler(onPlaybackAction: (PlaybackAction) -> Unit) {
        actionHandler = onPlaybackAction
    }

    /**
     * The one way to command the engine.
     *
     * Every control routes through here, so wrapping the handler passed to
     * [rememberPlayerState] intercepts all of them at once — analytics, logging,
     * a confirmation gate, a remote transport.
     */
    fun dispatch(action: PlaybackAction) = actionHandler(action)

    /**
     * The position controls should render: the finger's target while scrubbing,
     * the seek target while one is in flight, the engine's position otherwise.
     *
     * Always prefer this to `playbackState.positionMs` in a template — it is
     * what keeps a time label and a seek bar agreeing mid-drag.
     */
    val displayPositionMs: Long
        get() = seekInteractionState.displayPosition(playbackState.positionMs)

    /** True while the user holds the seek bar thumb. */
    val isScrubbing: Boolean
        get() = seekInteractionState.isScrubbing

    // ── Engine commands ───────────────────────────────────────────────────────

    /**
     * Plays when paused, pauses when playing.
     *
     * Resolved here rather than as a [PlaybackAction] variant because the engine
     * has no toggle — and adding one would force both platform backends to
     * handle a command that is really a UI affordance.
     */
    fun playPause() = if (playbackState.isPlaying) pause() else play()

    fun play() = dispatch(PlaybackAction.Play)

    fun pause() = dispatch(PlaybackAction.Pause)

    fun seekTo(positionMs: Long) = dispatch(PlaybackAction.SeekTo(positionMs))

    /** Seeks relative to what the user currently sees, clamped to the media. */
    fun seekBy(deltaMs: Long) =
        seekTo((displayPositionMs + deltaMs).coerceIn(0L, playbackState.durationMs.coerceAtLeast(0L)))

    fun setVolume(volume: Float) = dispatch(PlaybackAction.SetVolume(volume.coerceIn(0f, 1f)))

    fun setPlaybackSpeed(speed: Float) = dispatch(PlaybackAction.SetPlaybackSpeed(speed))

    fun stop() = dispatch(PlaybackAction.Stop)

    // ── UI-only commands ──────────────────────────────────────────────────────
    // These never reach the engine; they are pure presentation, so they go
    // straight to the hoisted holder rather than round-tripping through an
    // action both backends would have to ignore.

    fun toggleControls() = uiState.toggleControls()

    fun showControls() = uiState.showControls()

    fun hideControls() = uiState.hideControls()

    fun cycleScalingMode() = uiState.cycleScalingMode()

    fun setScalingMode(mode: VideoScalingMode) = uiState.setScalingMode(mode)

    fun toggleFullscreen() = uiState.toggleFullscreen()
}

/**
 * Creates a [PlayerState] that survives recomposition, refreshing it with the
 * latest [playbackState] and [onPlaybackAction] each time.
 *
 * The same instance is kept for the life of the composition — it is a state
 * holder, not a value — so hoisting it and passing it to `FlexibleVideoPlayer`
 * lets a surrounding screen read and drive the player directly.
 *
 * @param uiStateHolder pass your own to drive the chrome from outside the
 *   player, e.g. a top bar with its own fullscreen button.
 * @param seekInteractionState pass your own to observe [SeekInteractionState.isScrubbing] from
 *   outside, e.g. to suppress a parent's own gestures during a drag.
 */
@Composable
fun <S : PlaybackState> rememberPlayerState(
    playbackState: S,
    uiStateHolder: PlayerUiStateHolder = rememberPlayerUiStateHolder(),
    seekInteractionState: SeekInteractionState = rememberSeekInteractionState(),
    onPlaybackAction: (PlaybackAction) -> Unit,
): PlayerState<S> {
    // Keyed on the holders rather than Unit: swapping either of those is
    // swapping identity, and the state object must follow. Not keyed on
    // playbackState or the handler — those are refreshed below instead, which is
    // the whole point of a stable holder.
    val state = remember(uiStateHolder, seekInteractionState) {
        PlayerState(playbackState, uiStateHolder, seekInteractionState, onPlaybackAction)
    }

    // Written during composition, read by everything composed after — the same
    // forward-flowing shape as rememberUpdatedState.
    state.updatePlaybackState(playbackState)
    state.updateActionHandler(onPlaybackAction)

    return state
}
