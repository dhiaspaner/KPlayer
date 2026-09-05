package sample.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kplayer.core.state.PlaybackState

@Composable
fun VideoPlayerStateDescription(
    state: PlaybackState,
) {

    var expanded by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = {
                Text("Player State")
            },
            supportingContent = {
                Text(state.status.name)
            },
            trailingContent = {

                          TinyButton(
                              text =     if (expanded) "^" else "v",
                              onClick = { expanded = !expanded }
                          )



            }
        )

        AnimatedVisibility(expanded) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Status: ${state.status}")
                Text("Play When Ready: ${state.playWhenReady}")
                Text("Position: ${state.positionMs} ms")
                Text("Duration: ${state.durationMs} ms")
                Text("Source: ${state.source}")
                Text("Error: ${state.errorMessage ?: "-"}")
            }
        }
    }
}