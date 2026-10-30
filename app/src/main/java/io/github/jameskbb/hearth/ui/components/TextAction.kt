package io.github.jameskbb.hearth.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.UiTokens

/**
 * A text control: All apps, Arrange, Settings, settings rows, dialog options.
 * Focused: ink at full opacity with a 2 dp accent underline. Unfocused: inkMuted.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Hearth.type.action,
    onFocused: (() -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val palette = Hearth.palette
    val underline = UiTokens.underline
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .remoteClickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .drawWithContent {
                drawContent()
                if (focused) {
                    val y = size.height - underline.toPx() / 2
                    drawLine(
                        color = palette.accent,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = underline.toPx(),
                    )
                }
            }
            .padding(bottom = 4.dp),
    ) {
        Row {
            BasicText(text, style = style.copy(color = if (focused) palette.ink else palette.inkMuted))
            trailing?.invoke(focused)
        }
    }
}
