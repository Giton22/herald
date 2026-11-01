package dev.hermeskotlin.ui.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {

    @Test
    fun nothingTypedKeepsEveryRow() {
        assertTrue(settingMatches("", "App lock"))
        assertTrue(settingMatches("   ", "App lock"))
    }

    @Test
    fun aRowStaysWhenItsNameOrDescriptionHasTheWords() {
        assertTrue(settingMatches("lock", "App lock", null))
        // Any case, and around spaces typed by the way.
        assertTrue(settingMatches(" FINGERPRINT ", "App lock", "Ask for your fingerprint, face or screen lock"))
        assertFalse(settingMatches("dictation", "App lock", "Ask for your fingerprint, face or screen lock"))
    }

    @Test
    fun theCallTargetIsFoundByWhatItSays() {
        // Its title, its target and its line, as the row passes them.
        val row = arrayOf("Default Call target", "New chat", "Where your call is routed, when you use call Hermes. Hold the app icon from your app drawer to use it")
        assertTrue(settingMatches("call target", *row))
        assertTrue(settingMatches("app drawer", *row))
        assertFalse(settingMatches("wallpaper", *row))
    }

    @Test
    fun aTypedApostropheFindsTheCataloguesCurlyOne() {
        assertTrue(settingMatches("don't", "Don’t ask again"))
        assertTrue(settingMatches("don’t", "Don't ask again"))
    }
}
