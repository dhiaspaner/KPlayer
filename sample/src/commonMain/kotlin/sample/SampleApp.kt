package sample

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kplayer.core.MediaPlayer
import sample.screen.NormalPlayerScreen
import sample.screen.ReelsScreen

/**
 * The two things this demo shows: the same engine and the same control DSL
 * driving two completely different playback experiences.
 *
 * @param glyph a text glyph rather than a vector: `material-icons` would be the
 *   heaviest dependency in the whole build for two icons, and the library itself
 *   refuses it for the same reason (see `PlayerIcons`).
 */
private enum class Destination(val label: String, val glyph: String) {

    /** Landscape 16:9 playback with the full surface- and policy-tweaking rig. */
    NORMAL("Normal", "▶"),

    /** A swipeable short-form feed, the way the Reels template is meant to run. */
    REELS("Reels", "⇅"),
}

@Composable
fun SampleApp() {
    var destination by remember { mutableStateOf(Destination.NORMAL) }



    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101014)) {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = { Text(entry.glyph, fontSize = 18.sp) },
                        label = { Text(entry.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFF2A2A38),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->



        // Only the selected destination is composed, which matters more here
        // than in an ordinary tabbed app: each screen builds its own engine via
        // rememberVideoPlayer, so composing both would put two players in
        // contention for one audio session. Switching tabs releases the outgoing
        // engine through that composable's DisposableEffect.
        when (destination) {
            Destination.NORMAL -> NormalPlayerScreen(Modifier.padding(innerPadding))
            Destination.REELS -> ReelsScreen(Modifier.padding(innerPadding))
        }
    }
}
