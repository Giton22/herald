package dev.hermeskotlin.android.assist

import android.app.assist.AssistStructure
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.View
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Turns what Android hands the assistant into what the panel sends: the screen's text and a small JPEG. */
internal object ScreenReader {

    /** The text of every visible view, window by window, in the order the app laid them out. */
    fun lines(structure: AssistStructure): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let { collect(it, out) }
        }
        return out
    }

    private fun collect(node: AssistStructure.ViewNode, out: MutableList<String>) {
        if (node.visibility != View.VISIBLE) return
        // A field's own text, else what it's called for screen readers (icon buttons, images).
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) out += text
        for (i in 0 until node.childCount) collect(node.getChildAt(i), out)
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
