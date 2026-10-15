package dev.hermeskotlin.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** The picker switches launcher aliases by name: the app's manifest must declare exactly those, one enabled. */
class AppIconsManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val aliases: Map<String, Element> by lazy {
        // Tests run in shared/ui; the manifest is androidApp's.
        val manifest = File("../../androidApp/src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "no manifest at ${manifest.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity-alias")
        (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getElementsByTagName("category").let { c -> (0 until c.length).any { i -> (c.item(i) as Element).getAttributeNS(android, "name") == "android.intent.category.LAUNCHER" } } }
            .associateBy { "dev.hermeskotlin.android" + it.getAttributeNS(android, "name") }
    }

    @Test
    fun everyIconHasItsLauncherAlias() {
        assertEquals(AppIcon.entries.map { it.aliasClass }.toSet(), aliases.keys)
        aliases.values.forEach { assertEquals(".HeraldActivity", it.getAttributeNS(android, "targetActivity")) }
    }

    @Test
    fun onlyTheDefaultStartsEnabled() {
        val enabled = aliases.filterValues { it.getAttributeNS(android, "enabled") != "false" }.keys
        assertEquals(setOf(AppIcon.Blue.aliasClass), enabled)
    }

    @Test
    fun everyAliasCarriesTheShortcuts() {
        aliases.forEach { (name, alias) ->
            val meta = alias.getElementsByTagName("meta-data")
            assertTrue((0 until meta.length).any { (meta.item(it) as Element).getAttributeNS(android, "name") == "android.app.shortcuts" }, name)
        }
    }
}
