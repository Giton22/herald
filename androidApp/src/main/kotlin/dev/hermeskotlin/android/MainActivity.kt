package dev.hermeskotlin.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import dev.hermeskotlin.ui.App

class MainActivity : ComponentActivity() {

    // The last theme shown, read synchronously so the window behind the first frame already matches it.
    private val windowPrefs by lazy { getSharedPreferences("window", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (windowPrefs.contains(KEY_DARK)) applyWindowTheme(windowPrefs.getBoolean(KEY_DARK, false))
        setContent {
            App(onDarkTheme = { dark ->
                applyWindowTheme(dark)
                windowPrefs.edit { putBoolean(KEY_DARK, dark) }
            })
        }
    }

    /** The app theme can differ from the system's, so the window and bar icons follow the app. */
    private fun applyWindowTheme(dark: Boolean) {
        window.setBackgroundDrawableResource(if (dark) R.color.window_background_dark else R.color.window_background_light)
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private companion object {
        const val KEY_DARK = "dark"
    }
}
