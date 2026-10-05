package dev.hermeskotlin.core.bots

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotChatsTest {

    private class FakeBackend : BotChatBackend {
        var chats = mutableMapOf<String, BotSession>()
        var lookupFails = false
        var takenBy: BotSession? = null
        var starts = 0
        var startGate: CompletableDeferred<Unit>? = null

        override suspend fun botChat(profile: String): BotSession? {
            if (lookupFails) error("profile backend still waking up")
            return chats[profile]
        }

        override suspend fun startBotChat(profile: String): String {
            starts++
            startGate?.await()
            takenBy?.let {
                chats[profile] = it
                throw BotChatTakenException()
            }
            return "new-$starts".also { chats[profile] = BotSession(id = it) }
        }
    }

    private fun bot(name: String, chat: BotSession? = null) = Bot(name = name, canonicalSession = chat)

    @Test
    fun opensTheLiveEndOfAnExistingChat() = runTest {
        val backend = FakeBackend().apply { chats["scribe"] = BotSession(id = "root", resolvedId = "tip") }
        assertEquals("tip", BotChats(backend).open(bot("scribe", BotSession(id = "root"))))
        assertEquals(0, backend.starts)
    }

    @Test
    fun startsAChatOnlyForABotThatNeverHadOne() = runTest {
        val backend = FakeBackend()
        assertEquals("new-1", BotChats(backend).open(bot("scribe")))
        assertEquals(1, backend.starts)
    }

    @Test
    fun aFailedLookupNeverStartsASecondChat() = runTest {
        val backend = FakeBackend().apply { lookupFails = true }
        assertFailsWith<BotChatUnavailableException> { BotChats(backend).open(bot("scribe")) }
        assertEquals(0, backend.starts)
    }

    @Test
    fun anEmptyLookupForABotKnownToHaveAChatIsNotTrusted() = runTest {
        // The row showed a chat a moment ago; now the waking backend answers "none".
        val backend = FakeBackend()
        assertFailsWith<BotChatUnavailableException> { BotChats(backend).open(bot("scribe", BotSession(id = "root"))) }
        // Same when only an earlier roster had shown it.
        val chats = BotChats(backend)
        chats.note(listOf(bot("helper", BotSession(id = "h"))))
        assertFailsWith<BotChatUnavailableException> { chats.open(bot("helper")) }
        assertEquals(0, backend.starts)
    }

    @Test
    fun aChatStartedElsewhereMeanwhileIsAdopted() = runTest {
        val backend = FakeBackend().apply { takenBy = BotSession(id = "theirs") }
        assertEquals("theirs", BotChats(backend).open(bot("scribe")))
    }

    @Test
    fun aDoubleTapStartsOneChat() = runTest {
        val backend = FakeBackend().apply { startGate = CompletableDeferred() }
        val chats = BotChats(backend)
        val first = async { chats.open(bot("scribe")) }
        val second = async { chats.open(bot("scribe")) }
        yield()
        backend.startGate!!.complete(Unit)
        assertEquals("new-1", first.await())
        assertEquals("new-1", second.await())
        assertEquals(1, backend.starts)
    }

    @Test
    fun aFailedStartSaysWhy() = runTest {
        val backend = object : BotChatBackend {
            override suspend fun botChat(profile: String): BotSession? = null
            override suspend fun startBotChat(profile: String): String = error("No LLM provider")
        }
        val e = assertFailsWith<BotChatUnavailableException> { BotChats(backend).open(bot("scribe")) }
        assertTrue("No LLM provider" in e.message.orEmpty())
    }
}
