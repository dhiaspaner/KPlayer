package sample.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kplayer.ui.VideoFrameDiagnostics

/**
 * Why the drawn (`TEXTURE`) surface is or is not showing a picture.
 *
 * The frame path has no error channel of its own — a dropped frame is not a
 * playback failure, so nothing about it reaches `PlaybackState.errorMessage` and
 * the panel above this one stays perfectly healthy while the video is black.
 * This is that missing half: it reads
 * [kplayer.ui.rememberVideoFrameDiagnostics], which watches the engine's
 * `frameOutputFailure` and the renderer's own counters.
 *
 * Read it top to bottom — the first line that is not what you expect is the
 * answer.
 */
@Composable
fun FrameOutputDescription(
    diagnostics: VideoFrameDiagnostics,
    modifier: Modifier = Modifier,
) {
    // Opens itself when something is wrong: the whole point is to be seen without
    // being looked for.
    val hasFailure = diagnostics.outputFailure != null || diagnostics.renderFailure != null
    var expanded by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("Frame output") },
            supportingContent = {
                Text((if (diagnostics.isRendering) "OK — " else "") + diagnostics.verdict())
            },
            trailingContent = {
                TinyButton(
                    text = if (expanded || hasFailure) "^" else "v",
                    onClick = { expanded = !expanded },
                )
            },
        )

        AnimatedVisibility(expanded || hasFailure) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Frame source: ${if (diagnostics.frameSourceAvailable) "yes" else "no — this engine composites its own picture"}")
                Text("Output enabled: ${diagnostics.outputEnabled}")
                Text("Output failure: ${diagnostics.outputFailure ?: "-"}")
                Text(
                    "Last decoded frame: " + (
                        diagnostics.decoded?.let { "${it.width}x${it.height}, stride ${it.rowBytes}, #${it.sequence}" }
                            ?: "none"
                        )
                )
                Text("Frames drawn: ${diagnostics.drawnFrames}")
                Text("Render failure: ${diagnostics.renderFailure ?: "-"}")

                Text(
                    text = "The engine's frameOutputFailure and the renderer's counters. " +
                        "Decoded rising with drawn stuck means the surface is not consuming; " +
                        "neither moving with no failure means the decoder has produced nothing yet.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** One line that names the state the six fields below add up to. */
private fun VideoFrameDiagnostics.verdict(): String = when {
    !frameSourceAvailable -> "no frame path — the platform renders this player itself"
    outputFailure != null -> "decode failed: $outputFailure"
    renderFailure != null -> "frames arrive, drawing failed: $renderFailure"
    !outputEnabled -> "nothing is pulling frames (render mode is not TEXTURE?)"
    decoded == null -> "enabled, no frame decoded yet"
    drawnFrames == 0L -> "frames decoded, none drawn yet"
    else -> "drawing — ${decoded?.width}x${decoded?.height}, $drawnFrames frames"
}
