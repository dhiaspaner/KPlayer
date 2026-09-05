package sample.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kplayer.core.state.MediaSource
import sample.source.toMediaSource

/**
 * Chooses what to play: paste a remote URL, or pick a video off the device.
 *
 * Both routes end in the same one-line handoff — `onLoad(MediaSource)` — which is
 * the point worth demonstrating. The player has no notion of "local" versus
 * "remote"; it takes a descriptor, and the four `toMediaSource` actuals decide
 * which variant a picked file becomes on each platform.
 *
 * @param onLoad invoked with the chosen source. The caller decides what "load"
 *   means — [sample.screen.NormalPlayerScreen] issues a `PlaybackAction.Load`.
 * @param initialUrl prefills the field so the sample still plays something on a
 *   cold start without making the user type a URL first.
 */
@Composable
fun MediaSourcePicker(
    onLoad: (MediaSource) -> Unit,
    modifier: Modifier = Modifier,
    initialUrl: String = "",
) {
    // rememberSaveable, not remember: on Android this survives the rotation and
    // the process death that a long picker round-trip can cause, so a typed URL
    // is not lost while the user is in the file browser.
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    // FileKitType.Video rather than a generic file type. It asks each platform for
    // its *media* picker — Android's photo picker, iOS's PHPicker, a video-extension
    // filter on desktop, accept="video/*" on web — which needs no storage permission
    // on Android and, on iOS, hands back a temp copy instead of a security-scoped URL.
    val filePicker = rememberFilePickerLauncher(type = FileKitType.Video) { file ->
        // null means the user backed out of the dialog; leave the last pick alone.
        if (file != null) {
            pickedName = file.name
            onLoad(file.toMediaSource())
        }
    }

    fun loadTypedUrl() {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            focusManager.clearFocus()
            pickedName = null
            onLoad(MediaSource.Url(trimmed))
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Media URL", fontSize = 11.sp) },
            placeholder = { Text("https://…", fontSize = 11.sp) },
            singleLine = true,
            // Go, not Done: the keyboard's action button loads rather than just
            // dismissing, so pasting a URL is paste-then-one-tap on mobile.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { loadTypedUrl() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White,
                focusedBorderColor = Color(0xFF6D6DF0),
                unfocusedBorderColor = Color.DarkGray,
                focusedLabelColor = Color(0xFF9E9EF5),
                unfocusedLabelColor = Color.Gray,
                focusedPlaceholderColor = Color.Gray,
                unfocusedPlaceholderColor = Color.Gray,
            ),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TinyButton(text = "Load URL", onClick = { loadTypedUrl() })
            TinyButton(text = "Pick local video", onClick = { filePicker.launch() })

            pickedName?.let { name ->
                Text(
                    text = name,
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}
