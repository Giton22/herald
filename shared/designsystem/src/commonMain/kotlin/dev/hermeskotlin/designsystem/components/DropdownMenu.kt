package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.AnchorSide
import com.composeunstyled.DropdownMenuPanel
import com.composeunstyled.DropdownMenuPanelScope
import com.composeunstyled.MenuItem
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.ThemeToken
import androidx.compose.ui.text.TextStyle
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** How far the fade reaches in from an edge that has more rows past it. */
private val MENU_FADE = 40.dp

/** Kept free between a menu as tall as the screen and the screen's edges. */
private val MENU_SCREEN_MARGIN = 16.dp

/** Tallest a menu gets on a window whose size isn't known yet. */
private val MENU_FALLBACK_HEIGHT = 440.dp

/** A menu always shows at least a row, however short the window. */
private val MENU_MIN_HEIGHT = 56.dp

/**
 * A small menu that opens right under its [anchor] (or above it, when [above], for an anchor at the bottom of
 * the screen), lined up with the anchor's end edge (or its start edge, per [alignment]). Controlled: open with [expanded]; picking an item, tapping
 * outside or Back calls [onExpandedChange] with false.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    above: Boolean = false,
    /** Which edge of the anchor the menu lines up with; [AnchorAlignment.Start] for an anchor at the left of the screen. */
    alignment: AnchorAlignment = AnchorAlignment.End,
    /**
     * Tallest the menu gets before it scrolls, for a list that can grow long. Unspecified, a menu of actions
     * shows them all and scrolls only when the screen has no room for them.
     */
    maxHeight: Dp = Dp.Unspecified,
    items: @Composable DropdownMenuPanelScope.() -> Unit,
    anchor: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    UnstyledDropdownMenu(
        expanded,
        onExpandedChange,
        modifier,
        if (above) AnchorSide.Top else AnchorSide.Bottom,
        alignment,
        4.dp,
        0.dp,
        {
            // The library measures the panel with no height limit, so the panel sets its own: the window's height
            // less the system bars and a margin, or maxHeight when that is lower. Unbounded, the scroll couldn't
            // measure at all. The menu's window is edge to edge, so rows under the bars would take no taps.
            val density = LocalDensity.current
            val window = LocalWindowInfo.current.containerSize.height
            val bars = WindowInsets.safeDrawing.let { it.getTop(density) + it.getBottom(density) }
            val roomOnScreen = with(density) { (window - bars).toDp() } - MENU_SCREEN_MARGIN
            val cap = maxHeight.takeIf { it.isSpecified && it.isFinite }
            val tallest = when {
                window <= 0 -> cap ?: MENU_FALLBACK_HEIGHT
                cap != null -> minOf(cap, roomOnScreen)
                else -> roomOnScreen
            }.coerceAtLeast(MENU_MIN_HEIGHT)
            val scroll = rememberScrollState()
            val fadeColor = Theme[colors][surfaceElevated]
            DropdownMenuPanel(
                Modifier
                    .widthIn(min = 200.dp, max = 280.dp)
                    // As wide as the widest row, so every row can fill it and take a tap anywhere across.
                    .width(IntrinsicSize.Max)
                    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.20f))
                    .clip(shape)
                    .background(Theme[colors][surfaceElevated])
                    .border(1.dp, Theme[colors][stroke], shape)
                    // The panel stops at the room on screen (or maxHeight) and scrolls past it: the library
                    // lays every row out and clips the rest, so rows past the edge couldn't be reached.
                    // The rows stay the panel's own children, so arrow keys still move between them.
                    .heightIn(max = tallest)
                    // An edge with more rows past it fades out, so a cut-off menu reads as one that scrolls.
                    .drawWithContent {
                        drawContent()
                        val fade = MENU_FADE.toPx()
                        // Each fade grows with how far there is to scroll that way, so opening the menu (which
                        // nudges it a few pixels to the focused row) doesn't dim the first row.
                        val top = (scroll.value / fade).coerceIn(0f, 1f)
                        val bottom = ((scroll.maxValue - scroll.value) / fade).coerceIn(0f, 1f)
                        if (top > 0f) {
                            drawRect(
                                Brush.verticalGradient(listOf(fadeColor.copy(alpha = top), Color.Transparent), endY = fade),
                                size = Size(size.width, fade),
                            )
                        }
                        if (bottom > 0f) {
                            drawRect(
                                Brush.verticalGradient(listOf(Color.Transparent, fadeColor.copy(alpha = bottom)), startY = size.height - fade, endY = size.height),
                                topLeft = Offset(0f, size.height - fade),
                                size = Size(size.width, fade),
                            )
                        }
                    }
                    .verticalScroll(scroll)
                    .padding(vertical = 4.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
            ) { items() }
        },
        anchor,
    )
}

/** One row of a [DropdownMenu]: an icon and a label. The menu closes when it is picked. */
@Composable
fun DropdownMenuPanelScope.MenuAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    val row = MenuRowSize.current()
    MenuItem(
        onClick = onClick,
        // The panel lays rows out at their own width; filling it makes the whole row the target, not just the words.
        modifier = Modifier.fillMaxWidth().heightIn(min = row.height),
        indication = rememberHoverIndication(Theme[colors][textColor]),
    ) {
        Row(
            Modifier.padding(horizontal = row.horizontal, vertical = row.vertical),
            horizontalArrangement = Arrangement.spacedBy(row.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(row.icon))
            Text(text, style = Theme[typography][row.style], color = Theme[colors][textColor])
        }
    }
}

/** A menu row's measures: a finger's on a phone, a pointer's (compact, as desktop menus are) on a desktop. */
private class MenuRowSize(val height: Dp, val horizontal: Dp, val vertical: Dp, val gap: Dp, val icon: Dp, val style: ThemeToken<TextStyle>) {
    companion object {
        private val Phone = MenuRowSize(48.dp, 16.dp, 12.dp, 12.dp, 18.dp, body)
        private val Desktop = MenuRowSize(32.dp, 12.dp, 6.dp, 10.dp, 16.dp, bodySmall)

        @Composable
        fun current() = if (LocalDesktopLayout.current) Desktop else Phone
    }
}

/**
 * One choice in a [DropdownMenu] that picks among options: an icon, the label, an optional [count] in mono, and
 * a tick on the [selected] one. The menu closes when it is picked.
 */
@Composable
fun DropdownMenuPanelScope.MenuChoice(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int? = null,
) {
    val row = MenuRowSize.current()
    MenuItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = row.height).semantics { this.selected = selected },
        indication = rememberHoverIndication(Theme[colors][textColor]),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = row.horizontal, vertical = row.vertical),
            horizontalArrangement = Arrangement.spacedBy(row.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(row.icon))
            Text(
                text,
                style = Theme[typography][row.style].copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = Theme[colors][textColor],
                singleLine = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    count.toString(),
                    style = Theme[typography][code].copy(fontSize = 12.sp),
                    color = Theme[colors][textTertiary],
                    singleLine = true,
                )
            }
            // Always takes the room, so counts line up whether or not a row is the picked one. Silent: the row's
            // own selected state already says it.
            Box(Modifier.size(18.dp)) {
                if (selected) UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][textColor], modifier = Modifier.size(18.dp))
            }
        }
    }
}
