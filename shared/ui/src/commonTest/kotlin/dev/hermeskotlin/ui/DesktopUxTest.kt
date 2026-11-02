package dev.hermeskotlin.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import dev.hermeskotlin.core.bots.MentionQuery
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.pathRefQuery
import dev.hermeskotlin.core.projects.PathEntry
import dev.hermeskotlin.ui.chat.PromptRecall
import dev.hermeskotlin.ui.chat.findMatches
import dev.hermeskotlin.ui.chat.moveHighlight
import dev.hermeskotlin.ui.chat.refChoices
import dev.hermeskotlin.ui.components.PaletteItem
import dev.hermeskotlin.ui.components.paletteMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopUxTest {

    @Test
    fun rowClicksByKeysHeld() {
        assertEquals(RowClick.Open, rowClick(shift = false, primary = false, alt = false))
        assertEquals(RowClick.Pin, rowClick(shift = true, primary = false, alt = false))
        assertEquals(RowClick.NewWindow, rowClick(shift = false, primary = true, alt = false))
        // Alt+Shift holds Shift too, and wins.
        assertEquals(RowClick.Archive, rowClick(shift = true, primary = false, alt = true))
    }

    @Test
    fun ctrlTabGoesRoundTheList() {
        assertEquals(1, stepChat(0, 3, forward = true))
        assertEquals(0, stepChat(2, 3, forward = true))
        assertEquals(2, stepChat(0, 3, forward = false))
        // From a chat not in the list (a new one): the first, or the last.
        assertEquals(0, stepChat(-1, 3, forward = true))
        assertEquals(2, stepChat(-1, 3, forward = false))
    }

    @Test
    fun theBusAsksTheNewestHandlerFirst() {
        val bus = ShortcutBus()
        val heard = mutableListOf<String>()
        bus.add(ShortcutBus.Handler(true) { heard += "app"; true })
        bus.add(ShortcutBus.Handler(true) { heard += "chat"; it == Shortcut.FindInChat })
        bus.add(ShortcutBus.Handler(false) { heard += "covered"; true })
        assertEquals(true, bus.fire(Shortcut.FindInChat))
        assertEquals(listOf("chat"), heard)
        assertEquals(true, bus.fire(Shortcut.NewChat))
        assertEquals(listOf("chat", "chat", "app"), heard)
    }

    @Test
    fun macKeysAreWrittenTheMacWay() {
        assertEquals("⇧⌘N", keysLabel("Ctrl+Shift+N", mac = true))
        assertEquals("⌘K", keysLabel("Ctrl+K", mac = true))
        assertEquals("Ctrl+K", keysLabel("Ctrl+K", mac = false))
        // The tab-cycle chord is the Control key on a Mac as well; ⌘Tab would switch apps.
        assertEquals("⌃Tab", keysLabel("Ctrl+Tab", mac = true))
        assertEquals("⌃⇧Tab", keysLabel("Ctrl+Shift+Tab", mac = true))
        assertEquals("⌥⇧click", keysLabel("Alt+Shift+click", mac = true))
    }

    @Test
    fun upWalksBackOnlyFromAnEmptyComposer() {
        val recall = PromptRecall()
        val sent = listOf("one", "two", "three")
        assertNull(recall.older("a draft", sent))
        assertEquals("three", recall.older("", sent))
        assertEquals("two", recall.older("three", sent))
        assertEquals("one", recall.older("two", sent))
        assertNull(recall.older("one", sent))
        assertEquals("two", recall.newer("one", sent))
        assertEquals("three", recall.newer("two", sent))
        assertEquals("", recall.newer("three", sent))
        // Changed after it came back, it's a draft: Up leaves it.
        assertEquals("three", recall.older("", sent))
        assertNull(recall.older("three, edited", sent))
    }

    @Test
    fun thePaletteFindsByEveryWord() {
        fun item(label: String, keywords: String = "") = PaletteItem("Go to", label, Lucide.Search, keywords = keywords) {}
        val items = listOf(item("Settings"), item("New chat", "start"), item("Archived chats"), item("Search chats"))
        assertEquals(items, paletteMatches("  ", items))
        assertEquals(listOf("New chat"), paletteMatches("start new", items).map { it.label })
        // A label starting with it comes first; the rest keep their order.
        assertEquals(listOf("Search chats", "Old chats search"), paletteMatches("search ch", listOf(item("Old chats search")) + items).map { it.label })
        assertEquals(listOf("New chat", "Archived chats", "Search chats"), paletteMatches("ch", items).map { it.label })
    }

    @Test
    fun findLooksThroughWhatWasSaid() {
        val messages = listOf(
            ChatMessage.User("u1", "Deploy the API"),
            ChatMessage.Assistant("a1", text = "Deployed. The api is up."),
            ChatMessage.Notice("n1", "Model changed"),
        )
        assertEquals(listOf(0, 1), findMatches(messages, " api "))
        assertEquals(emptyList(), findMatches(messages, ""))
        assertEquals(listOf(2), findMatches(messages, "model"))
    }

    @Test
    fun atOffersFileReferencesThenTheFolderEntries() {
        val kinds = refChoices(MentionQuery(0, 2, "f"), null, emptyList())
        assertEquals(listOf("@file:", "@folder:"), kinds.map { it.insert })

        val text = "read @file:src/Ma"
        val ref = pathRefQuery(text, text.length)!!
        assertEquals("src/", ref.folder)
        val entries = listOf(PathEntry("Main.kt", false), PathEntry("main", true), PathEntry("Other.kt", false))
        val choices = refChoices(null, ref, entries)
        assertEquals(listOf("@file:src/Main.kt ", "@file:src/main/"), choices.map { it.insert })
        assertEquals(5, choices.first().start)
        assertEquals(text.length, choices.first().end)

        val folder = pathRefQuery("@folder:", 8)!!
        assertEquals(listOf("@folder:main/"), refChoices(null, folder, entries).map { it.insert })
    }

    @Test
    fun onlyAWordThatStartsAReferenceCounts() {
        assertNull(pathRefQuery("mail@file:x", 11))
        assertNull(pathRefQuery("@file:\"my docs/a", 16))
        assertEquals("~/", pathRefQuery("@file:~/no", 10)!!.folder)
        assertEquals("/", pathRefQuery("@file:/et", 9)!!.folder)
        assertEquals("", pathRefQuery("@file:RE", 8)!!.folder)
    }

    @Test
    fun upAndDownGoRoundTheSuggestions() {
        // With none marked, Down starts at the first and Up at the last.
        assertEquals(0, moveHighlight(-1, 1, 4))
        assertEquals(3, moveHighlight(-1, -1, 4))
        assertEquals(2, moveHighlight(1, 1, 4))
        assertEquals(0, moveHighlight(3, 1, 4))
        assertEquals(3, moveHighlight(0, -1, 4))
        // One marked past a list that got shorter starts over.
        assertEquals(0, moveHighlight(7, 1, 4))
        assertEquals(-1, moveHighlight(0, 1, 0))
    }
}
