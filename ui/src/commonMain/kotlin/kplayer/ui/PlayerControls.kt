package kplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kplayer.ui.model.SeekInteractionDefaults
import kplayer.ui.model.formatPlaybackTime
import kplayer.core.event.PlaybackAction
import kplayer.core.state.isPlaying
import kplayer.core.state.isSeekable
import kotlin.time.Duration.Companion.milliseconds

/**
 * The built-in controls: ordinary composables that take a [PlayerState].
 *
 * Not extensions and not members — a plain `state` parameter, so they are called
 * the same way from anywhere: a template with the state as receiver, a screen
 * that holds it in a `val`, a preview, a lambda nested three layouts deep. An
 * extension would resolve against whatever implicit receiver happens to be in
 * scope, which inside a `Box { Row { … } }` is not the one you meant.
 *
 * They have no access your own controls lack. Everything below reads
 * [PlayerState.playbackState] and calls [PlayerState.dispatch] through the same
 * public API, so writing a replacement is copying one of these and changing the
 * layout — see [kplayer.ui.template.DefaultControlsTemplate] for a full set
 * arranged into chrome.
 *
 * None of them carry a `BoxScope`; a caller that wants `Modifier.align(...)`
 * opens its own `Box`, exactly as the templates do.
 */

/** Play/pause toggle; the glyph reflects what a tap will do. */
@Composable
fun PlayPauseButton(
    state: PlayerState<*>,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    tint: Color = Color.White,
    background: Color = Color.Black.copy(alpha = 0.35f),
    onClick: () -> Unit = { state.playPause() },
) {
    Box(
        modifier
            .size(size)
            .background(background, CircleShape)
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = Modifier.size(size * 0.45f)
        if (state.playbackState.isPlaying) {
            PlayerIcons.Pause(glyph, tint)
        } else {
            PlayerIcons.Play(glyph, tint)
        }
    }
}

/**
 * Draggable seek bar.
 *
 * The whole drag lives in [SeekInteractionState] — local Compose state over
 * the [kplayer.ui.model.SeekInteraction] rule, no engine round-trip.
 * While the finger is down the slider renders the drag target, so nothing
 * yanks the thumb; on release exactly one [PlaybackAction.SeekTo] goes out
 * and the target is held until the engine reports a position within
 * [SeekInteractionDefaults.SETTLE_TOLERANCE_MS] of it.
 *
 * The timeout below is the safety valve: if the seek is rejected or the
 * stream ends first, the engine never reports the target and the thumb
 * would otherwise stay frozen there forever.
 */
@Composable
fun TimeSeekBar(
    state: PlayerState<*>,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
) {
    val playbackState = state.playbackState
    val seek = state.seekInteractionState
    val duration = playbackState.durationMs

    // Engine caught up → hand the thumb back to it.
    LaunchedEffect(playbackState.positionMs, seek.pendingSeekMs) {
        if (seek.hasCaughtUp(playbackState.positionMs)) seek.settle()
    }
    // …or it never will. Do not hold the thumb hostage.
    LaunchedEffect(seek.pendingSeekMs) {
        if (seek.pendingSeekMs == null) return@LaunchedEffect
        delay(SeekInteractionDefaults.SETTLE_TIMEOUT_MS.milliseconds)
        seek.settle()
    }

    val interactionSource = remember { MutableInteractionSource() }

    Slider(
        value = state.displayPositionMs.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
        valueRange = 0f..(if (duration > 0L) duration.toFloat() else 1f),
        enabled = playbackState.isSeekable,
        onValueChange = { seek.onDrag(it.toLong()) },
        onValueChangeFinished = { seek.onDragEnd()?.let { target -> state.seekTo(target) } },
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(
            thumbColor = activeColor,
            activeTrackColor = activeColor,
            inactiveTrackColor = inactiveColor,
        ),
        modifier = modifier,
    )
}

/** `1:23 / 4:56`, or just the elapsed time when [showDuration] is false. */
@Composable
fun DurationText(
    state: PlayerState<*>,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    showDuration: Boolean = true,
) {
    val elapsed = formatPlaybackTime(state.displayPositionMs)
    val text = if (showDuration) {
        "$elapsed / ${formatPlaybackTime(state.playbackState.durationMs)}"
    } else {
        elapsed
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Cycles the scaling mode; the glyph reflects the mode now in effect. */
@Composable
fun ScalingModeButton(
    state: PlayerState<*>,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tint: Color = Color.White,
) {
    Box(
        modifier.size(size).noRippleClickable { state.cycleScalingMode() },
        contentAlignment = Alignment.Center,
    ) {
        PlayerIcons.Scaling(Modifier.size(size * 0.75f), tint, state.uiState.scalingMode)
    }
}

/**
 * Toggles [PlayerUiStateHolder.isFullscreen].
 *
 * The library only flips the flag — hiding system bars and locking
 * orientation is app policy, so observe `uiState.isFullscreen` from your
 * screen and react there.
 */
@Composable
fun FullscreenButton(
    state: PlayerState<*>,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tint: Color = Color.White,
) {
    Box(
        modifier.size(size).noRippleClickable { state.toggleFullscreen() },
        contentAlignment = Alignment.Center,
    ) {
        PlayerIcons.Fullscreen(Modifier.size(size * 0.75f), tint, exit = state.uiState.isFullscreen)
    }
}

/** Error text, rendered only when the engine reported a failure. */
@Composable
fun ErrorText(
    state: PlayerState<*>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFFF6B6B),
) {
    val error = state.playbackState.errorMessage ?: return
    Text(
        text = error,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(16.dp),
    )
}

/**
 * Clickable without the ripple/indication — controls sit on video, where a
 * material ripple reads as a rendering artifact.
 */
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}
