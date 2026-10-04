package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DraftStoreTest {

    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val work = GatewayUrl.parse("http://100.64.0.2:9119")

    @Test
    fun keepsOneDraftPerChatGatewayAndProfile() = runTest {
        val store = DraftStore(InMemoryKeyValueStore())

        store.set(home, "s1", "first")
        store.set(home, "s2", "second")
        store.set(home, "s1", "coder's", profile = "coder")
        store.set(work, "s1", "work")
        store.set(home, null, "new chat")

        assertEquals("first", store.get(home, "s1"))
        assertEquals("second", store.get(home, "s2"))
        assertEquals("coder's", store.get(home, "s1", profile = "coder"))
        assertEquals("work", store.get(work, "s1"))
        assertEquals("new chat", store.get(home, null))
    }

    @Test
    fun survivesARestart() = runTest {
        val disk = InMemoryKeyValueStore()
        DraftStore(disk).set(home, "s1", "half a thought")

        assertEquals("half a thought", DraftStore(disk).get(home, "s1"))
    }

    @Test
    fun blankTextRemovesTheDraft() = runTest {
        val store = DraftStore(InMemoryKeyValueStore())
        store.set(home, "s1", "draft")

        store.set(home, "s1", "  ")

        assertNull(store.get(home, "s1"))
    }

    @Test
    fun listsTheChatsWithDraftsButNotTheNewChat() = runTest {
        val store = DraftStore(InMemoryKeyValueStore())
        store.set(home, "s1", "a")
        store.set(home, null, "b")
        store.set(home, "s2", "c", profile = "coder")

        assertEquals(setOf("s1"), store.chatsWithDrafts(home).first())

        store.set(home, "s1", "")
        assertEquals(emptySet(), store.chatsWithDrafts(home).first())
    }
}
