package kplayer.ui.template

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Short-form vertical video chrome, in the TikTok / Reels / Shorts idiom:
 * a right-aligned column of round action buttons, a slim progress bar pinned to
 * the very bottom, and no centre play button — tapping the video toggles
 * playback instead of the overlay.
 *
 * An extension because that is the shape of `FlexibleVideoPlayer`'s
 * `controlsOverlay` slot; the receiver is captured into `state` immediately,
 * since the controls take it as a parameter and `this` inside the layouts below
 * is a `BoxScope`, not the player.
 *
 * Opens its own `Box` to align against — [PlayerState] carries no
 * `BoxScope` of its own, so any template that wants `Modifier.align(...)`
 * provides that `Box` itself, the same way this one does.
 *
 * @param actions extra app-specific buttons (like, comment, share) stacked above
 *   the player's own controls. The player has no opinion about these, so they
 *   are a slot rather than a fixed set.
 */
@Composable
fun PlayerState<*>.ReelsControlsTemplate(
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val state = this
    Box(modifier.fillMaxSize()) {

        // Right-hand action rail.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            actions()

            // Compact play/pause, matching the rail's button size.
            PlayPauseButton(state, size = 44.dp, background = Color.Black.copy(alpha = 0.28f))

            ScalingModeButton(state, size = 28.dp)

            FullscreenButton(state, size = 28.dp)
        }

        // Elapsed time, bottom-left — Reels-style layouts show time compactly and
        // omit the total, since clips are short.
        DurationText(
            state,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 20.dp),
            showDuration = false,
        )

        // Hairline progress bar flush with the bottom edge. It is still the real
        // seek bar — draggable, and it drives the same scrub lifecycle.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            TimeSeekBar(state, modifier = Modifier.fillMaxWidth())
        }

        // Buffering hint, kept subtle so it does not interrupt a scroll feed.
        if (state.playbackState.isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }

        ErrorText(state, modifier = Modifier.align(Alignment.Center))
    }
}
