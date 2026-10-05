package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.input
import dev.hermeskotlin.designsystem.label as labelStyle
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** Shows every character as a bullet; the underlying [TextFieldState] keeps the real text. */
private object PasswordMask : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        replace(0, length, "•".repeat(length))
    }
}

/**
 * The one text input: optional label above, helper/error text below.
 * [password] masks the text and adds a show/hide toggle; [clearable] adds a clear button once non-empty.
 */
@Composable
fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    /** Grows up to this many lines when not [singleLine], then scrolls. */
    maxLines: Int = Int.MAX_VALUE,
    password: Boolean = false,
    leadingIcon: ImageVector? = null,
    clearable: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    /** Lets the caller move focus into the field, e.g. when a search opens. */
    focusRequester: FocusRequester? = null,
    /** What autofill may offer here, e.g. [ContentType.SmsOtpCode]; null offers nothing. */
    contentType: ContentType? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val borderColor = when {
        error != null -> Theme[colors][danger]
        focused -> Theme[colors][accent]
        else -> Theme[colors][stroke]
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(label, style = Theme[typography][labelStyle], color = Theme[colors][textSecondary])
        }
        UnstyledTextField(
            state = state,
            enabled = enabled,
            accessibilityLabel = label,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][text],
            cursorBrush = SolidColor(Theme[colors][accent]),
            // UnstyledTextField defaults to unspecified colors, which hides the selection and its handles.
            selectionColors = LocalTextSelectionColors.current,
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
            keyboardOptions = if (password) {
                keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
            } else {
                keyboardOptions
            },
            outputTransformation = if (password && !revealed) PasswordMask else null,
            onKeyboardAction = onKeyboardAction,
            interactionSource = interactionSource,
            modifier = Modifier
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(contentType?.let { type -> Modifier.semantics { this.contentType = type } } ?: Modifier)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .background(Theme[colors][input], shape)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    UnstyledIcon(
                        leadingIcon,
                        contentDescription = null,
                        tint = Theme[colors][textTertiary],
                        modifier = Modifier.padding(end = 10.dp).size(18.dp),
                    )
                }
                TextInput(
                    modifier = Modifier.weight(1f),
                    placeholder = placeholder?.let {
                        { Text(it, style = Theme[typography][body], color = Theme[colors][textTertiary]) }
                    },
                )
                if (clearable && state.text.isNotEmpty()) {
                    UnstyledButton(
                        onClick = { state.clearText() },
                        modifier = Modifier.padding(start = 8.dp).size(24.dp),
                        indication = null,
                    ) {
                        UnstyledIcon(
                            Lucide.X,
                            contentDescription = "Clear",
                            tint = Theme[colors][textTertiary],
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (password) {
                    UnstyledButton(
                        onClick = { revealed = !revealed },
                        modifier = Modifier.padding(start = 8.dp).size(24.dp),
                        indication = null,
                    ) {
                        UnstyledIcon(
                            if (revealed) Lucide.EyeOff else Lucide.Eye,
                            contentDescription = if (revealed) "Hide password" else "Show password",
                            tint = Theme[colors][textTertiary],
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        val helper = error ?: supportingText
        if (helper != null) {
            Text(
                helper,
                style = Theme[typography][caption],
                color = if (error != null) Theme[colors][danger] else Theme[colors][textTertiary],
            )
        }
    }
}
