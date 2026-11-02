package dev.hermeskotlin.ui.i18n

import androidx.compose.runtime.mutableStateOf
import dev.hermeskotlin.core.settings.AppLanguage

/**
 * What the interface says, in the language the user picked. English is written in the code itself; a translation
 * only holds the strings that differ, and anything missing falls back to the code's own words.
 */
object Strings {
    private val table = mutableStateOf<Map<String, String>>(emptyMap())

    /** [language] as the tables to use: [AppLanguage.System] resolves through [deviceLanguage]. */
    fun setLanguage(language: AppLanguage) {
        val resolved = if (language == AppLanguage.System) deviceLanguage() else language
        table.value = when (resolved) {
            AppLanguage.Chinese -> ChineseStrings
            else -> emptyMap()
        }
    }

    /** [text] in the chosen language, or as written when it has no translation. */
    fun t(text: String): String = table.value[text] ?: text

    /** [text] with `{0}`, `{1}`… replaced by [args], in the chosen language. */
    fun t(text: String, vararg args: Any?): String {
        var out = t(text)
        args.forEachIndexed { index, arg -> out = out.replace("{$index}", arg.toString()) }
        return out
    }
}
