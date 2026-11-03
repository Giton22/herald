package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.ui.settings.AppLanguage
import dev.hermeskotlin.ui.settings.appLanguage
import org.koin.compose.koinInject

/**
 * The language the screens under it are drawn in, in Compose Multiplatform's pattern for switching it inside the
 * app: the app's content goes in `CompositionLocalProvider(LocalAppLocale provides tag) { key(tag) { … } }`, so every
 * `stringResource` reads its text again in the new language.
 */
expect object LocalAppLocale {
    /**
     * [tag] (a shipped language's) for the screens below and the process's default locale; null: the system's.
     * On Android 13 and later the system applies the app's language itself, and this leaves it be.
     */
    @Composable
    infix fun provides(tag: String?): ProvidedValue<*>
}

/** The app's language where the platform keeps it: Settings › Language reads and picks it here. */
interface LanguageChoice {
    /** The picked language's tag; null for System default. */
    val picked: String?

    /** The language the system gives the app when none is picked. */
    val system: AppLanguage

    /** Picks [tag]'s language, or System default for null. */
    fun pick(tag: String?)
}

/** Android 13 and later: the per-app language the system keeps. Null elsewhere, where the app's settings keep it. */
@Composable
internal expect fun rememberSystemLanguageChoice(): LanguageChoice?

/** The language the system gives the app when none is picked in it. */
internal expect fun systemLanguage(): AppLanguage

/** Where the app's language is picked: the system's per-app language where there is one, the app's settings elsewhere. */
@Composable
internal fun rememberLanguageChoice(): LanguageChoice {
    val perApp = rememberSystemLanguageChoice()
    val store = koinInject<SettingsStore>()
    val settings by store.settings.collectAsStateWithLifecycle()
    if (perApp != null) return perApp
    val picked = appLanguage(settings?.language)?.tag
    return remember(store, picked) {
        object : LanguageChoice {
            override val picked = picked
            override val system = systemLanguage()
            override fun pick(tag: String?) {
                val next = appLanguage(tag)?.tag
                if (next == picked) return
                LanguagePageReturn.pending = true
                store.update { it.copy(language = next) }
            }
        }
    }
}

/**
 * A language picked in the app's settings draws the screens afresh ([ProvideAppLanguage]'s key), which would
 * leave the user on the chat. This brings them back to Settings › Language, where they picked it: Settings opens
 * again while [pending], and the Language page [take]s it. (Android 13 and later recreate the activity instead,
 * whose saved state keeps both open.)
 */
internal object LanguagePageReturn {
    var pending: Boolean = false

    fun take(): Boolean = pending.also { pending = false }
}

/**
 * [content] in the [stored] language (a shipped language's tag; null or any other: the system's). Keyed on it, so
 * every string is read again when it changes, including those a screen keeps in a `remember`.
 */
@Composable
internal fun ProvideAppLanguage(stored: String?, content: @Composable () -> Unit) {
    val tag = appLanguage(stored)?.tag
    CompositionLocalProvider(LocalAppLocale provides tag) { key(tag) { content() } }
}
