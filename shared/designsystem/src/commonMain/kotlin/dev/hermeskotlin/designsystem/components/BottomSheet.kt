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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composeunstyled.DragIndication
import com.composeunstyled.ModalBottomSheetProperties
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
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.title as titleStyle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
    // The sheet reports a dismiss once per showing and forgets that it did only when a new showing comes to rest,
    // so a tap outside while it is still sliding in after an earlier dismiss hides it without calling onDismiss:
    // [visible] stays true and the sheet can never be shown again. Taps outside wait until it has come to rest,
    // and a sheet that ends up hidden while still meant to be visible reports the dismiss here.
    val opened = state.isIdle && state.currentDetent != SheetDetent.Hidden
    val stillVisible by rememberUpdatedState(visible)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state) {
        var shown = false
        snapshotFlow { state.isIdle && state.currentDetent == SheetDetent.Hidden && state.targetDetent == SheetDetent.Hidden }
            .collect { hidden ->
                if (!hidden) {
                    shown = true
                } else if (shown) {
                    shown = false
                    if (stillVisible) dismiss()
                }
            }
    }
    val shape = RoundedCornerShape(topStart = Theme[radii][radiusLarge], topEnd = Theme[radii][radiusLarge])

    UnstyledModalBottomSheet(
        state = state,
        properties = ModalBottomSheetProperties(dismissOnClickOutside = opened),
        onDismiss = onDismiss,
        overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.45f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        // A sheet tall enough to reach the top stops under the status bar rather than running beneath it.
        Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
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

/** Optional heading block for a [BottomSheet]: the title in the sheet's title style, a quiet line under it. */
@Composable
fun SheetHeader(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = Theme[typography][titleStyle],
            color = Theme[colors][textColor],
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = Theme[typography][bodySmall].copy(fontSize = 13.sp),
                color = Theme[colors][textTertiary],
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One full-width row in a [BottomSheet]: its icon on a small tile, then the label. [destructive] tints both
 * with the danger color.
 */
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
            Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(if (destructive) Theme[colors][dangerSoft] else Theme[colors][surface2], RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                UnstyledIcon(
                    icon,
                    contentDescription = null,
                    tint = if (destructive) tint else Theme[colors][textSecondary],
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(text, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = tint)
        }
    }
}
