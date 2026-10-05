package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotProblem
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.cron.CronJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NeedsYouTest {

    private fun bot(name: String, chat: String? = null, resolved: String? = null) =
        Bot(name = name, canonicalSession = chat?.let { BotSession(id = it, resolvedId = resolved) })

    private val quota = BotTrouble(BotProblem.Quota, "402", BotTrouble.Source.Turn)
    private val brief = CronJob(id = "j1", name = "[bot:scout] Brief", lastStatus = "error")

    @Test
    fun aBotHeldUpOnAnAnswerComesFirstAndEachBotOnce() {
        val scribe = bot("scribe", chat = "c1")
        val scout = bot("scout", chat = "c2")
        val keeper = bot("keeper", chat = "c3")

        val items = needsYou(
            bots = listOf(scout, keeper, scribe),
            waiting = mapOf("c1" to Waiting.Approval),
            troubles = mapOf("scribe" to quota, "keeper" to quota),
            routines = mapOf("scout" to brief),
        )

        assertEquals(listOf("scribe", "keeper", "scout"), items.map { it.bot.name })
        // Waiting outranks the bot's own trouble.
        assertIs<NeedsYou.Answer>(items[0])
        assertEquals("Needs approval", items[0].reason)
        assertEquals("Routine “Brief” didn't go through", items[2].reason)
    }

    @Test
    fun aChatMovedOnByCompressionIsStillTheBots() {
        val scribe = bot("scribe", chat = "root", resolved = "tip")

        val items = needsYou(listOf(scribe), waiting = mapOf("tip" to Waiting.Question), troubles = emptyMap(), routines = emptyMap())

        assertEquals(Waiting.Question, (items.single() as NeedsYou.Answer).waiting)
    }

    @Test
    fun nothingNeededIsNothingShown() {
        assertTrue(needsYou(listOf(bot("scribe", chat = "c1")), mapOf("other" to Waiting.Approval), emptyMap(), emptyMap()).isEmpty())
    }
}
