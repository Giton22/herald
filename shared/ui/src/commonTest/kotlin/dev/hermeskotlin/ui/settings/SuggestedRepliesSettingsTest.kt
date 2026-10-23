package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.plugins.NextPromptApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestedRepliesSettingsTest {

    @Test
    fun offItOnlySaysWhatItDoes() {
        val what = SuggestionsLine.What
        assertEquals(what, suggestionsLine(on = false, plugin = SuggestionsPlugin.Missing))
        assertEquals(what, suggestionsLine(on = true, plugin = SuggestionsPlugin.Checking))
        assertEquals(what, suggestionsLine(on = true, plugin = SuggestionsPlugin.Unknown))
        assertEquals(what, suggestionsLine(on = true, plugin = SuggestionsPlugin.Installed("0.3.1")))
    }

    @Test
    fun onItSaysWhereThePluginStands() {
        assertEquals(SuggestionsLine.Missing, suggestionsLine(on = true, plugin = SuggestionsPlugin.Missing))
        assertEquals(SuggestionsLine.Installing, suggestionsLine(on = true, plugin = SuggestionsPlugin.Installing))
        assertEquals(
            SuggestionsLine.Failed("timed out"),
            suggestionsLine(on = true, plugin = SuggestionsPlugin.Failed("timed out")),
        )
        assertEquals(
            SuggestionsLine.Outdated("0.3.0", NextPromptApi.PLUGIN_VERSION),
            suggestionsLine(on = true, plugin = SuggestionsPlugin.Installed("0.3.0")),
        )
    }

    @Test
    fun versionsBefore031AreOutdated() {
        assertTrue(SuggestionsPlugin.Installed("0.3.0").outdated)
        assertTrue(SuggestionsPlugin.Installed("0.2.9").outdated)
        assertFalse(SuggestionsPlugin.Installed("0.3.1").outdated)
        assertFalse(SuggestionsPlugin.Installed("0.4").outdated)
        assertFalse(SuggestionsPlugin.Installed("1.0.0").outdated)
        // A plugin that names no version isn't flagged: there's nothing to tell the user.
        assertFalse(SuggestionsPlugin.Installed(null).outdated)
    }
}
