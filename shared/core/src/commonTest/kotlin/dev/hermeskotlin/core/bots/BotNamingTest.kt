package dev.hermeskotlin.core.bots

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotNamingTest {

    @Test
    fun namesBecomeProfileIdsAsOnDesktop() {
        assertEquals("research-buddy", slugifyProfileName("Research Buddy"))
        assertEquals("resume", slugifyProfileName("Résumé"))
        assertEquals("u5c0f-u52a9-u624b", slugifyProfileName("小助手"))
        assertEquals("ud55c-uae00", slugifyProfileName("한글"))
        assertEquals("code_review-2", slugifyProfileName("  Code_Review!! 2 "))
        assertTrue(slugifyProfileName("a".repeat(80)).length <= 64)
        // A long CJK name is cut between tokens, never inside one.
        val cut = slugifyProfileName("小".repeat(20))
        assertTrue(cut.split('-').all { it == "u5c0f" }, cut)
    }

    @Test
    fun aNameThatCantBeAnIdIsKeptAsTheTitle() {
        assertEquals("u5c0f-u52a9-u624b" to "小助手", botIdentity("小助手", ""))
        assertEquals("scribe" to "Writer", botIdentity("Scribe", " Writer "))
        assertEquals("scribe" to "", botIdentity("Scribe", ""))
    }

    @Test
    fun theGeneratedPersona() {
        assertEquals(
            "# Scribe\n\n**Role:** Writer\n**Mission:** Drafts posts\n\n" +
                "You are Scribe, a persistent named agent (profile `scribe`) on this machine.\n" +
                "You keep your own memory, skills, and conversation history across sessions.",
            composeSoul("Scribe", "scribe", "Writer", "Drafts posts"),
        )
        assertTrue(composeSoul("Scribe", "scribe", "", "").startsWith("# Scribe\n\nYou are Scribe"))
        assertEquals("Code Reviewer", displayNameFor("code-reviewer", ""))
        assertEquals("Doc", displayNameFor("x", " Doc "))
    }

    @Test
    fun draftsCheckTheirNameAndCarryTheirLook() {
        assertEquals("Give it a name.", BotDraft("").problem(emptySet()))
        assertEquals("A bot called \"scribe\" already exists.", BotDraft("scribe").problem(setOf("scribe")))
        assertNull(BotDraft("scribe").problem(setOf("default")))
        val look = BotDraft("scribe", title = "Writer", shape = BotShape.Hexagon, color = "#4b94dd").look()
        assertEquals(JsonPrimitive("hexagon"), look["shape"])
        assertEquals(JsonPrimitive(true), look["custom"])
        assertEquals(JsonPrimitive("Writer"), look["title"])
        // Without a pick, the name's own shape and hue stay in charge.
        assertNull(BotDraft("scribe").look()["custom"])
        assertEquals("#4b94dd", cssHex(0x4B94DD))
        assertEquals(12, BOT_SWATCHES.size)
    }
}
