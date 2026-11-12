package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.hermeskotlin.ui.settings.AppLanguage
import dev.hermeskotlin.ui.settings.appLanguage
import dev.hermeskotlin.ui.settings.systemAppLanguage
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.countryCode
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages

actual object LocalAppLocale {
    // Static: a new language recomposes everything under it, which then reads the new AppleLanguages.
    private val Local = staticCompositionLocalOf<String?> { null }

    @Composable
    actual infix fun provides(tag: String?): ProvidedValue<*> {
        applyAppLanguage(tag)
        return Local provides appLanguage(tag)?.tag
    }
}

/** The app's preferred languages, which Compose resources and NSLocale read their language from. */
private const val LANGUAGES = "AppleLanguages"

/**
 * The languages the app had before a pick, kept while one stands in for them: present only while one does. It names
 * System default's language; it isn't read again if the phone's language changes meanwhile.
 */
private const val SYSTEM_LANGUAGES = "app.language.system"

/** The app's own AppleLanguages before a pick (iOS's per-app language), put back on System default. */
private const val PRIOR_LANGUAGES = "app.language.prior"

/**
 * Makes [tag]'s language (null: the system's) the app's first preferred language. Picking one keeps what stood
 * before; System default puts that back, so iOS's own per-app language survives a pick and its undoing.
 */
private fun applyAppLanguage(tag: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    val picked = appLanguage(tag)?.tag
    if (picked == null) {
        if (defaults.objectForKey(SYSTEM_LANGUAGES) != null) {
            val prior = defaults.objectForKey(PRIOR_LANGUAGES)
            if (prior != null) defaults.setObject(prior, LANGUAGES) else defaults.removeObjectForKey(LANGUAGES)
            defaults.removeObjectForKey(PRIOR_LANGUAGES)
            defaults.removeObjectForKey(SYSTEM_LANGUAGES)
        }
        return
    }
    if (defaults.objectForKey(SYSTEM_LANGUAGES) == null) {
        defaults.setObject(NSLocale.preferredLanguages, SYSTEM_LANGUAGES)
        // The app's own domain only: objectForKey would also find the global list every app has.
        appDomain(defaults)?.get(LANGUAGES)?.let { defaults.setObject(it, PRIOR_LANGUAGES) }
    }
    // Parsed: iOS hands back its own form of a tag (zh-Hans-CN for zh-CN), which would never equal the pick.
    if (appLanguage(NSLocale.preferredLanguages.firstOrNull() as? String)?.tag != picked) defaults.setObject(listOf(picked), LANGUAGES)
}

private fun appDomain(defaults: NSUserDefaults): Map<Any?, *>? =
    NSBundle.mainBundle.bundleIdentifier?.let { defaults.persistentDomainForName(it) }

internal actual fun systemLanguage(): AppLanguage {
    val kept = NSUserDefaults.standardUserDefaults.stringArrayForKey(SYSTEM_LANGUAGES)
    val id = (kept ?: NSLocale.preferredLanguages).firstOrNull() as? String ?: "en"
    val locale = NSLocale(localeIdentifier = id)
    return systemAppLanguage(locale.languageCode, locale.countryCode)
}

// iOS keeps no per-app language the app can set, so the app's settings keep it.
@Composable
internal actual fun rememberSystemLanguageChoice(): LanguageChoice? = null
