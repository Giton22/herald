package dev.hermeskotlin.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Every translation in `composeResources` against English: the same placeholders, no key English lacks, and no
 * `%%`, which Compose resources show as two percent signs rather than one.
 */
class StringCatalogueTest {
    private val root = File("src/commonMain/composeResources")

    /** Each folder's strings, a plural's quantities joined, by key. */
    private fun catalogue(dir: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val e = strings.item(i) as Element
                out[e.getAttribute("name")] = e.textContent
            }
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val e = plurals.item(i) as Element
                out["plurals:" + e.getAttribute("name")] = e.textContent
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
        root.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty().forEach { dir ->
            catalogue(dir).forEach { (key, text) ->
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
