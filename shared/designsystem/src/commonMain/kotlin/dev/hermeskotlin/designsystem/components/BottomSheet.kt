package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import com.composeunstyled.DialogPanel
import com.composeunstyled.DialogScope
import com.composeunstyled.UnstyledDialog
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.hermeskotlin.designsystem.rememberHoverIndication
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
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * Modal sheet from the bottom edge, sized to its content. Controlled: show with [visible];
 * a drag-down, scrim tap or Back calls [onDismiss] once the sheet has slid out, which should set [visible] to false.
 * [onEscape], while set, takes the desktop's Escape instead: a sheet showing one of its items goes back to the list.
 * [anchor], on the desktop, is the button it opened from in window coordinates: it opens there as a popover.
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onEscape: (() -> Unit)? = null,
    anchor: Rect? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OpenModals.Count(visible)
    if (LocalDesktopLayout.current) DesktopSheet(visible, onDismiss, onEscape, anchor, content) else PhoneSheet(visible, onDismiss, content)
}

/**
 * A sheet's content as a desktop shows it: a panel in the middle of the window, as tall as it needs up to the
 * window's height; or, given an [anchor], a popover over (or under) that button, the window not dimmed. A click
 * outside or Escape calls [onDismiss].
 */
@Composable
private fun DesktopSheet(visible: Boolean, onDismiss: () -> Unit, onEscape: (() -> Unit)?, anchor: Rect?, content: @Composable ColumnScope.() -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val escape by rememberUpdatedState(onEscape)
    val focus = remember { FocusRequester() }
    UnstyledDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        overlay = { Scrim(scrimColor = if (anchor != null) Color.Transparent else Color.Black.copy(alpha = 0.45f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        // Here, so each opening starts without the last one's focus.
        var focusInside by remember { mutableStateOf(false) }
        // The highest the panel's top has been, centred, for this window height. Content that grows (a list that
        // loads) keeps it centred; content that shrinks (a search filtering a list) leaves it there, so what's under
        // the pointer doesn't jump. A window resized centres it again.
        val held = remember { IntArray(2) { -1 } }
        // The tallest a popover has been while open.
        val tallest = remember { IntArray(1) }
        // The button as it was when the popover opened: one that moves or goes meanwhile doesn't take it along.
        // Taken again on a reopening that comes while the last one still fades.
        val button = remember(visible) { anchor }
        // Where the layout itself sits in the window, to place a popover against its button. The dialog fills the
        // window, so its padding is a good first guess, before the first layout says.
        val inset = with(LocalDensity.current) { 12.dp.toPx() }
        var origin by remember { mutableStateOf(Offset(inset, inset)) }
        val gap = with(LocalDensity.current) { POPOVER_GAP.roundToPx() }
        val roomy = with(LocalDensity.current) { POPOVER_MIN_ROOM.roundToPx() }
        val popoverWidth = with(LocalDensity.current) { POPOVER_WIDTH.roundToPx() }
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (button != null) 12.dp else 24.dp, vertical = if (button != null) 12.dp else 40.dp)
                .onGloballyPositioned { origin = it.positionInWindow() },
            content = {
                SheetPanel(focus, popover = button != null, onFocusInside = { focusInside = it }, dismiss = { escape?.invoke() ?: dismiss() }, content = content)
            },
        ) { measurables, constraints ->
            if (button == null) {
                val panels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                return@Layout layout(constraints.maxWidth, constraints.maxHeight) {
                    for (panel in panels) {
                        if (panel.height == 0) continue
                        val centred = ((constraints.maxHeight - panel.height) / 2).coerceAtLeast(0)
                        if (held[1] != constraints.maxHeight) held[0] = centred
                        held[1] = constraints.maxHeight
                        held[0] = minOf(held[0], centred)
                        panel.place((constraints.maxWidth - panel.width) / 2, held[0])
                    }
                }
            }
            // A popover: over its button when there's room, else under it, and never shorter than it's been while
            // open, so a search filtering its list doesn't pull the field away from the pointer.
            val top = (button.top - origin.y).toInt()
            val bottom = (button.bottom - origin.y).toInt()
            val above = top - gap
            val below = constraints.maxHeight - bottom - gap
            val over = above >= roomy || above >= below
            val room = (if (over) above else below).coerceIn(0, constraints.maxHeight)
            val width = minOf(constraints.maxWidth, popoverWidth)
            val panels = measurables.map {
                it.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = tallest[0].coerceIn(0, room), maxHeight = room))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                for (panel in panels) {
                    if (panel.height == 0) continue
                    tallest[0] = maxOf(tallest[0], panel.height)
                    val x = (button.left - origin.x).toInt().coerceIn(0, (constraints.maxWidth - panel.width).coerceAtLeast(0))
                    panel.place(x, if (over) top - gap - panel.height else bottom + gap)
                }
            }
        }
        LaunchedEffect(Unit) {
            // Escape reaches the panel only with the focus in it; a field that took it first keeps it. Two frames:
            // the dialog's panel is only put in on the frame after it opens.
            withFrameNanos {}
            withFrameNanos {}
            if (!focusInside) runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun DialogScope.SheetPanel(
    focus: FocusRequester,
    popover: Boolean,
    onFocusInside: (Boolean) -> Unit,
    dismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    DialogPanel(
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f),
        modifier = Modifier
            .widthIn(max = DESKTOP_SHEET_WIDTH)
            .fillMaxWidth()
            // A popover floats over an undimmed window, so a shadow lifts it off what's under it, as a menu's does.
            .then(if (popover) Modifier.shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.20f)) else Modifier)
            .background(Theme[colors][surfaceElevated], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .onFocusChanged { onFocusInside(it.hasFocus) }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) dismiss()
                escape
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), content = content)
    }
}

/** How wide a sheet opens on the desktop. */
private val DESKTOP_SHEET_WIDTH = 560.dp

/** How wide a sheet opens as a popover over its button. */
private val POPOVER_WIDTH = 520.dp

/** The space between a popover and its button. */
private val POPOVER_GAP = 8.dp

/** Room enough over a button for a popover to open there rather than under it. */
private val POPOVER_MIN_ROOM = 360.dp

@Composable
private fun PhoneSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val stillVisible by rememberUpdatedState(visible)
    val dismiss by rememberUpdatedState(onDismiss)
    val rules = remember { SheetDismissRules() }
    // A tap outside or Back only hides a sheet that has come to rest open, so a second tap on whatever opened it
    // lands on the scrim and does nothing while the sheet is still sliding in. (A drag that catches the sheet
    // mid-slide still closes it: the library falls back to Hidden when it is refused there.)
    val state = rememberModalBottomSheetState(
        initialDetent = SheetDetent.Hidden,
        confirmDetentChange = { it != SheetDetent.Hidden || rules.mayHide(stillVisible) },
    )
    LaunchedEffect(visible) {
        state.targetDetent = if (visible) SheetDetent.FullyExpanded else SheetDetent.Hidden
    }
    // The one place a dismiss is reported, whichever way the user closed the sheet. The library's own onDismiss
    // is not used: it fires twice for a drag next to this, and not at all for some taps outside.
    LaunchedEffect(state) {
        snapshotFlow {
            val hidden = state.currentDetent == SheetDetent.Hidden && state.targetDetent == SheetDetent.Hidden
            state.isIdle to hidden
        }.collect { (idle, hidden) ->
            if (rules.onPosition(idle = idle, hidden = hidden, visible = stillVisible)) dismiss()
        }
    }
    val shape = RoundedCornerShape(topStart = Theme[radii][radiusLarge], topEnd = Theme[radii][radiusLarge])

    UnstyledModalBottomSheet(
        state = state,
        // Constant on purpose: the library adds or drops a pointer input as this value changes, so a value derived
        // from the sheet's state rebuilt it mid-drag and every drag-down was cancelled. confirmDetentChange above
        // holds back early taps instead.
        properties = ModalBottomSheetProperties(dismissOnClickOutside = true),
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
    if (LocalInMenu.current) return MenuHeader(title, subtitle)
    // A desktop panel's title is a heading, not a page's.
    val desktop = LocalDesktopLayout.current
    val heading = Theme[typography][titleStyle]
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = if (desktop) 2.dp else 4.dp, bottom = if (desktop) 8.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = if (desktop) heading.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold) else heading,
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
    if (LocalInMenu.current) return MenuRow(text, icon, onClick, destructive = destructive)
    val tint = if (destructive) Theme[colors][danger] else Theme[colors][textColor]
    UnstyledButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        indication = rememberHoverIndication(tint),
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

/** A [SheetHeader] in a desktop menu: the title in small bold type, the line under it quieter, a rule below. */
@Composable
private fun MenuHeader(title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
        Text(
            title,
            style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold),
            color = Theme[colors][textColor],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = Theme[typography][bodySmall].copy(fontSize = 12.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    MenuSeparator()
}

/**
 * One row of a desktop menu ([MenuPanel], or a [SheetAction] in a desktop [ActionSheet]): compact, the icon bare
 * beside the label. Without an [icon] the label keeps the icon's room, so it lines up with the rows that have one.
 * [keys] names the key that does the same without the menu, at the row's end ("F2").
 */
@Composable
fun MenuRow(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    keys: String? = null,
) {
    val tint = when {
        !enabled -> Theme[colors][textTertiary]
        destructive -> Theme[colors][danger]
        else -> Theme[colors][textColor]
    }
    // The row under the pointer takes the focus, so a menu has one row lit, as a desktop's does, and the arrow keys
    // carry on from where the pointer left off.
    val focus = remember { FocusRequester() }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .heightIn(min = 32.dp)
            .focusRequester(focus)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Enter) runCatching { focus.requestFocus() }
                    }
                }
            },
        indication = rememberHoverIndication(tint),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconTint = if (destructive || !enabled) tint else Theme[colors][textSecondary]
            if (icon != null) UnstyledIcon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            else Spacer(Modifier.size(16.dp))
            Text(
                text,
                style = Theme[typography][bodySmall],
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (keys != null) Modifier.weight(1f, fill = false) else Modifier,
            )
            if (keys != null) {
                Spacer(Modifier.width(12.dp))
                Text(keys, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
    }
}

/** A rule between groups of [MenuRow]s. */
@Composable
fun MenuSeparator() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Theme[colors][stroke]))
}
