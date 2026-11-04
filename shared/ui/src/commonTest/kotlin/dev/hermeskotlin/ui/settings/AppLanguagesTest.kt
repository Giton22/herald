package dev.hermeskotlin.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLanguagesTest {

    @Test
    fun aTagNamesTheShippedLanguageOrNone() {
        assertEquals("de", appLanguage("de")?.tag)
        assertEquals("pt-BR", appLanguage("pt-BR")?.tag)
        assertEquals("pt-BR", appLanguage("pt_br")?.tag)
        assertEquals("zh-CN", appLanguage("zh-Hans-CN")?.tag)
        // A regional variant of a language shipped without a region is that language.
        assertEquals("de", appLanguage("de-AT")?.tag)
        // A region the app doesn't have isn't swapped for the one it has.
        assertNull(appLanguage("pt-PT"))
        assertNull(appLanguage("zh-TW"))
        // System default, and a language stored before it was removed.
        assertNull(appLanguage(null))
        assertNull(appLanguage(""))
        assertNull(appLanguage("it"))
    }

    @Test
    fun systemDefaultNamesTheLanguageTheSystemGives() {
        assertEquals("de", systemAppLanguage("de", "CH").tag)
        assertEquals("pt-BR", systemAppLanguage("pt", "BR").tag)
        assertEquals("zh-CN", systemAppLanguage("zh", "CN").tag)
        assertEquals("hu", systemAppLanguage("hu", null).tag)
        // Nothing shipped for it: the app is in English.
        assertEquals("en", systemAppLanguage("pt", "PT").tag)
        assertEquals("en", systemAppLanguage("it", "IT").tag)
        assertEquals("en", systemAppLanguage("en", "GB").tag)
    }

    @Test
    fun aPickBecomesThePerAppLocaleListAndBack() {
        assertEquals("", perAppLocaleTags(null))
        assertEquals("fr", perAppLocaleTags("fr"))
        assertEquals("zh-CN", perAppLocaleTags("zh-CN"))
        // A tag the app doesn't ship asks for no per-app language rather than one it can't show.
        assertEquals("", perAppLocaleTags("it"))
        for (language in AppLanguages) assertEquals(language.tag, pickedFromPerAppLocales(perAppLocaleTags(language.tag)))
        assertNull(pickedFromPerAppLocales(perAppLocaleTags(null)))
    }

    @Test
    fun thePerAppLocaleListReadsAsAPick() {
        assertNull(pickedFromPerAppLocales(""))
        assertEquals("hu", pickedFromPerAppLocales("hu"))
        // Android's own Language page may write the script; the first of several is the one in use.
        assertEquals("zh-CN", pickedFromPerAppLocales("zh-Hans-CN,en"))
        // A language the app no longer ships shows as System default.
        assertNull(pickedFromPerAppLocales("it-IT"))
    }

    @Test
    fun eachLanguageNamesItsResourceFolder() {
        assertEquals(
            listOf("values", "values-de", "values-fr", "values-es", "values-pt-rBR", "values-zh-rCN", "values-hu"),
            AppLanguages.map { it.resourceFolder },
        )
    }
}
