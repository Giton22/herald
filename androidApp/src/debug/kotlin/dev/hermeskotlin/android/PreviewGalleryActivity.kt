package dev.hermeskotlin.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.hermeskotlin.ui.preview.HeraldPreview
import dev.hermeskotlin.ui.preview.PreviewScene

/**
 * Debug builds only: shows one sample screen full-screen, for the README screenshots.
 * `adb shell am start -n dev.herald.android/dev.hermeskotlin.android.PreviewGalleryActivity --es scene Reply --ez dark true`
 */
class PreviewGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra("dark", true)
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        super.onCreate(savedInstanceState)
        val scene = PreviewScene.entries.firstOrNull { it.name == intent.getStringExtra("scene") } ?: PreviewScene.Reply
        setContent { HeraldPreview(scene, dark) }
    }
}
