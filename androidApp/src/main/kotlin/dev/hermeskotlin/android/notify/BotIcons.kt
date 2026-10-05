package dev.hermeskotlin.android.notify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotShape
import dev.hermeskotlin.core.bots.botLook
import dev.hermeskotlin.core.bots.showsPicture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A bot's face as an Android icon, for its notifications and shortcuts: its picture when it has one,
 * else the same coloured shape with eyes the app draws. Shortcuts and conversation icons get cropped to
 * a circle by the launcher, so the face sits on a soft tint of its colour with room around it.
 */
object BotIcons {

    fun icon(bot: Bot, picture: ByteArray?, adaptive: Boolean = false): IconCompat {
        val bitmap = picture?.takeIf { showsPicture(bot, it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: drawn(bot)
        return if (adaptive) IconCompat.createWithAdaptiveBitmap(padded(bitmap, bot)) else IconCompat.createWithBitmap(bitmap)
    }

    private fun drawn(bot: Bot, size: Int = 192): Bitmap {
        val look = botLook(bot)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(look.color shr 16 and 0xFF, look.color shr 8 and 0xFF, look.color and 0xFF) }
        canvas.drawPath(shape(look.shape, size.toFloat()), fill)
        val dark = 0.2126f * Color.red(fill.color) + 0.7152f * Color.green(fill.color) + 0.0722f * Color.blue(fill.color) < 110
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.argb(235, 255, 255, 255) else Color.rgb(0x1B, 0x15, 0x30) }
        val w = size.toFloat()
        val cy = when (look.shape) {
            BotShape.Triangle, BotShape.Drop -> w * 0.64f
            BotShape.Cloud -> w * 0.58f
            else -> w * 0.5f
        }
        val ew = w * 0.1f
        val eh = w * 0.15f
        listOf(w / 2 - w * 0.13f, w / 2 + w * 0.13f).forEach { x ->
            canvas.drawRoundRect(RectF(x - ew / 2, cy - eh / 2, x + ew / 2, cy + eh / 2), ew / 2, ew / 2, eye)
        }
        return bitmap
    }

    /** The face at the middle 60% of a tinted square, the adaptive-icon safe zone. */
    private fun padded(face: Bitmap, bot: Bot, size: Int = 216): Bitmap {
        val look = botLook(bot)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.argb(56, look.color shr 16 and 0xFF, look.color shr 8 and 0xFF, look.color and 0xFF))
        canvas.drawColor(Color.argb(200, 24, 24, 27), android.graphics.PorterDuff.Mode.DST_OVER)
        val inset = size * 0.2f
        canvas.drawBitmap(face, null, RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    private fun shape(shape: BotShape, w: Float): Path = Path().apply {
        val h = w
        when (shape) {
            BotShape.Circle -> addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
            BotShape.Squircle -> addRoundRect(RectF(0f, 0f, w, h), w * 0.32f, w * 0.32f, Path.Direction.CW)
            BotShape.Pill -> addRoundRect(RectF(0f, h * 0.14f, w, h * 0.86f), h * 0.36f, h * 0.36f, Path.Direction.CW)
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
                addOval(RectF(0f, h * 0.36f, w * 0.5f, h * 0.86f), Path.Direction.CW)
                addOval(RectF(w * 0.5f, h * 0.36f, w, h * 0.86f), Path.Direction.CW)
                addOval(RectF(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.76f), Path.Direction.CW)
                addRect(RectF(w * 0.25f, h * 0.5f, w * 0.75f, h * 0.86f), Path.Direction.CW)
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
