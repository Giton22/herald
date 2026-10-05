package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.cron.CronJob

/**
 * What a bot needs from the user, for the roster's Needs-you section: the phone's natural job, since the
 * user isn't at a desk watching the bots work. One per bot, its most pressing.
 */
sealed interface NeedsYou {
    val bot: Bot

    /** A line saying what's needed. */
    val reason: String

    /** The bot's chat is held up on the user: an approval, a question, a secret. */
    data class Answer(override val bot: Bot, val waiting: Waiting) : NeedsYou {
        override val reason: String get() = waiting.label
    }

    /** The bot can't work until something is fixed (its ⚠). */
    data class Fix(override val bot: Bot, val trouble: BotTrouble) : NeedsYou {
        override val reason: String get() = trouble.problem.hint
    }

    /** One of the bot's routines went wrong. */
    data class Routine(override val bot: Bot, val job: CronJob) : NeedsYou {
        override val reason: String get() = "Routine “${job.displayName}” didn't go through"
    }
}

/**
 * The bots needing the user, those held up on an answer first (a turn is waiting), then those that can't
 * work, then failing routines; in roster order within each. [waiting] is by stored session id, so a bot
 * counts when its Bot Chat, by its own id or the live end of its lineage, is the one waiting. Hidden bots
 * count too: hiding a bot doesn't stop it working, or asking.
 */
fun needsYou(
    bots: List<Bot>,
    waiting: Map<String, Waiting>,
    troubles: Map<String, BotTrouble>,
    routines: Map<String, CronJob>,
): List<NeedsYou> {
    val items = bots.mapNotNull { bot ->
        val chat = bot.canonicalSession
        val held = listOfNotNull(chat?.openId, chat?.id).firstNotNullOfOrNull(waiting::get)
        when {
            held != null -> NeedsYou.Answer(bot, held)
            troubles[bot.name] != null -> NeedsYou.Fix(bot, troubles.getValue(bot.name))
            routines[bot.name] != null -> NeedsYou.Routine(bot, routines.getValue(bot.name))
            else -> null
        }
    }
    return items.sortedBy {
        when (it) {
            is NeedsYou.Answer -> 0
            is NeedsYou.Fix -> 1
            is NeedsYou.Routine -> 2
        }
    }
}
