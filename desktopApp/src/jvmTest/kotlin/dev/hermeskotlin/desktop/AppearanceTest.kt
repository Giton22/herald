package dev.hermeskotlin.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppearanceTest {
    private val key = "apple.awt.application.appearance"
    private val before = System.getProperty(key)

    @AfterTest fun restore() {
        if (before == null) System.clearProperty(key) else System.setProperty(key, before)
    }

    @Test fun macDialogsFollowTheSystem() {
        System.clearProperty(key)
        followSystemAppearance("Mac OS X")
        assertEquals("system", System.getProperty(key))
    }

    @Test fun elsewhereNothingIsSet() {
        for (os in listOf("Windows 11", "Linux", null)) {
            System.clearProperty(key)
            followSystemAppearance(os)
            assertNull(System.getProperty(key), "$os")
        }
    }
}
