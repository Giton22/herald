package dev.hermeskotlin.android.assist

import android.app.assist.AssistStructure
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.View
import dev.hermeskotlin.ui.assistant.ScreenItem
import dev.hermeskotlin.ui.assistant.ScreenRegion
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Turns what Android hands the assistant into what the panel sends: the screen's text and a small JPEG. */
internal object ScreenReader {

    /**
     * The text of every visible view, window by window, in the order the app laid them out, each with
     * where it sits on the display (for circling).
     */
    fun items(structure: AssistStructure): List<ScreenItem> {
        val out = ArrayList<ScreenItem>()
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            window.rootViewNode?.let { collect(it, window.left.toFloat(), window.top.toFloat(), out) }
        }
        return out
    }

    /** [x], [y]: where [node]'s parent puts its children's origin on the display. */
    private fun collect(node: AssistStructure.ViewNode, x: Float, y: Float, out: MutableList<ScreenItem>) {
        if (node.visibility != View.VISIBLE) return
        var left = x + node.left
        var top = y + node.top
        // Animated or translated views carry a matrix; its offset is what moves them on screen.
        node.transformation?.let { matrix ->
            val values = FloatArray(9).also(matrix::getValues)
            left += values[Matrix.MTRANS_X]
            top += values[Matrix.MTRANS_Y]
        }
        // A field's own text, else what it's called for screen readers (icon buttons, images).
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) out += ScreenItem(text, ScreenRegion(left, top, left + node.width, top + node.height))
        for (i in 0 until node.childCount) collect(node.getChildAt(i), left - node.scrollX, top - node.scrollY, out)
    }

    /** [region] of [screenshot] (display pixels, clamped to it) as a JPEG no bigger than the full one would be. */
    fun crop(screenshot: Bitmap, region: ScreenRegion): ByteArray? {
        val left = region.left.toInt().coerceIn(0, screenshot.width - 1)
        val top = region.top.toInt().coerceIn(0, screenshot.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, screenshot.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, screenshot.height)
        // Cut from a software copy: hardware bitmaps can't be cut or compressed directly.
        val soft = if (screenshot.config == Bitmap.Config.HARDWARE) screenshot.copy(Bitmap.Config.ARGB_8888, false) else screenshot
        return jpeg(Bitmap.createBitmap(soft, left, top, right - left, bottom - top))
    }

    /** The app in front by its launcher name, or its package when Herald may not look it up. */
    fun appName(structure: AssistStructure, packageManager: PackageManager): String? {
        val pkg = structure.activityComponent?.packageName ?: return null
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /** Scaled so its long edge is at most [MAX_EDGE]: plenty for a model to read, small to send. */
    fun jpeg(screenshot: Bitmap): ByteArray {
        val scale = MAX_EDGE.toFloat() / max(screenshot.width, screenshot.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(screenshot, (screenshot.width * scale).toInt(), (screenshot.height * scale).toInt(), true)
        } else {
            screenshot
        }
        // Hardware bitmaps can't be compressed directly.
        val soft = if (sized.config == Bitmap.Config.HARDWARE) sized.copy(Bitmap.Config.ARGB_8888, false) else sized
        return ByteArrayOutputStream().use { stream ->
            soft.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
    }

    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 82
}
