package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.hermeskotlin.ui.settings.AppLanguage
import dev.hermeskotlin.ui.settings.appLanguage
import dev.hermeskotlin.ui.settings.systemAppLanguage
import java.util.Locale

actual object LocalAppLocale {
    // Static: a new language recomposes everything under it, which then reads the new default locale.
    private val Local = staticCompositionLocalOf<String?> { null }

    @Composable
    actual infix fun provides(tag: String?): ProvidedValue<*> {
        applyAppLanguage(tag)
        return Local provides appLanguage(tag)?.tag
    }
}

/** The JVM's locale as the system gave it, kept before a picked language replaces the default. */
private object SystemLocale {
    val locale: Locale = Locale.getDefault()
}

/**
 * Makes [tag]'s language (null: the system's) the JVM's default locale, which Compose resources read their text by.
 * The desktop calls it at start-up, before anything reads a string, so the first window is already in it.
 */
fun applyAppLanguage(tag: String?) {
    // Kept first, while the default is still the system's: the first call may be the one that replaces it.
    val system = SystemLocale.locale
    val locale = appLanguage(tag)?.let { Locale.forLanguageTag(it.tag) } ?: system
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
}

internal actual fun systemLanguage(): AppLanguage = SystemLocale.locale.let { systemAppLanguage(it.language, it.country) }

@Composable
internal actual fun rememberSystemLanguageChoice(): LanguageChoice? = null
