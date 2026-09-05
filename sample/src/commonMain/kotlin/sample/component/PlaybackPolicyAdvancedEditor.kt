package sample.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kplayer.interruption.AudioFocusPolicy
import kplayer.interruption.BackgroundPolicy
import kplayer.interruption.HeadphonesPolicy
import kplayer.interruption.InterruptionConfig

@Composable
fun PlaybackPolicyAdvancedEditor(
    config: InterruptionConfig,
    onChange: (InterruptionConfig) -> Unit
) {
    Column {

        PolicySelectorRow(
            title = "Background Policy",
            options = backgroundPolicyOptions,
            selected = config.backgroundPolicy,
            onSelect = { onChange(config.copy(backgroundPolicy = it)) }
        )

        PolicySelectorRow(
            title = "Audio Focus Policy",
            options = audioFocusPolicyOptions,
            selected = config.audioFocusPolicy,
            onSelect = { onChange(config.copy(audioFocusPolicy = it)) }
        )

        PolicySelectorRow(
            title = "Headphones Policy",
            options = headphonesPolicyOptions,
            selected = config.headphonesPolicy,
            onSelect = { onChange(config.copy(headphonesPolicy = it)) }
        )
    }
}

/**
 * Generic single-choice selector row backed by a dropdown menu.
 * Works for any closed set of options (label + value).
 */
@Composable
fun <T> PolicySelectorRow(
    title: String,
    options: List<PolicyOption<out T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.first { it.value == selected }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedOption.label)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label)
                                option.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(option.value)
                            expanded = false
                        }
                    )
                }
            }
        }

        selectedOption.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class PolicyOption<T>(
    val value: T,
    val label: String,
    val description: String? = null
)

val backgroundPolicyOptions = listOf(
    PolicyOption(BackgroundPolicy.KeepState, "Keep State", "Playback is unaffected by backgrounding."),
    PolicyOption(BackgroundPolicy.PauseAndRestore, "Pause & Restore", "Pauses in background, resumes on return."),
    PolicyOption(BackgroundPolicy.PauseAndStayPaused, "Pause & Stay Paused", "Pauses in background, requires manual resume.")
)

val audioFocusPolicyOptions = listOf(
    PolicyOption(AudioFocusPolicy.Ignore, "Ignore", "Audio focus changes are ignored."),
    PolicyOption(AudioFocusPolicy.RestoreIfPlayingBefore, "Restore If Playing Before", "Resumes only if it was playing before the interruption."),
    PolicyOption(AudioFocusPolicy.AlwaysResume, "Always Resume", "Always resumes after focus returns."),
    PolicyOption(AudioFocusPolicy.PauseAndStayPaused, "Pause & Stay Paused", "Requires manual resume after focus loss.")
)

val headphonesPolicyOptions = listOf(
    PolicyOption(HeadphonesPolicy.Ignore, "Ignore", "Headphone changes are ignored."),
    PolicyOption(HeadphonesPolicy.PauseAndRequireManualResume, "Pause & Require Manual Resume", "Pauses on disconnect, no auto-resume."),
    PolicyOption(HeadphonesPolicy.PauseAndRestoreOnReconnect, "Pause & Restore on Reconnect", "Pauses on disconnect, resumes on reconnect if playing before."),
    PolicyOption(HeadphonesPolicy.ContinuePlayback, "Continue Playback", "Playback continues after disconnect.")
)
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = Color.White
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange
        )
    }
}