package dev.hermeskotlin.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestedRepliesSettingsTest {

    private val what = suggestionsDetail(on = false, plugin = SuggestionsPlugin.Missing)

    @Test
    fun offItOnlySaysWhatItDoes() {
        assertTrue("Next Prompt plugin" in what)
        assertEquals(what, suggestionsDetail(on = true, plugin = SuggestionsPlugin.Checking))
        assertEquals(what, suggestionsDetail(on = true, plugin = SuggestionsPlugin.Unknown))
        assertEquals(what, suggestionsDetail(on = true, plugin = SuggestionsPlugin.Installed("0.3.1")))
    }

    @Test
    fun onItSaysWhereThePluginStands() {
        assertTrue("doesn't have" in suggestionsDetail(on = true, plugin = SuggestionsPlugin.Missing))
        assertTrue("Installing" in suggestionsDetail(on = true, plugin = SuggestionsPlugin.Installing))
        assertTrue("timed out" in suggestionsDetail(on = true, plugin = SuggestionsPlugin.Failed("timed out")))
        assertTrue("0.3.0" in suggestionsDetail(on = true, plugin = SuggestionsPlugin.Installed("0.3.0")))
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
