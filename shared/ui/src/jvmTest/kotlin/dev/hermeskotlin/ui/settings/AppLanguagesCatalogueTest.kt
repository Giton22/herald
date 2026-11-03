package dev.hermeskotlin.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.Element

/**
 * [AppLanguages], the picker's list, against the two other places a language is named: Android's
 * `locales_config.xml` and the `values-*` folders of strings. Adding a language to one and not the others fails here.
 */
class AppLanguagesCatalogueTest {

    @Test
    fun theListMatchesLocalesConfig() {
        val file = File("../../androidApp/src/main/res/xml/locales_config.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val locales = doc.getElementsByTagName("locale")
        val tags = (0 until locales.length).map {
            (locales.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }
        assertEquals(AppLanguages.map { it.tag }, tags)
    }

    @Test
    fun theListMatchesTheStringFolders() {
        val folders = File("src/commonMain/composeResources").listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }
            .orEmpty()
            // Folders of another kind (a theme's or a density's) hold no language.
            .filter { File(it, "strings.xml").exists() }
            .map { it.name }
            .toSet()
        assertEquals(AppLanguages.map { it.resourceFolder }.toSet(), folders)
    }
}
