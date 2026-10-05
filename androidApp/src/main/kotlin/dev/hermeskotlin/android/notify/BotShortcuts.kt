package dev.hermeskotlin.android.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.hermeskotlin.android.MainActivity
import dev.hermeskotlin.core.bots.Bot

/**
 * Each bot as an Android conversation shortcut: in the launcher's long-press menu on Herald, as a
 * home-screen shortcut, and behind the bot's notifications, which Android then shows as a conversation.
 * Opening one goes to `hermes://bot/<profile>`, the link Hermes Desktop uses for a bot.
 */
object BotShortcuts {

    /** Publishes [bot]'s shortcut (refreshing its name and face) and returns its id. */
    fun push(context: Context, bot: Bot, picture: ByteArray?): String {
        val info = info(context, bot, picture, rank = 0)
        ShortcutManagerCompat.pushDynamicShortcut(context, info)
        return info.id
    }

    /**
     * The launcher's list: the roster's first few bots, in its order. Also forgets the shortcuts of bots no
     * longer in [bots] (removed, renamed or hidden): Android keeps a long-lived shortcut cached once a
     * notification used it, and the share sheet would go on offering it, under its old name.
     */
    fun publish(context: Context, bots: List<Bot>, pictures: Map<String, ByteArray>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_SHORTCUTS)
        val infos = bots.take(max).mapIndexed { rank, bot -> info(context, bot, pictures[bot.name], rank) }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, infos) }
        val current = bots.mapTo(HashSet()) { ID_PREFIX + it.name }
        runCatching {
            val stale = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                .map { it.id }
                .filter { it.startsWith(ID_PREFIX) && it !in current }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
        }
    }

    /**
     * The bot as a person in its conversation. Not marked as a bot: Android keeps conversations whose
     * other side is only bots out of the Conversations section (NotificationRecord.isOnlyBots), and these
     * are exactly the ones the user wants to read like a contact's.
     */
    fun person(bot: Bot, picture: ByteArray?): Person = Person.Builder()
        .setName(bot.label)
        .setKey("bot:${bot.name}")
        .setIcon(BotIcons.icon(bot, picture))
        .build()

    private fun info(context: Context, bot: Bot, picture: ByteArray?, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ID_PREFIX + bot.name)
            // The conversation's name and face in the shade come from here, so both are just the bot's.
            .setShortLabel(bot.label)
            .setLongLabel(bot.label)
            .setIcon(BotIcons.icon(bot, picture))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("hermes://bot/${Uri.encode(bot.name)}"), context, MainActivity::class.java))
            .setLongLived(true)
            .setPerson(person(bot, picture))
            .setCategories(setOf(SHARE_CATEGORY))
            .setRank(rank)
            .build()

    private const val MAX_SHORTCUTS = 4

    /** Lets the share sheet offer a bot directly (share-to-bot); the `<share-target>` in shortcuts.xml names it. */
    const val SHARE_CATEGORY = "dev.hermeskotlin.category.BOT_SHARE"

    /** A bot shortcut's id is this and the bot's profile; a share through it carries the id. */
    const val ID_PREFIX = "bot:"
}
