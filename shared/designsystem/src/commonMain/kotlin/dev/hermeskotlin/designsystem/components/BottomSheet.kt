package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composeunstyled.DragIndication
import com.composeunstyled.Scrim
import com.composeunstyled.Sheet
import com.composeunstyled.SheetDetent
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledModalBottomSheet
import com.composeunstyled.rememberModalBottomSheetState
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * Modal sheet from the bottom edge, sized to its content. Controlled: show with [visible];
 * a drag-down, scrim tap or Back calls [onDismiss], which should set [visible] to false.
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(initialDetent = SheetDetent.Hidden)
    LaunchedEffect(visible) {
        state.targetDetent = if (visible) SheetDetent.FullyExpanded else SheetDetent.Hidden
    }
    val shape = RoundedCornerShape(topStart = Theme[radii][radiusLarge], topEnd = Theme[radii][radiusLarge])

    UnstyledModalBottomSheet(
        state = state,
        onDismiss = onDismiss,
        overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.45f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Sheet(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .background(Theme[colors][surfaceElevated], shape)
                    .border(1.dp, Theme[colors][stroke], shape),
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        DragIndication(
                            Modifier.size(36.dp, 4.dp).background(Theme[colors][strokeStrong], CircleShape),
                        )
                    }
                    content()
                }
            }
        }
    }
}

/** Optional heading block for a [BottomSheet]. */
@Composable
fun SheetHeader(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = Theme[typography][body], color = Theme[colors][textColor], maxLines = 2)
        if (subtitle != null) {
            Text(subtitle, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 2)
        }
    }
}

/** One full-width row in a [BottomSheet]. [destructive] tints it with the danger color. */
@Composable
fun SheetAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) Theme[colors][danger] else Theme[colors][textColor]
    UnstyledButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        indication = rememberColoredIndication(tint),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(
                icon,
                contentDescription = null,
                tint = if (destructive) tint else Theme[colors][textSecondary],
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = Theme[typography][body], color = tint)
        }
    }
}
