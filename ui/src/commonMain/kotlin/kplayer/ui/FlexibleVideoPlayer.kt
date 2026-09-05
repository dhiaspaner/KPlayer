package kplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kplayer.core.MediaPlayer
import kplayer.core.state.MediaSource
import kplayer.core.state.isPlaying
import kplayer.ui.template.DefaultControlsTemplate
import kplayer.videoplayer.VideoPlayerState
import kotlin.time.Duration.Companion.milliseconds

/**
 * The library's entry point: a video player that renders one state holder.
 *
 * Unidirectional, and blind to whatever is actually decoding:
 *
 * ```
 * PlayerState ──► FlexibleVideoPlayer ──► videoSurface / content / controls
 *      ▲                                              │
 *      └───────────── PlaybackAction ─────────────────┘
 * ```
 *
 * Nothing here, in [PlayerState], or in any template can observe whether the
 * backend is ExoPlayer, AVPlayer, a Cast session, or a `when` block in a test.
 * The composable reads [PlayerState] and the state holder forwards
 * [kplayer.core.event.PlaybackAction]s to whoever built it.
 *
 * ```kotlin
 * val state = rememberPlayerState(
 *     playbackState = playback,
 *     onPlaybackAction = viewModel::onPlaybackAction,
 * )
 *
 * FlexibleVideoPlayer(state = state)
 * ```
 *
 * The overload taking a [MediaPlayer] wires all of this to a real engine in one
 * line; see it for the common case.
 *
 * ### The three layers
 *
 * They stack in this order:
 *
 * 1. **[videoSurface]** — the frames, and nothing else.
 * 2. **[contentOverlay]** — draws *about* the video: subtitles, watermarks,
 *    casting badges. Sits below the tap layer, so nothing here swallows the
 *    gesture that toggles the chrome.
 * 3. **[controlsOverlay]** — drives the video.
 *
 * Both overlays get [PlayerState] as receiver: one type to learn, and a badge
 * that needs to become tappable does not have to be moved between slots. The
 * layers differ in *where they sit*, not in what they are allowed to reach.
 *
 * @param state the player's state holder; see [rememberPlayerState].
 * @param surfaceConfig how the video itself is rendered — scaling, render mode,
 *   subtitles, PiP, screen-on. See [VideoSurfaceConfig]. Its `scalingMode` and
 *   `renderMode` are **initial values only**: both are hoisted into
 *   [PlayerUiStateHolder] so they can be driven at runtime, and the holder owns
 *   them from then on. Its `scalingMode` is
 *   only a seed; `state.uiState` is the live authority and wins.
 * @param autoHideControls when true, controls fade out after
 *   [autoHideDelayMillis] of uninterrupted playback and reappear on tap. Turn it
 *   off for previews, tests, or chrome that is meant to stay put. Ignored under
 *   [VideoSurfaceConfig.showNativeControls], which runs its own hide timer.
 * @param videoSurface the native render surface, as a slot.
 *
 *   This is the one thing a state holder genuinely cannot express: attaching a
 *   `SurfaceView` or an `AVPlayerViewController` needs the platform player
 *   object itself, not a description of it. Isolating it here is what keeps the
 *   rest of this composable engine-agnostic — and defaulting it to *nothing* is
 *   why the whole player renders from a fabricated state with no backend at all.
 *
 *   It receives the config **after** the scaling merge below, so a surface never
 *   has to re-derive which scaling mode actually won.
 * @param contentOverlay overlays drawn under the tap layer, with [PlayerState]
 *   as receiver. Empty by default — subtitles are not special-cased here, they
 *   are just the most common thing to put in this slot.
 * @param controlsOverlay the transport chrome, with [PlayerState] as receiver.
 *   Defaults to [DefaultControlsTemplate]. **Not rendered at all** when
 *   [VideoSurfaceConfig.showNativeControls] is on — the platform's controller
 *   replaces it rather than layering with it.
 */
@Composable
fun FlexibleVideoPlayer(
    state: PlayerState<VideoPlayerState>,
    modifier: Modifier = Modifier,
    surfaceConfig: VideoSurfaceConfig = VideoSurfaceConfig(),
    autoHideControls: Boolean = true,
    autoHideDelayMillis: Long = 3_000L,
    videoSurface: @Composable BoxScope.(VideoSurfaceConfig) -> Unit = {},
    contentOverlay: @Composable PlayerState<VideoPlayerState>.() -> Unit = {},
    controlsOverlay: @Composable PlayerState<VideoPlayerState>.() -> Unit = { DefaultControlsTemplate() },
) {
    // uiState is the live authority for scaling — it is what a ScalingModeButton
    // mutates — so the surface must see its value, not the config's stale seed.
    // The holder owns both of these once it exists, so they are overwritten here
    // rather than read from the config: a `setScalingMode` or `setRenderMode` from
    // outside the player must not be undone by the next recomposition passing the
    // original config down again.
    val effectiveConfig = remember(surfaceConfig, state.uiState.scalingMode, state.uiState.renderMode) {
        surfaceConfig.copy(
            scalingMode = state.uiState.scalingMode,
            renderMode = state.uiState.renderMode,
        )
    }

    // Auto-hide: the timer restarts whenever the controls are shown or playback
    // starts. Never hides while paused or scrubbing — the user is still working.
    // Skipped under native controls, which run their own hide timer; this one
    // would be toggling a flag nothing reads.
    if (autoHideControls && !surfaceConfig.showNativeControls) {
        LaunchedEffect(state.uiState.controlsVisible, state.playbackState.isPlaying, state.isScrubbing) {
            if (state.uiState.controlsVisible && state.playbackState.isPlaying && !state.isScrubbing) {
                delay(autoHideDelayMillis.milliseconds)
                state.hideControls()
            }
        }
    }

    Box(
        modifier
            .then(
                // A fixed ratio applied here rather than left to the caller, so
                // the player has an intrinsic size before any media loads and
                // the surrounding layout does not jump on the first frame.
                surfaceConfig.targetAspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier
            )
            .background(surfaceConfig.backgroundColor)
    ) {
        videoSurface(effectiveConfig)

        state.contentOverlay()

        // With the platform drawing its own transport, both of the layers below
        // are not merely redundant but harmful: the tap layer would swallow
        // every touch before the native controller saw it, and the overlay would
        // stack a second set of controls on the first. The native controller
        // owns its own visibility, so uiState.controlsVisible stops driving
        // anything in this mode.
        if (!surfaceConfig.showNativeControls) {

            // Tap layer sits above the video and content but below the controls:
            // toggles the overlay without a subtitle swallowing the gesture.
            Box(Modifier.fillMaxSize().noRippleClickable { state.toggleControls() })

            AnimatedVisibility(
                visible = state.uiState.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.controlsOverlay()
            }
        }
    }
}

/**
 * Convenience overload for the common case: a real engine.
 *
 * Deliberately the thinnest possible adapter — it collects the player's state
 * into a [PlayerState], points that at `onAction`, and supplies the one thing a
 * state holder cannot, the native render surface. Every behaviour (auto-hide,
 * native controls, scaling, scrubbing) lives in the primary composable above and
 * is not restated here, so the two can never drift.
 *
 * ```kotlin
 * val player = rememberVideoPlayer()
 * LaunchedEffect(Unit) { player.load(MediaSource.Url("https://…/video.mp4")) }
 *
 * FlexibleVideoPlayer(
 *     player = player,
 *     modifier = Modifier.fillMaxWidth(),
 *     surfaceConfig = VideoSurfaceConfig(targetAspectRatio = 16f / 9f),
 * ) { DefaultControlsTemplate() }
 * ```
 *
 * Reach for the [PlayerState] overload instead when the player lives in a
 * ViewModel, when playback is driven from somewhere the composable cannot see
 * (a Cast session, a playback service), or when rendering chrome with no engine.
 *
 * See the primary [FlexibleVideoPlayer] for every parameter.
 */
@Composable
fun <S : MediaSource> FlexibleVideoPlayer(
    player: MediaPlayer<S, VideoPlayerState>,
    modifier: Modifier = Modifier,
    surfaceConfig: VideoSurfaceConfig = VideoSurfaceConfig(renderMode = VideoRenderMode.TEXTURE),
    uiStateHolder: PlayerUiStateHolder = rememberPlayerUiStateHolder(
        scalingMode = surfaceConfig.scalingMode,
        renderMode = surfaceConfig.renderMode,
    ),
    seekInteractionState: SeekInteractionState = rememberSeekInteractionState(),
    autoHideControls: Boolean = true,
    autoHideDelayMillis: Long = 3_000L,
    contentOverlay: @Composable PlayerState<*>.() -> Unit = {},
    controlsOverlay: @Composable PlayerState<*>.() -> Unit = { DefaultControlsTemplate() },
) {
    val playbackState by player.state.collectAsState()

    val state = rememberPlayerState(
        playbackState = playbackState,
        uiStateHolder = uiStateHolder,
        seekInteractionState = seekInteractionState,
        // Remembered so the handler keeps a stable identity instead of
        // allocating a fresh reference on every state emission.
        onPlaybackAction = remember(player) { player::onAction },
    )

    FlexibleVideoPlayer(
        state = state,
        modifier = modifier,
        surfaceConfig = surfaceConfig,
        autoHideControls = autoHideControls,
        autoHideDelayMillis = autoHideDelayMillis,
        videoSurface = { config ->
            NativeVideoSurface(
                player = player,
                config = config,
                modifier = Modifier.fillMaxSize(),
            )
        },
        contentOverlay = contentOverlay,
        controlsOverlay = controlsOverlay,
    )
}
