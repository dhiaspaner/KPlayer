package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kplayer.ui.model.VideoScalingMode

/**
 * The player's **presentation** state, hoisted out of the engine entirely.
 *
 * Scaling, controls visibility and fullscreen are decisions the UI makes and the
 * engine has no opinion about — ExoPlayer does not care whether your chrome is
 * on screen. Keeping them here rather than in [kplayer.core.state.PlaybackState] has
 * three consequences worth the split:
 *
 * 1. **They survive player recreation.** Rotation, surface re-attach, even
 *    swapping the engine underneath: this holder is untouched.
 * 2. **They are Compose-native.** Backed by `mutableStateOf`, not a `StateFlow`,
 *    so reading `scalingMode` subscribes only the composables that draw with it.
 *    A position update ticking four times a second recomposes nothing here.
 * 3. **They are hoistable.** Because the caller can create the holder, code
 *    *outside* the player can drive it — see [rememberPlayerUiStateHolder].
 *
 * Modelled on `LazyListState`: a `@Stable` class with read-only properties,
 * explicit mutators, a `Saver`, and a `remember…` factory.
 *
 * Main-thread only, like the rest of Compose state.
 */
@Stable
class PlayerUiStateHolder(
    scalingMode: VideoScalingMode = VideoScalingMode.FIT,
    controlsVisible: Boolean = true,
    isFullscreen: Boolean = false,
    renderMode: VideoRenderMode = VideoRenderMode.Default,
) {

    // Backed by private state with public read-only projections rather than
    // `var … private set`: the compiler-generated `setScalingMode` /
    // `setFullscreen` setters would collide on the JVM with the same-named
    // mutators below, which are the API this class actually wants to expose.
    private var _scalingMode by mutableStateOf(scalingMode)
    private var _controlsVisible by mutableStateOf(controlsVisible)
    private var _isFullscreen by mutableStateOf(isFullscreen)
    private var _renderMode by mutableStateOf(renderMode)

    /** How the frame is fitted into the surface. Bound to the native surface. */
    val scalingMode: VideoScalingMode get() = _scalingMode

    /** Whether the control overlay is on screen. */
    val controlsVisible: Boolean get() = _controlsVisible

    /**
     * Fullscreen flag.
     *
     * The library only tracks it — actually going fullscreen (hiding system
     * bars, locking orientation) is app policy and platform-specific, so read
     * this from your screen and react there.
     */
    val isFullscreen: Boolean get() = _isFullscreen

    /**
     * How frames reach the screen; see [VideoRenderMode] for the trade-off.
     *
     * Here rather than only on [VideoSurfaceConfig] because it is a decision worth
     * changing *while playing*, not just at composition. The cheap mode is right
     * for ordinary playback and the drawn mode is what a blur, a rounded corner or
     * a shared-element transition needs — and an app that animates its player into
     * fullscreen wants to switch for the duration and switch back.
     *
     * Switching rebuilds the native surface, which costs a frame or two of black.
     * Flip it for a transition, not per frame.
     */
    val renderMode: VideoRenderMode get() = _renderMode

    fun setScalingMode(mode: VideoScalingMode) {
        _scalingMode = mode
    }

    /** FIT → CROP → FILL → FIT, for a single "toggle scaling" button. */
    fun cycleScalingMode() {
        _scalingMode = _scalingMode.next()
    }

    fun showControls() {
        _controlsVisible = true
    }

    fun hideControls() {
        _controlsVisible = false
    }

    fun toggleControls() {
        _controlsVisible = !_controlsVisible
    }

    fun setRenderMode(mode: VideoRenderMode) {
        _renderMode = mode
    }

    /**
     * DIRECT ⇄ TEXTURE, for a single "toggle rendering" button.
     *
     * Two values rather than a cycle, so this is a toggle and not a `next()` the
     * way scaling is.
     */
    fun toggleRenderMode() {
        _renderMode = when (_renderMode) {
            VideoRenderMode.DIRECT -> VideoRenderMode.TEXTURE
            VideoRenderMode.TEXTURE -> VideoRenderMode.DIRECT
        }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _isFullscreen = fullscreen
    }

    fun toggleFullscreen() {
        _isFullscreen = !_isFullscreen
    }

    companion object {

        /**
         * Restores every property across process death and configuration changes.
         * The two enums are saved by ordinal because enums are not natively
         * saveable on every platform.
         *
         * Reads the render mode defensively. A saved list written before this
         * property existed is three entries long, and indexing past its end would
         * throw during restore — which surfaces as a crash on rotation rather than
         * as a lost preference. Falling back to the default costs one setting.
         */
        val Saver: Saver<PlayerUiStateHolder, Any> = listSaver(
            save = {
                listOf(it.scalingMode.ordinal, it.controlsVisible, it.isFullscreen, it.renderMode.ordinal)
            },
            restore = {
                PlayerUiStateHolder(
                    scalingMode = VideoScalingMode.entries[it[0] as Int],
                    controlsVisible = it[1] as Boolean,
                    isFullscreen = it[2] as Boolean,
                    renderMode = (it.getOrNull(3) as? Int)
                        ?.let(VideoRenderMode.entries::getOrNull)
                        ?: VideoRenderMode.Default,
                )
            },
        )
    }
}

/**
 * Creates a [PlayerUiStateHolder] that survives recomposition, configuration
 * changes and process death.
 *
 * The parameters are **initial values only**. Once the user changes a mode, the
 * holder owns it; passing a different argument later does not fight them —
 * exactly how `rememberLazyListState(initialFirstVisibleItemIndex = …)` behaves.
 *
 * Hoist it when something outside the player needs to drive the chrome:
 *
 * ```kotlin
 * val ui = rememberPlayerUiStateHolder(scalingMode = VideoScalingMode.CROP)
 *
 * TopAppBar(actions = {
 *     IconButton(onClick = { ui.cycleScalingMode() }) { … }
 *     IconButton(onClick = { ui.toggleFullscreen() }) { … }
 * })
 * FlexibleVideoPlayer(player = player, uiStateHolder = ui)
 * ```
 */
@Composable
fun rememberPlayerUiStateHolder(
    scalingMode: VideoScalingMode = VideoScalingMode.FIT,
    controlsVisible: Boolean = true,
    isFullscreen: Boolean = false,
    renderMode: VideoRenderMode = VideoRenderMode.Default,
): PlayerUiStateHolder = rememberSaveable(saver = PlayerUiStateHolder.Saver) {
    PlayerUiStateHolder(scalingMode, controlsVisible, isFullscreen, renderMode)
}
