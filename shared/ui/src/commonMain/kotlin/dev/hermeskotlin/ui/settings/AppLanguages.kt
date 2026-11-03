package dev.hermeskotlin.ui.settings

/**
 * A language Kyllene ships: its BCP 47 [tag] as `locales_config.xml` lists it, and its name in itself ([endonym]),
 * which the picker shows untranslated so anyone can find their own language whatever the app is in.
 */
data class AppLanguage(val tag: String, val endonym: String) {
    val language: String get() = tagParts(tag).first
    val region: String? get() = tagParts(tag).second

    /** The folder under `composeResources/` with its strings: `values` for English, `values-pt-rBR` for `pt-BR`. */
    val resourceFolder: String
        get() = if (this == English) "values" else "values-$language" + (region?.let { "-r$it" } ?: "")
}

private val English = AppLanguage("en", "English")

/**
 * Every language the app ships, in the picker's order: the one list the picker, `locales_config.xml` and the
 * `values-*` folders are checked against (AppLanguagesTest), so a language added to one can't miss the others.
 */
val AppLanguages: List<AppLanguage> = listOf(
    English,
    AppLanguage("de", "Deutsch"),
    AppLanguage("fr", "Français"),
    AppLanguage("es", "Español"),
    AppLanguage("pt-BR", "Português (Brasil)"),
    AppLanguage("zh-CN", "简体中文"),
    AppLanguage("hu", "Magyar"),
)

/**
 * The shipped language [tag] names, or null: for no tag (System default), and for a tag the app doesn't ship (one
 * stored before its language was removed), which then reads as System default too. A script in the tag is skipped
 * (`zh-Hans-CN` is `zh-CN`); a region must match where the language has one (`pt-PT` isn't `pt-BR`).
 */
fun appLanguage(tag: String?): AppLanguage? {
    if (tag.isNullOrBlank()) return null
    val (language, region) = tagParts(tag)
    return AppLanguages.firstOrNull { it.language == language && it.region == region }
        ?: AppLanguages.firstOrNull { it.language == language && it.region == null }
}

/**
 * The language the app's strings come out in when the system's locale is [language] and [region] and nothing is
 * picked, as Compose resources choose a folder: the language with its region, else the language alone, else English.
 */
fun systemAppLanguage(language: String, region: String?): AppLanguage {
    val lang = language.lowercase()
    val reg = region?.uppercase()?.takeIf { it.isNotEmpty() }
    return AppLanguages.firstOrNull { it.language == lang && it.region == reg }
        ?: AppLanguages.firstOrNull { it.language == lang && it.region == null }
        ?: English
}

/**
 * Android 13+: the tags for `LocaleList.forLanguageTags` that pick [tag] as the app's language. Empty for System
 * default (an empty per-app list), and for a tag the app doesn't ship.
 */
fun perAppLocaleTags(tag: String?): String = appLanguage(tag)?.tag.orEmpty()

/**
 * Android 13+: what the per-app locale list ([tags], as `LocaleList.toLanguageTags` writes it) picks: the shipped
 * language's tag, or null for an empty list (System default) or a language the app doesn't ship.
 */
fun pickedFromPerAppLocales(tags: String): String? =
    tags.split(',').firstOrNull { it.isNotBlank() }?.let { appLanguage(it.trim())?.tag }

/** The language and region (upper case, or null) of a BCP 47 tag; a script (`Hans`) and anything after are skipped. */
private fun tagParts(tag: String): Pair<String, String?> {
    val parts = tag.replace('_', '-').split('-').filter { it.isNotEmpty() }
    val language = parts.firstOrNull().orEmpty().lowercase()
    val region = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
    return language to region?.uppercase()
}
