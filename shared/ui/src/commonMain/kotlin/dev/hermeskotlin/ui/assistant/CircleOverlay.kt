package dev.hermeskotlin.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.rememberImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Herald's circle to search: the screen as it was when the assistant was called up, frozen and dimmed,
 * to circle (or tap) the part to ask about. The stroke is drawn in the edge's light; once the finger
 * lifts, what was picked lights up for a moment and goes to [onCircled].
 */
@Composable
internal fun CircleOverlay(screen: ScreenCapture, onCircled: (ScreenRegion) -> Unit, onCancel: () -> Unit) {
    val shot = screen.screenshot?.let { rememberImageBitmap(it, maxEdge = 2400) }
    val stroke = remember { mutableStateListOf<Offset>() }
    var picked by remember { mutableStateOf<ScreenRegion?>(null) }
    val scope = rememberCoroutineScope()
    val capture by rememberUpdatedState(screen)
    val circled by rememberUpdatedState(onCircled)
    val edge = LocalAccentPalette.current.edge

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        shot?.let { Image(it, contentDescription = "Your screen", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // Already picked and on its way: touches until then do nothing. Checked after the
                        // touch, not before, or this would spin without ever waiting for one.
                        if (picked != null) return@awaitEachGesture
                        stroke.clear()
                        stroke += down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            stroke += change.position
                            change.consume()
                        }
                        val box = ScreenRegion.around(stroke.map { it.x to it.y }) ?: return@awaitEachGesture
                        // The frozen screen is stretched over this overlay, which needn't be the display's
                        // size; the text's places and the crop are in the display's pixels.
                        val toDisplayX = (capture.displayWidth.takeIf { it > 0 } ?: size.width).toFloat() / size.width
                        val toDisplayY = (capture.displayHeight.takeIf { it > 0 } ?: size.height).toFloat() / size.height
                        val slop = TAP_SLOP.toPx()
                        val region = if (box.width < slop && box.height < slop) {
                            // A tap: the text under the finger, else a square around it.
                            val at = box.centerX to box.centerY
                            capture.items.tapped(at.first * toDisplayX to at.second * toDisplayY)?.bounds
                                ?.scaled(1 / toDisplayX, 1 / toDisplayY)
                                ?.padded(PAD.toPx() / 2, size.width, size.height)
                                ?: ScreenRegion(at.first, at.second, at.first, at.second).padded(TAP_BOX.toPx() / 2, size.width, size.height)
                        } else {
                            box.padded(PAD.toPx(), size.width, size.height)
                        }
                        picked = region
                        scope.launch {
                            delay(HIGHLIGHT_MS)
                            circled(region.scaled(toDisplayX, toDisplayY))
                        }
                    }
                },
        ) {
            // Dimmed a little, so it reads as a frozen picture and the stroke stands out.
            drawRect(Color.Black.copy(alpha = 0.32f))
            picked?.let { region ->
                val corner = CornerRadius(12.dp.toPx())
                for ((width, alpha) in GLOW) {
                    drawRoundRect(
                        edge.bright,
                        topLeft = Offset(region.left, region.top),
                        size = Size(region.width, region.height),
                        cornerRadius = corner,
                        style = Stroke(width.toPx()),
                        alpha = alpha,
                    )
                }
                drawRoundRect(edge.head, Offset(region.left, region.top), Size(region.width, region.height), corner, Stroke(2.dp.toPx()))
            }
            if (picked == null && stroke.size > 1) {
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    // Through the midpoints, so a quick stroke still draws a smooth curve.
                    for (i in 1 until stroke.size) {
                        val mid = (stroke[i - 1] + stroke[i]) / 2f
                        quadraticTo(stroke[i - 1].x, stroke[i - 1].y, mid.x, mid.y)
                    }
                    lineTo(stroke.last().x, stroke.last().y)
                }
                for ((width, alpha) in GLOW) {
                    drawPath(path, edge.bright, alpha = alpha, style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawPath(path, edge.head, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(start = 18.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Circle or tap what to ask about", style = Theme[typography][label], color = Color.White)
            IconButton(Lucide.X, contentDescription = "Stop circling", onClick = onCancel, tint = Color.White)
        }
    }
}

private val TAP_SLOP = 24.dp
private val PAD = 16.dp
private val TAP_BOX = 180.dp
private const val HIGHLIGHT_MS = 350L
private val GLOW = listOf(22.dp to 0.18f, 10.dp to 0.4f)
