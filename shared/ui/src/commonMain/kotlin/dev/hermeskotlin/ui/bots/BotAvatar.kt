package dev.hermeskotlin.ui.bots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotShape
import dev.hermeskotlin.core.bots.botLook
import dev.hermeskotlin.ui.chat.rememberImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A bot's face: its picture when it has one, else Desktop's stock look, a colored shape with two eyes
 * picked from the profile name (or what the user chose on Desktop).
 */
@Composable
fun BotAvatar(bot: Bot, picture: ByteArray?, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val bitmap = picture?.let { rememberImageBitmap(it, maxEdge = 160) }
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)),
        )
        return
    }
    val look = remember(bot.name, bot.uiMeta) { botLook(bot) }
    val fill = Color(0xFF000000 or look.color.toLong())
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            drawPath(shapePath(look.shape, this.size), fill)
            drawEyes(look.shape, fill)
        }
    }
}

private fun shapePath(shape: BotShape, size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        when (shape) {
            BotShape.Circle -> addOval(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
            BotShape.Squircle -> addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(w * 0.32f)))
            BotShape.Pill -> addRoundRect(RoundRect(0f, h * 0.14f, w, h * 0.86f, CornerRadius(h * 0.36f)))
            BotShape.Triangle -> {
                moveTo(w * 0.5f, h * 0.06f)
                lineTo(w * 0.98f, h * 0.9f)
                lineTo(w * 0.02f, h * 0.9f)
                close()
            }
            BotShape.Hexagon -> {
                for (i in 0 until 6) {
                    val angle = PI / 3 * i - PI / 2
                    val x = w / 2 + w / 2 * cos(angle).toFloat()
                    val y = h / 2 + h / 2 * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            BotShape.Cloud -> {
                addOval(androidx.compose.ui.geometry.Rect(0f, h * 0.36f, w * 0.5f, h * 0.86f))
                addOval(androidx.compose.ui.geometry.Rect(w * 0.5f, h * 0.36f, w, h * 0.86f))
                addOval(androidx.compose.ui.geometry.Rect(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.76f))
                addRect(androidx.compose.ui.geometry.Rect(w * 0.25f, h * 0.5f, w * 0.75f, h * 0.86f))
            }
            BotShape.Drop -> {
                moveTo(w * 0.5f, 0f)
                cubicTo(w * 0.62f, h * 0.2f, w * 0.95f, h * 0.42f, w * 0.95f, h * 0.62f)
                cubicTo(w * 0.95f, h * 0.86f, w * 0.75f, h, w * 0.5f, h)
                cubicTo(w * 0.25f, h, w * 0.05f, h * 0.86f, w * 0.05f, h * 0.62f)
                cubicTo(w * 0.05f, h * 0.42f, w * 0.38f, h * 0.2f, w * 0.5f, 0f)
                close()
            }
        }
    }
}

/** Two eyes a little below the middle (lower in the triangle and drop, whose mass sits low). */
private fun DrawScope.drawEyes(shape: BotShape, fill: Color) {
    val w = size.width
    val h = size.height
    val centerY = when (shape) {
        BotShape.Triangle -> h * 0.64f
        BotShape.Drop -> h * 0.64f
        BotShape.Cloud -> h * 0.58f
        else -> h * 0.5f
    }
    val gap = w * 0.13f
    val eyeW = w * 0.1f
    val eyeH = h * 0.15f
    // Light eyes on dark bodies, dark eyes on light ones (avatar.tsx isDarkColor).
    val eye = if (fill.luminanceScore() < 110) Color.White.copy(alpha = 0.92f) else Color(0xFF1B1530)
    listOf(w / 2 - gap, w / 2 + gap).forEach { x ->
        drawRoundRect(
            eye,
            topLeft = Offset(x - eyeW / 2, centerY - eyeH / 2),
            size = Size(eyeW, eyeH),
            cornerRadius = CornerRadius(eyeW / 2),
        )
    }
}

private fun Color.luminanceScore(): Float = 0.2126f * red * 255 + 0.7152f * green * 255 + 0.0722f * blue * 255
