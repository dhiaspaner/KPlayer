package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kplayer.core.MediaPlayer
import kplayer.core.state.PlaybackStatus

/**
 * [VideoSurfaceConfig.keepScreenOn], as a modifier for the surface to wear.
 *
 * The holding is entirely Compose's: `Modifier.keepScreenOn()` is
 * `View.keepScreenOn` on Android and `UIApplication.idleTimerDisabled` on iOS,
 * each counted across every node that asks for it, so the request nests with the
 * rest of the app instead of clobbering a global flag. This module therefore
 * carries no screen-on code of its own on any platform — only the part Compose
 * cannot know, which is the gate below.
 *
 * Where Compose has no hook the modifier does nothing, and so does this: web has
 * no Screen Wake Lock binding behind it and desktop has nothing at all. Both
 * fall back to whatever the host does by itself, which for a playing `<video>`
 * in a browser is usually enough.
 */
@Composable
internal fun keepScreenOnModifier(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
): Modifier = if (keepScreenOnRequested(player, config)) Modifier.keepScreenOn() else Modifier

/**
 * Whether the screen should be held awake right now: asked for, and playing.
 *
 * Gated on playback rather than on the surface existing, because a paused player
 * has no claim on the user's battery.
 *
 * The status is collected as its own `Boolean` flow rather than off
 * `player.state`, so a caller recomposes on play/pause and not on every position
 * sync. That matters because what reads this is a *modifier*: rebuilding it ~4×
 * a second would re-run each surface's interop `update` with it.
 */
@Composable
private fun keepScreenOnRequested(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
): Boolean {
    val playing by remember(player, config.keepScreenOn) {
        if (!config.keepScreenOn) flowOf(false)
        else player.state.map { it.status == PlaybackStatus.Playing }.distinctUntilChanged()
    }.collectAsState(false)

    return playing
}
