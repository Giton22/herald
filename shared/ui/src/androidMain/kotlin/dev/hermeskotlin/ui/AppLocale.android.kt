package dev.hermeskotlin.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.hermeskotlin.ui.settings.AppLanguage
import dev.hermeskotlin.ui.settings.appLanguage
import dev.hermeskotlin.ui.settings.perAppLocaleTags
import dev.hermeskotlin.ui.settings.pickedFromPerAppLocales
import dev.hermeskotlin.ui.settings.systemAppLanguage
import java.util.Locale

/** Android 13 and later keep the app's language themselves (Settings › Apps › Kyllene › Language). */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
val systemKeepsAppLanguage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

actual object LocalAppLocale {
    // Static and equal while the language is: Compose resources read the process's default locale, which
    // applyAppLanguage sets, and the key over the screens has them read it again. Not LocalConfiguration, whose
    // never-equal policy would redraw every string each time the app's root recomposes.
    private val Local = staticCompositionLocalOf<String?> { null }

    @Composable
    actual infix fun provides(tag: String?): ProvidedValue<*> {
        // On Android 13 and later the system recreates the activity in the app's language; nothing to lay over it.
        if (systemKeepsAppLanguage) return Local provides null
        // Read so a configuration change (the phone's own language) puts the picked one back over it.
        LocalConfiguration.current
        applyAppLanguage(LocalContext.current, tag)
        return Local provides appLanguage(tag)?.tag
    }
}

/**
 * Android 12 and older: makes [tag]'s language (null: the system's) the process's default locale, and the locale of
 * [context]'s resources and the application's, so text read outside Compose follows it too. Returns that locale.
 * Does nothing on Android 13 and later, where the system applies the per-app language.
 */
fun applyAppLanguage(context: Context, tag: String?): Locale {
    if (systemKeepsAppLanguage) return Locale.getDefault()
    val locale = appLanguage(tag)?.let { Locale.forLanguageTag(it.tag) } ?: systemLocale()
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
    listOf(context, context.applicationContext).distinct().forEach { it.resources.useLocale(locale) }
    return locale
}

/**
 * Android 13 and later: a language picked in the app on Android 12 or older ([stored]) becomes the system's per-app
 * language after the phone's update, unless one is set there already. True when there was a stored one to hand
 * over, which the caller then clears.
 */
fun handOverStoredLanguage(context: Context, stored: String?): Boolean {
    if (!systemKeepsAppLanguage || stored == null) return false
    val manager = context.getSystemService(LocaleManager::class.java)
    val tag = appLanguage(stored)?.tag
    if (tag != null && manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags(tag)
    return true
}

@Suppress("DEPRECATION") // The only way to change a live Resources' locale before Android 13's per-app languages.
private fun Resources.useLocale(locale: Locale) {
    if (configuration.locales[0] == locale) return
    val updated = Configuration(configuration).apply { setLocale(locale) }
    updateConfiguration(updated, displayMetrics)
}

/** The phone's own first language: the system's resources, which a per-app language or [applyAppLanguage] leave alone. */
private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

internal actual fun systemLanguage(): AppLanguage = systemLocale().let { systemAppLanguage(it.language, it.country) }

@Composable
internal actual fun rememberSystemLanguageChoice(): LanguageChoice? {
    if (!systemKeepsAppLanguage) return null
    val context = LocalContext.current
    // Read again when the configuration changes: Android's own Language page changes it too.
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { PerAppLanguage(context) }
}

/** The system's per-app language: Android's own Language page and Settings › Language show and change the same one. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PerAppLanguage(context: Context) : LanguageChoice {
    private val manager = context.getSystemService(LocaleManager::class.java)
    override val picked: String? = pickedFromPerAppLocales(manager.applicationLocales.toLanguageTags())
    override val system: AppLanguage = systemLanguage()

    // The system recreates the activity in the new language, so the screens read it from there.
    override fun pick(tag: String?) {
        manager.applicationLocales = LocaleList.forLanguageTags(perAppLocaleTags(tag))
    }
}
