package dev.hermeskotlin.android.notify

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.R

/** One mutable shortcut: its destination is read from Settings when tapped, including pinned copies. */
internal object CallShortcut {
    const val ID = "default_call"

    fun info(context: Context, picture: ByteArray? = null): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ID)
            .apply { context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let(::setActivity) }
            .setShortLabel(context.getString(R.string.shortcut_voice_chat))
            .setIcon(icon(context, picture))
            .setIntent(Intent(Intent.ACTION_VIEW, "hermes://voice-chat".toUri(), context, HeraldActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            .setRank(0)
            .build()

    private fun icon(context: Context, picture: ByteArray?): IconCompat = pictureBitmap(context, picture)
        ?.let(IconCompat::createWithAdaptiveBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_shortcut_call)

    fun pictureBitmap(context: Context, picture: ByteArray?): android.graphics.Bitmap? {
        val photo = picture?.let {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, options)
            // Decode only the pixels needed for an icon, even when the bot uploads a full-size photograph.
            options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 216).coerceAtLeast(1)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(it, 0, it.size, options)
        }
            ?: return null
        val bitmap = createBitmap(216, 216)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 24, 27))
        val side = minOf(photo.width, photo.height)
        val left = (photo.width - side) / 2
        val top = (photo.height - side) / 2
        canvas.drawBitmap(photo, android.graphics.Rect(left, top, left + side, top + side),
            RectF(43f, 43f, 173f, 173f), Paint(Paint.FILTER_BITMAP_FLAG))
        // Upper left, inside the adaptive safe zone: the launcher owns the app badge at lower right.
        canvas.drawCircle(78f, 78f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3B, 0x82, 0xF6) })
        ContextCompat.getDrawable(context, R.drawable.ic_shortcut_call_foreground)?.apply {
            setBounds(28, 28, 128, 128)
            draw(canvas)
        }
        photo.recycle()
        return bitmap
    }
}
