package dev.hermeskotlin.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.hermeskotlin.ui.Shortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The window's keys as the desktop hands them over: a key going down, then the character it types. */
@OptIn(InternalComposeUiApi::class)
class ShortcutKeysTest {

    private fun down(key: Key, char: Char = Char(0), ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, char.code, isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift)

    private fun typed(char: Char, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyEvent(Key.Unknown, KeyEventType.Unknown, char.code, isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift)

    @Test
    fun typingIsReadOnceFromTheTypedCharacter() {
        // A text field takes the character but lets the key-down through: reading both would type it twice.
        assertNull(Shortcut.unhandled(down(Key.H, 'h'), mac = false))
        assertEquals(Shortcut.Type("h"), Shortcut.unhandled(typed('h'), mac = false))
        assertEquals(Shortcut.Type("H"), Shortcut.unhandled(typed('H', shift = true), mac = false))
    }

    @Test
    fun controlsSpacesAndChordsAreNotTyping() {
        assertNull(Shortcut.unhandled(typed(' '), mac = false))
        assertNull(Shortcut.unhandled(typed('\n'), mac = false))
        assertNull(Shortcut.unhandled(typed('\t'), mac = false))
        assertNull(Shortcut.unhandled(typed('a', ctrl = true), mac = false))
        assertNull(Shortcut.unhandled(typed('a', alt = true), mac = false))
    }

    @Test
    fun pasteIsTheKeyGoingDown() {
        assertEquals(Shortcut.Paste, Shortcut.unhandled(down(Key.V, ctrl = true), mac = false))
        assertEquals(Shortcut.Paste, Shortcut.unhandled(down(Key.V, meta = true), mac = true))
        assertNull(Shortcut.unhandled(down(Key.V, ctrl = true, shift = true), mac = false))
    }
}
