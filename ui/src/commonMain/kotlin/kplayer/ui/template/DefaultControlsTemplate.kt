package kplayer.ui.template

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kplayer.ui.DurationText
import kplayer.ui.ErrorText
import kplayer.ui.FullscreenButton
import kplayer.ui.PlayPauseButton
import kplayer.ui.PlayerState
import kplayer.ui.ScalingModeButton
import kplayer.ui.TimeSeekBar
import kplayer.core.state.isBuffering

/**
 * Conventional media-player chrome: a top bar for display options, a large
 * centre play/pause target, and a bottom row of elapsed time, seek bar and a
 * fullscreen toggle.
 *
 * Everything is built from the controls in `PlayerControls.kt` and reads only
 * `playbackState` / `uiState`, so this doubles as the reference for writing your
 * own template — copy it and rearrange.
 *
 * An extension because that is the shape of `FlexibleVideoPlayer`'s
 * `controlsOverlay` slot. The receiver is captured into `state` immediately: the
 * controls take it as a parameter, and inside the layouts below `this` is a
 * `BoxScope` or a `RowScope`, not the player.
 *
 * Opens its own `Box` to align against — [PlayerState] carries no
 * `BoxScope` of its own, so any template that wants `Modifier.align(...)`
 * provides that `Box` itself, the same way this one does.
 */
@Composable
fun PlayerState<*>.DefaultControlsTemplate(modifier: Modifier = Modifier) {
    val state = this
    Box(modifier.fillMaxSize()) {

        // Top scrim + display options.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScalingModeButton(state)
        }

        // Centre: a spinner while buffering, otherwise the play/pause target.
        if (state.playbackState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        } else {
            PlayPauseButton(state, modifier = Modifier.align(Alignment.Center))
        }

        // Bottom scrim + transport.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TimeSeekBar(state, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DurationText(state)
                Spacer(Modifier.weight(1f))
                FullscreenButton(state)
            }
        }

        ErrorText(state, modifier = Modifier.align(Alignment.Center))
    }
}
