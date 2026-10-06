package dev.hermeskotlin.core.bots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionsTest {

    @Test
    fun findsTheWordBeingTyped() {
        assertEquals(MentionQuery(0, 1, ""), mentionQuery("@", 1))
        assertEquals(MentionQuery(4, 8, "tes"), mentionQuery("ask @tes", 8))
        // The cursor back inside a word: the query is what's before it, the range is the whole word,
        // so a pick replaces "@test1", not just "@t".
        assertEquals(MentionQuery(4, 10, "t"), mentionQuery("ask @test1 now", 6))
        // Not an e-mail address, not after the word ended.
        assertNull(mentionQuery("mail me@host", 12))
        assertNull(mentionQuery("ask @test1 now", 14))
        assertNull(mentionQuery("no mention", 3))
    }

    @Test
    fun matchesBotsByHandleOrName() {
        val bots = listOf(
            Bot(name = "default"),
            Bot(name = "test1"),
            Bot(name = "research-buddy", displayName = "Research Buddy"),
            Bot(name = "secret", uiMeta = kotlinx.serialization.json.Json.parseToJsonElement("""{"hermes-bots":{"hidden":true}}""") as kotlinx.serialization.json.JsonObject),
        )
        assertEquals(listOf("hermes", "research-buddy", "test1"), bots.mentionable("", self = null).map { it.handle })
        assertEquals(listOf("test1"), bots.mentionable("te", self = null).map { it.handle })
        assertEquals(listOf("research-buddy"), bots.mentionable("bud", self = null).map { it.handle })
        assertEquals(listOf("hermes"), bots.mentionable("her", self = "test1").map { it.handle })
        // The bot whose chat it is isn't offered to itself.
        assertEquals(emptyList(), bots.mentionable("test", self = "test1").map { it.handle })
    }
}
