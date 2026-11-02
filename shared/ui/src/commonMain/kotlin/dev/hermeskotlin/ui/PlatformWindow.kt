package dev.hermeskotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether the on-screen keyboard is showing. */
internal expect val imeVisible: Boolean
    @Composable get

/** A dialog that covers the whole window, under the system bars too (the media viewer). */
internal expect fun fullScreenDialogProperties(): DialogProperties

/** Whether there's a real keyboard to write with, so Enter sends and Shift+Enter starts a line (the desktop). */
internal expect val enterSends: Boolean

/** The desktop app: a window, a keyboard and a mouse, and none of the phone's own features. */
internal val isDesktop: Boolean get() = enterSends

/**
 * A rounded panel over the chat: [blur] of what's beneath, tinted with [fill], inside a 1dp [edge].
 *
 * A desktop's clip isn't smoothed (Skia clips without anti-aliasing), so there the outline is drawn as shapes,
 * which are: the fill, then the border over it, and the clip only inside the border, where its steps meet the
 * same fill. A phone's clip is smooth, so it clips the whole panel as before.
 */
internal fun Modifier.frostedPanel(shape: Shape, fill: Color, edge: Color, blur: Modifier = Modifier): Modifier =
    if (isDesktop) {
        background(fill, shape)
            .smoothEdge(edge, shape)
            .padding(1.dp)
            .clip(shape)
            .then(blur)
            .background(fill)
    } else {
        clip(shape)
            .then(blur)
            .background(fill)
            .border(1.dp, edge, shape)
    }

/**
 * A 1dp outline over the content, a little heavier than 1px: a 1px line shades its curves over two pixels at
 * part strength, so the rounded corners read fainter than the straight sides. The extra keeps them even.
 */
private fun Modifier.smoothEdge(color: Color, shape: Shape): Modifier = drawWithCache {
    val width = 1.dp.toPx() * EDGE_WEIGHT
    val outline = shape.createOutline(Size(size.width - width, size.height - width), layoutDirection, this)
    onDrawWithContent {
        drawContent()
        translate(width / 2, width / 2) { drawOutline(outline, color, style = Stroke(width)) }
    }
}

private const val EDGE_WEIGHT = 1.35f

/** A microphone and a speaker for voice: the desktop has neither yet, so dictation, voice chat and reading aloud stay off there. */
internal val hasVoice: Boolean get() = !isDesktop

/** Room under a page's list: a phone's keeps its last row clear of the thumb's reach; a desktop's needs only a margin. */
internal val PageFoot: Dp get() = if (isDesktop) 24.dp else 96.dp

/** A page's list row: a thumb's 56dp on a phone, a pointer's 44dp on a desktop. */
internal val PageRowHeight: Dp get() = if (isDesktop) 44.dp else 56.dp

/** What a hint says the user does to a control: "Click" with a mouse, "Tap" on a phone. */
internal val tapVerb: String get() = if (isDesktop) "Click" else "Tap"

/** What a file's Share does: a phone's share sheet, or on the desktop, which has none, the app that opens it. */
internal val shareLabel: String get() = if (isDesktop) "Open in app" else "Share"

/** [shareLabel]'s icon. */
internal val shareIcon: ImageVector get() = if (isDesktop) Lucide.ExternalLink else Lucide.Share2

/** What Herald runs on, for text that says where something is kept: "phone", or "computer" on the desktop. */
internal val deviceNoun: String get() = if (isDesktop) "computer" else "phone"

/**
 * Text a user may want to copy (an error, a path, a command) made selectable on the desktop, where a drag selects
 * and a right-click copies; a phone's is as it was.
 */
@Composable
internal fun CopyableOnDesktop(content: @Composable () -> Unit) {
    if (isDesktop) SelectionContainer(content = content) else content()
}

/**
 * A list whose rows Up and Down step through on the desktop, as Tab does, where the window moves the focus with
 * Tab alone; Enter then opens the focused row. A phone's list is as it was.
 */
@Composable
internal fun Modifier.arrowFocus(): Modifier {
    if (!isDesktop) return this
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionUp -> FocusDirection.Up
            else -> null
        }
        if (event.type != KeyEventType.KeyDown || direction == null || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return@onPreviewKeyEvent false
        focusManager.moveFocus(direction)
    }
}

/** The menu text gets on a right-click: the app's own on a desktop; a phone keeps the system's toolbar. */
@Composable
internal expect fun PlatformTextContextMenu(content: @Composable () -> Unit)

/** Whether shortcuts are written the Mac's way, ⌘ for Ctrl. */
internal expect val macKeys: Boolean
