package kplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kplayer.ui.model.VideoScalingMode

/**
 * Icons drawn with [Canvas] rather than pulled from `material-icons-extended`.
 *
 * That artifact is several thousand vectors and would be the single heaviest
 * dependency in the library; a player needs five glyphs. Drawing them also keeps
 * them crisp at any size and tintable without a theme.
 */
internal object PlayerIcons {

    @Composable
    fun Play(modifier: Modifier = Modifier, tint: Color = Color.White) {
        Canvas(modifier) {
            val path = Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.15f)
                lineTo(size.width * 0.85f, size.height * 0.5f)
                lineTo(size.width * 0.25f, size.height * 0.85f)
                close()
            }
            drawPath(path, tint)
        }
    }

    @Composable
    fun Pause(modifier: Modifier = Modifier, tint: Color = Color.White) {
        Canvas(modifier) {
            val barWidth = size.width * 0.22f
            val barHeight = size.height * 0.7f
            val top = size.height * 0.15f
            drawRect(tint, Offset(size.width * 0.22f, top), Size(barWidth, barHeight))
            drawRect(tint, Offset(size.width * 0.56f, top), Size(barWidth, barHeight))
        }
    }

    /** Four corner brackets pointing outwards (enter) or inwards (exit). */
    @Composable
    fun Fullscreen(modifier: Modifier = Modifier, tint: Color = Color.White, exit: Boolean = false) {
        Canvas(modifier) {
            val stroke = Stroke(width = size.minDimension * 0.09f)
            val inset = size.minDimension * 0.18f
            val arm = size.minDimension * 0.24f
            // Each corner is an L drawn from the corner inwards (or the reverse).
            val corners = listOf(
                Triple(Offset(inset, inset), 1f, 1f),
                Triple(Offset(size.width - inset, inset), -1f, 1f),
                Triple(Offset(inset, size.height - inset), 1f, -1f),
                Triple(Offset(size.width - inset, size.height - inset), -1f, -1f),
            )
            corners.forEach { (corner, dx, dy) ->
                val origin = if (exit) Offset(corner.x + arm * dx, corner.y + arm * dy) else corner
                val sx = if (exit) -dx else dx
                val sy = if (exit) -dy else dy
                drawPath(
                    Path().apply {
                        moveTo(origin.x + arm * sx, origin.y)
                        lineTo(origin.x, origin.y)
                        lineTo(origin.x, origin.y + arm * sy)
                    },
                    tint,
                    style = stroke,
                )
            }
        }
    }

    /** A frame whose inner rectangle hints at the current [VideoScalingMode]. */
    @Composable
    fun Scaling(modifier: Modifier = Modifier, tint: Color = Color.White, mode: VideoScalingMode) {
        Canvas(modifier) {
            val stroke = Stroke(width = size.minDimension * 0.08f)
            val inset = size.minDimension * 0.15f

            drawRect(
                color = tint,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = stroke,
            )

            // Inner block: letterboxed for FIT, overflowing for CROP, flush for FILL.
            val (padX, padY) = when (mode) {
                VideoScalingMode.FIT -> size.width * 0.28f to size.height * 0.36f
                VideoScalingMode.CROP -> size.width * 0.36f to size.height * 0.24f
                VideoScalingMode.FILL -> size.width * 0.28f to size.height * 0.28f
            }

            drawRect(
                color = tint,
                topLeft = Offset(padX, padY),
                size = Size(size.width - padX * 2, height = size.height - padY * 2)
            )
        }
    }
}