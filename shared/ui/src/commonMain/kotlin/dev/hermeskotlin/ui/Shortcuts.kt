package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import dev.hermeskotlin.ui.chat.ChatTarget

/** A keyboard shortcut of the desktop app, as Hermes Desktop binds them. */
sealed interface Shortcut {
    data object NewChat : Shortcut
    data object CommandPalette : Shortcut
    data object Settings : Shortcut
    data object ToggleSidebar : Shortcut
    data object SearchChats : Shortcut
    data object NextChat : Shortcut
    data object PreviousChat : Shortcut
    data object FindInChat : Shortcut
    data object ModelPicker : Shortcut
    data object FocusComposer : Shortcut
    data object ShortcutsHelp : Shortcut
    data object PageUp : Shortcut
    data object PageDown : Shortcut

    /** Ctrl+Home / Ctrl+End: the chat's first or latest message, unless the composer's text wants the keys. */
    data object ScrollTop : Shortcut
    data object ScrollBottom : Shortcut
    data object NewWindow : Shortcut
    data object CloseWindow : Shortcut
    data object ZoomIn : Shortcut
    data object ZoomOut : Shortcut
    data object ZoomReset : Shortcut

    /** Ctrl+J: puts the file rail away, or brings it back. */
    data object ToggleRail : Shortcut

    /** Ctrl+S: saves the page being edited (a job, config.yaml). */
    data object Save : Shortcut

    /** Escape that nothing closed with it: stops the running reply, or leaves an edit. */
    data object Escape : Shortcut

    /** A plain Enter that nothing focused took: answers an approval waiting in the chat. */
    data object Enter : Shortcut

    /** Ctrl+1…9: the chat at [index] (from 0) in the sidebar's list. */
    data class OpenChatAt(val index: Int) : Shortcut

    /** A listed chat picked elsewhere (the command palette), for the sidebar to open as a click on it would. */
    data class OpenListed(val id: String) : Shortcut

    /** Typing while nothing takes it: the text goes to the composer, which takes the focus. */
    data class Type(val text: String) : Shortcut

    /** Ctrl+V while nothing takes it: the clipboard goes to the composer. */
    data object Paste : Shortcut

    /** A prompt for the open chat from the quick entry, sent as Send would send it. */
    data class Prompt(val text: String) : Shortcut

    /** A chat to show in a window of its own (Ctrl+click in the list, Ctrl+Shift+N for the open one). */
    data class PopOut(val target: ChatTarget) : Shortcut

    /** One of the sidebar's pages, from the command palette. */
    data class Go(val page: SidebarPage) : Shortcut

    companion object {
        /**
         * The shortcut a key press stands for, read before anything focused sees it; null for one that isn't.
         * [mac] puts ⌘ where Ctrl is elsewhere.
         */
        fun of(event: KeyEvent, mac: Boolean): Shortcut? {
            if (event.type != KeyEventType.KeyDown) return null
            val primary = if (mac) event.isMetaPressed else event.isCtrlPressed
            val shift = event.isShiftPressed
            // Literally Control for the tab-cycle chord, as on every platform (⌘Tab switches apps on a Mac).
            if (event.isCtrlPressed && !event.isAltPressed) {
                when (event.key) {
                    Key.Tab -> return if (shift) PreviousChat else NextChat
                    Key.PageDown -> if (!shift) return NextChat
                    Key.PageUp -> if (!shift) return PreviousChat
                }
            }
            // Alt+Up / Alt+Down step through the chats, as Slack, Teams and Discord do; a Mac's ⌥↑ moves the caret.
            if (!mac && event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !shift) {
                when (event.key) {
                    Key.DirectionUp -> return PreviousChat
                    Key.DirectionDown -> return NextChat
                }
            }
            if (!primary) {
                if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || shift) return null
                return when (event.key) {
                    Key.PageUp -> PageUp
                    Key.PageDown -> PageDown
                    else -> null
                }
            }
            if (event.isAltPressed) return null
            return when (event.key) {
                Key.N -> if (shift) NewWindow else NewChat
                Key.K, Key.P -> if (shift) null else CommandPalette
                Key.Comma -> Settings
                Key.B -> if (shift) null else ToggleSidebar
                Key.F -> if (shift) SearchChats else FindInChat
                Key.M -> if (shift) ModelPicker else null
                Key.L -> if (shift) null else FocusComposer
                Key.J -> if (shift) null else ToggleRail
                Key.S -> if (shift) null else Save
                Key.Slash -> ShortcutsHelp
                Key.W -> if (shift) null else CloseWindow
                Key.Equals, Key.Plus, Key.NumPadAdd -> ZoomIn
                Key.Minus, Key.NumPadSubtract -> ZoomOut
                Key.Zero, Key.NumPad0 -> if (shift) null else ZoomReset
                Key.MoveHome -> if (shift) null else ScrollTop
                Key.MoveEnd -> if (shift) null else ScrollBottom
                else -> DIGITS.indexOf(event.key).takeIf { it >= 0 && !shift }?.let { OpenChatAt(it) }
            }
        }

        /**
         * What a key press nothing focused took stands for: typing or a paste to bring to the composer. Typing is
         * read from the typed character (the desktop's [KeyEventType.Unknown]), not the key going down: a text field
         * takes the character but lets the key-down go on, which would type it twice.
         */
        fun unhandled(event: KeyEvent, mac: Boolean): Shortcut? {
            if (event.type == KeyEventType.KeyDown) {
                val primary = if (mac) event.isMetaPressed else event.isCtrlPressed
                val plain = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
                if (plain && (event.key == Key.Enter || event.key == Key.NumPadEnter)) return Enter
                return Paste.takeIf { primary && !event.isAltPressed && !event.isShiftPressed && event.key == Key.V }
            }
            if (event.type != KeyEventType.Unknown) return null
            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return null
            val code = event.utf16CodePoint
            // Letters, digits, punctuation; not the controls, Tab and Enter among them, nor a bare space,
            // which presses a focused button.
            if (code <= ' '.code || code == 0x7f) return null
            // A character past the 16-bit range (most emoji) is two UTF-16 units.
            return Type(if (code > 0xFFFF) charArrayOf(highSurrogate(code), lowSurrogate(code)).concatToString() else code.toChar().toString())
        }

        private fun highSurrogate(code: Int) = ((code - 0x10000 shr 10) + 0xD800).toChar()

        private fun lowSurrogate(code: Int) = ((code - 0x10000 and 0x3FF) + 0xDC00).toChar()

        private val DIGITS =listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    }
}

/** The pages the sidebar opens in its place. */
enum class SidebarPage(val label: String) {
    Chats("Chats"),
    Bots("Bots"),
    Inbox("Inbox"),
    Scheduled("Scheduled"),
    Archived("Archived chats"),
    Capabilities("Capabilities"),
    Config("Config"),
    Plugins("Plugins"),
    Insights("Insights"),
}

/** The keys of the shortcuts listed in the palette and on the shortcuts page, as the platform writes them. */
fun keysLabel(keys: String, mac: Boolean = macKeys): String =
    if (!mac) keys else keys
        // The tab-cycle chord is the Control key on a Mac too (⌘Tab switches apps), so it keeps ⌃.
        .replace("Ctrl+Shift+Tab", "⌃⇧Tab").replace("Ctrl+Tab", "⌃Tab")
        .replace("Ctrl+Shift+", "⇧⌘").replace("Ctrl+", "⌘").replace("Alt+", "⌥").replace("Shift+", "⇧")

/** The desktop app's shortcuts, by what they're for, as the shortcuts page lists them. */
val SHORTCUT_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Composer" to listOf(
        "Send" to "Enter",
        "New line" to "Shift+Enter",
        "Queue behind the running reply" to "Ctrl+Enter",
        "Earlier prompts" to "Up / Down",
        "Stop the reply" to "Esc",
        "Focus the composer" to "Ctrl+L",
        "Pick a model" to "Ctrl+Shift+M",
    ),
    "Chats" to listOf(
        "New chat" to "Ctrl+N",
        "Next chat" to "Ctrl+Tab",
        "Previous chat" to "Ctrl+Shift+Tab",
        "Chat 1 to 9 in the list" to "Ctrl+1…9",
        "Search chats" to "Ctrl+Shift+F",
        "Find in this chat" to "Ctrl+F",
        "Hide or show the open file" to "Ctrl+J",
        "Files and folders in a prompt" to "@file: / @folder:",
        "Open in a new window" to "Ctrl+Shift+N",
        "Open a chat in a new window" to "Ctrl+click",
        "Pin or unpin a chat" to "Shift+click",
        "Archive a chat" to "Alt+Shift+click",
        "Scroll a page" to "PageUp / PageDown",
        "First or latest message" to "Ctrl+Home / Ctrl+End",
        "Previous or next chat" to "Alt+Up / Alt+Down",
    ),
    "App" to listOf(
        "Command palette" to "Ctrl+K",
        "Settings" to "Ctrl+,",
        "Show or hide the sidebar" to "Ctrl+B",
        "Zoom in / out / reset" to "Ctrl+= / Ctrl+- / Ctrl+0",
        "Save a job or config.yaml" to "Ctrl+S",
        "Close the window" to "Ctrl+W",
        "Quick entry from anywhere" to "Ctrl+Shift+Space",
        "These shortcuts" to "Ctrl+/",
    ),
)

/** What a click on a chat in the list does, by the keys held, as Hermes Desktop's rows. */
enum class RowClick { Open, Pin, Archive, NewWindow }

/**
 * Alt+Shift archives, Ctrl (⌘) opens in a window of its own, Shift pins; a plain click opens. Two-key gestures
 * go first, since they hold the one-key ones' keys too.
 */
fun rowClick(shift: Boolean, primary: Boolean, alt: Boolean): RowClick = when {
    alt && shift -> RowClick.Archive
    primary -> RowClick.NewWindow
    shift -> RowClick.Pin
    else -> RowClick.Open
}

/**
 * Where Ctrl+Tab ([forward]) or Ctrl+Shift+Tab goes from the chat at [at] in a list of [size]: round the end to
 * the other end; from a chat not in the list (a new one), to the first or the last.
 */
fun stepChat(at: Int, size: Int, forward: Boolean): Int = when {
    at !in 0 until size -> if (forward) 0 else size - 1
    forward -> (at + 1) % size
    else -> (at - 1 + size) % size
}

/**
 * A window's shortcuts, handed to whichever screen takes them: like [DesktopBack], the newest handler that is
 * on and takes a shortcut runs it, so a page open over the chat gets the first say.
 */
class ShortcutBus {
    private val handlers = mutableListOf<Handler>()

    internal class Handler(var enabled: Boolean, var take: (Shortcut) -> Boolean)

    internal fun add(handler: Handler) {
        handlers += handler
    }

    internal fun remove(handler: Handler) {
        handlers -= handler
    }

    /** Runs [shortcut] in the innermost handler that takes it; false when none did. */
    fun fire(shortcut: Shortcut): Boolean = handlers.asReversed().any { it.enabled && it.take(shortcut) }
}

/** Whether the quick entry's global keys are held by another app, so Settings can say why they do nothing. */
object QuickEntryHotkey {
    var taken by mutableStateOf(false)
}

/** The window's shortcuts; a bus nothing fires into where there's no keyboard to speak of (phones). */
val LocalShortcuts = staticCompositionLocalOf { ShortcutBus() }

/**
 * Whether something in the window holds the keyboard focus, kept by the window's root; what shows up on its
 * own (an approval, a question) takes the focus only while it's free, so it never pulls a search or a dialog's
 * field from under the user.
 */
val LocalFocusHeld = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** The window's root, keeping [held] to whether anything inside it has the focus. */
fun Modifier.keepFocusHeld(held: MutableState<Boolean>): Modifier = onFocusChanged { held.value = it.hasFocus }

/**
 * Takes shortcuts while [enabled]: [onShortcut] answers whether it handled the one given, so one it leaves
 * goes on to the screen beneath.
 */
@Composable
fun ShortcutHandler(enabled: Boolean = true, onShortcut: (Shortcut) -> Boolean) {
    val bus = LocalShortcuts.current
    val latest = rememberUpdatedState(onShortcut)
    val handler = remember(bus) { ShortcutBus.Handler(enabled) { latest.value(it) } }
    handler.enabled = enabled
    DisposableEffect(bus, handler) {
        bus.add(handler)
        onDispose { bus.remove(handler) }
    }
}
