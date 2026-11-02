package dev.hermeskotlin.designsystem

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.launch

/**
 * What a pointer over something shows: [color] as a light tint. Presses draw nothing, as they always have here.
 * Focus reached from the keyboard (Tab, on a desktop) draws a ring in the accent, so the user sees where they
 * are; a click doesn't focus a button, so the ring never follows the mouse.
 *
 * Use this, not Compose Unstyled's `rememberColoredIndication(color)`: with one color that call takes it as the hover
 * color as it is, so on a desktop, which hovers, the whole control turned that color and hid what was on it.
 */
@Composable
fun rememberHoverIndication(color: Color): IndicationNodeFactory = rememberHoverIndication(color, Theme[colors][accent])

/**
 * The same for a control clipped to [shape]: the keyboard's ring follows that shape's edge, so a pill's or a
 * rounded button's ring has its corners rather than a square cut off by the clip.
 */
@Composable
fun rememberHoverIndication(color: Color, shape: Shape): IndicationNodeFactory = rememberHoverIndication(color, Theme[colors][accent], shape)

/** The same with the focus ring's [ring] color given, for the theme's own default, set before the theme is. */
@Composable
fun rememberHoverIndication(color: Color, ring: Color, shape: Shape? = null): IndicationNodeFactory =
    remember(color, ring, shape) { HoverIndication(color.copy(alpha = color.alpha * HOVER_ALPHA), ring, shape) }

private class HoverIndication(private val hover: Color, private val ring: Color, private val shape: Shape?) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HoverIndicationNode(interactionSource, hover, ring, shape)

    override fun equals(other: Any?) = other is HoverIndication && other.hover == hover && other.ring == ring && other.shape == shape

    override fun hashCode() = 31 * (31 * hover.hashCode() + ring.hashCode()) + shape.hashCode()
}

private class HoverIndicationNode(
    private val interactions: InteractionSource,
    private val hover: Color,
    private val ring: Color,
    private val shape: Shape?,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    // What is hovering, pressed or focused now, each Enter, Press or Focus until its own end. A node made while the
    // pointer was already on the control (a theme change remakes them) sees only the Exit, which finds nothing.
    private val hovers = mutableListOf<HoverInteraction.Enter>()
    private val presses = mutableListOf<PressInteraction.Press>()
    private val focuses = mutableListOf<FocusInteraction.Focus>()

    override fun onAttach() {
        coroutineScope.launch {
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovers += interaction
                    is HoverInteraction.Exit -> hovers -= interaction.enter
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    override fun onDetach() {
        hovers.clear()
        presses.clear()
        focuses.clear()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val desktop = currentValueOf(LocalDesktopLayout)
        // A desktop's mouse press darkens the tint a step, so a click is seen landing; a phone keeps none, as ever.
        if (desktop && presses.isNotEmpty()) drawRect(hover.copy(alpha = hover.alpha * PRESS_FACTOR))
        else if (hovers.isNotEmpty()) drawRect(hover)
        // Only with a desktop's keyboard: a phone draws no focus, as it never has, and focus a click moved (a sheet
        // opened with the mouse focusing its first row) shows none.
        val keyboard = currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard
        if (focuses.isNotEmpty() && keyboard && desktop) {
            val width = FOCUS_RING.toPx()
            if (shape != null) {
                // On the shape's edge at twice the width: the clip takes the outer half, leaving the ring inside.
                drawOutline(shape.createOutline(size, layoutDirection, this), ring, style = Stroke(width * 2))
            } else {
                // Inside the edge, so whatever clips the control keeps the ring.
                drawRect(ring, topLeft = Offset(width / 2, width / 2), size = Size(size.width - width, size.height - width), style = Stroke(width))
            }
        }
    }
}

private const val HOVER_ALPHA = 0.08f

/** How much deeper than the hover tint a press is: 0.14 over 0.08. */
private const val PRESS_FACTOR = 1.75f

/** How thick the ring around the control the keyboard is on is. */
private val FOCUS_RING = 2.dp
