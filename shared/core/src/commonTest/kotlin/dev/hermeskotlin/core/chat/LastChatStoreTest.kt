package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastChatStoreTest {

    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val work = GatewayUrl.parse("http://100.64.0.2:9119")

    @Test
    fun remembersOneChatPerGateway() = runTest {
        val store = LastChatStore(InMemoryKeyValueStore())

        store.set(home, LastChat("s1", "Fix the build"))
        store.set(work, LastChat("w1"))

        assertEquals(LastChat("s1", "Fix the build"), store.get(home))
        assertEquals(LastChat("w1"), store.get(work))
    }

    @Test
    fun clearingOrForgettingTheRememberedSessionEmptiesIt() = runTest {
        val store = LastChatStore(InMemoryKeyValueStore())
        store.set(home, LastChat("s1"))

        store.forget(home, "other")
        assertEquals("s1", store.get(home)?.sessionId)

        store.forget(home, "s1")
        assertNull(store.get(home))

        store.set(home, LastChat("s2"))
        store.set(home, null)
        assertNull(store.get(home))
    }
}
