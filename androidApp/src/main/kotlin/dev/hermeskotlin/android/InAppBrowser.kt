package dev.hermeskotlin.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import androidx.core.content.ContextCompat

/**
 * Opens links from the app's screens. Web pages open in a Custom Tab over Herald, in its colors, so
 * reading a link doesn't leave the chat; anything else (mail, an APK download, no browser with Custom
 * Tabs) goes to whichever app handles it. [dark] is the theme the app shows now.
 */
class InAppBrowser(private val activity: Activity, private val dark: () -> Boolean) : UriHandler {

    override fun openUri(uri: String) {
        val parsed = Uri.parse(uri)
        if (opensInTab(parsed)) {
            try {
                customTab().launchUrl(activity, parsed)
                return
            } catch (_: ActivityNotFoundException) {
                // Nothing takes it as a tab; try a plain view below.
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, parsed))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app on this phone can open that link.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun customTab(): CustomTabsIntent {
        fun colors(color: Int) = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(activity, color))
            .setNavigationBarColor(ContextCompat.getColor(activity, color))
            .build()
        return CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(if (dark()) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, colors(R.color.window_background_light))
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, colors(R.color.window_background_dark))
            .build()
    }

    companion object {
        /** Web pages, but not an APK download (the update banner's), which a browser hands to the installer. */
        fun opensInTab(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && uri.path?.endsWith(".apk", ignoreCase = true) != true
        }
    }
}
