package dev.hermeskotlin.core.screen

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeysymsTest {

    @Test
    fun aPrintableCharacterIsItsOwnKey() {
        assertEquals(0x61, keysymForCharacter('a'))
        assertEquals(0x41, keysymForCharacter('A'))
        assertEquals(0x30, keysymForCharacter('0'))
        assertEquals(0x20, keysymForCharacter(' '))
        assertEquals(0xE9, keysymForCharacter('é'))
    }

    @Test
    fun theKeysWithoutACharacterHaveTheirOwn() {
        assertEquals(0xFF0D, keysymForKey(ScreenKey.Enter))
        assertEquals(0xFF08, keysymForKey(ScreenKey.Backspace))
        assertEquals(0xFF09, keysymForKey(ScreenKey.Tab))
        assertEquals(0xFF1B, keysymForKey(ScreenKey.Escape))
        assertEquals(0xFF51, keysymForKey(ScreenKey.Left))
        assertEquals(0xFF52, keysymForKey(ScreenKey.Up))
        assertEquals(0xFF53, keysymForKey(ScreenKey.Right))
        assertEquals(0xFF54, keysymForKey(ScreenKey.Down))
        assertEquals(0xFF50, keysymForKey(ScreenKey.Home))
        assertEquals(0xFF57, keysymForKey(ScreenKey.End))
        assertEquals(0xFF55, keysymForKey(ScreenKey.PageUp))
        assertEquals(0xFF56, keysymForKey(ScreenKey.PageDown))
        assertEquals(0xFFFF, keysymForKey(ScreenKey.Delete))
        assertEquals(0xFF63, keysymForKey(ScreenKey.Insert))
        assertEquals(0xFFBE, keysymForKey(ScreenKey.F1))
        assertEquals(0xFFC9, keysymForKey(ScreenKey.F12))
    }

    @Test
    fun aNewlineIsTheReturnKey() {
        assertEquals(0xFF0D, keysymForCharacter('\n'))
        assertEquals(0xFF09, keysymForCharacter('\t'))
    }

    @Test
    fun aCharacterWithNoKeyIsRefused() {
        assertEquals(0, keysymForCharacter('中'))
        assertTrue(keysymForCharacter('a') != 0)
    }
}
