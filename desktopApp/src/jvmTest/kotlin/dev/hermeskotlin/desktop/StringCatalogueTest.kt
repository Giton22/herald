package dev.hermeskotlin.desktop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The desktop app's own catalogue (the tray, the quick entry, notifications, the storage prompt) against English:
 * the same placeholders, no key English lacks, no `%%` (Compose resources show it as two percent signs), and every
 * key in every language, since these are few and short.
 */
class StringCatalogueTest {
    private val root = File("src/jvmMain/composeResources")

    private fun catalogue(dir: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val e = strings.item(i) as Element
                out[e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[ds]""").findAll(text).map { it.value }.toSet()

    @Test
    fun translationsMatchEnglish() {
        val english = catalogue(File(root, "values"))
        assertTrue(english.isNotEmpty(), "No English catalogue under ${root.absolutePath}")
        val problems = mutableListOf<String>()
        english.forEach { (key, text) -> if ("%%" in text) problems += "values: $key has %%" }
        val languages = root.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
        assertTrue(languages.isNotEmpty(), "No translations under ${root.absolutePath}")
        languages.forEach { dir ->
            val translated = catalogue(dir)
            (english.keys - translated.keys).forEach { problems += "${dir.name}: $it is missing" }
            translated.forEach { (key, text) ->
                val base = english[key]
                when {
                    base == null -> problems += "${dir.name}: $key isn't in values/"
                    placeholders(text) != placeholders(base) ->
                        problems += "${dir.name}: $key has ${placeholders(text)}, English has ${placeholders(base)}"
                    "%%" in text -> problems += "${dir.name}: $key has %%"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
