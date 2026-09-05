package sample.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TinyButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
            .height(24.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            lineHeight = 9.sp
        )
    }
}