package dev.hermeskotlin.core.screen

/**
 * The keys a keyboard sends, named the way VNC names them: an X11 key symbol.
 *
 * A printable character is its own key symbol, so a phone keyboard's letters need no table. The keys that
 * have no character — the arrows, the function keys — have numbers of their own.
 */
enum class ScreenKey {
    Enter,
    Backspace,
    Tab,
    Escape,
    Left,
    Right,
    Up,
    Down,
    Home,
    End,
    PageUp,
    PageDown,
    Delete,
    Insert,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

/** The key symbol for a character, or 0 when it has none. */
fun keysymForCharacter(ch: Char): Int = when {
    ch.code in 0x20..0xFF -> ch.code
    ch == '\n' || ch == '\r' -> KEYSYM_RETURN
    ch == '\t' -> KEYSYM_TAB
    ch == '\b' -> KEYSYM_BACKSPACE
    ch == '' -> KEYSYM_ESCAPE
    else -> 0
}

/** The key symbol for a key that has no character. */
fun keysymForKey(key: ScreenKey): Int = when (key) {
    ScreenKey.Enter -> KEYSYM_RETURN
    ScreenKey.Backspace -> KEYSYM_BACKSPACE
    ScreenKey.Tab -> KEYSYM_TAB
    ScreenKey.Escape -> KEYSYM_ESCAPE
    ScreenKey.Left -> KEYSYM_LEFT
    ScreenKey.Right -> KEYSYM_RIGHT
    ScreenKey.Up -> KEYSYM_UP
    ScreenKey.Down -> KEYSYM_DOWN
    ScreenKey.Home -> KEYSYM_HOME
    ScreenKey.End -> KEYSYM_END
    ScreenKey.PageUp -> KEYSYM_PAGE_UP
    ScreenKey.PageDown -> KEYSYM_PAGE_DOWN
    ScreenKey.Delete -> KEYSYM_DELETE
    ScreenKey.Insert -> KEYSYM_INSERT
    ScreenKey.F1 -> KEYSYM_F1
    ScreenKey.F2 -> KEYSYM_F2
    ScreenKey.F3 -> KEYSYM_F3
    ScreenKey.F4 -> KEYSYM_F4
    ScreenKey.F5 -> KEYSYM_F5
    ScreenKey.F6 -> KEYSYM_F6
    ScreenKey.F7 -> KEYSYM_F7
    ScreenKey.F8 -> KEYSYM_F8
    ScreenKey.F9 -> KEYSYM_F9
    ScreenKey.F10 -> KEYSYM_F10
    ScreenKey.F11 -> KEYSYM_F11
    ScreenKey.F12 -> KEYSYM_F12
}

private const val KEYSYM_BACKSPACE = 0xFF08
private const val KEYSYM_TAB = 0xFF09
private const val KEYSYM_RETURN = 0xFF0D
private const val KEYSYM_ESCAPE = 0xFF1B
private const val KEYSYM_HOME = 0xFF50
private const val KEYSYM_LEFT = 0xFF51
private const val KEYSYM_UP = 0xFF52
private const val KEYSYM_RIGHT = 0xFF53
private const val KEYSYM_DOWN = 0xFF54
private const val KEYSYM_PAGE_UP = 0xFF55
private const val KEYSYM_PAGE_DOWN = 0xFF56
private const val KEYSYM_END = 0xFF57
private const val KEYSYM_INSERT = 0xFF63
private const val KEYSYM_DELETE = 0xFFFF
private const val KEYSYM_F1 = 0xFFBE
private const val KEYSYM_F2 = 0xFFBF
private const val KEYSYM_F3 = 0xFFC0
private const val KEYSYM_F4 = 0xFFC1
private const val KEYSYM_F5 = 0xFFC2
private const val KEYSYM_F6 = 0xFFC3
private const val KEYSYM_F7 = 0xFFC4
private const val KEYSYM_F8 = 0xFFC5
private const val KEYSYM_F9 = 0xFFC6
private const val KEYSYM_F10 = 0xFFC7
private const val KEYSYM_F11 = 0xFFC8
private const val KEYSYM_F12 = 0xFFC9
