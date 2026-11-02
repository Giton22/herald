package dev.hermeskotlin.android.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.core.bots.Bot

/**
 * Each bot as an Android conversation shortcut: in the launcher's long-press menu on Herald, as a
 * home-screen shortcut, and behind the bot's notifications, which Android then shows as a conversation.
 * Opening one goes to `hermes://bot/<profile>`, the link Hermes Desktop uses for a bot.
 */
object BotShortcuts {
    private var callPicture: ByteArray? = null

    /**
     * Refresh the call action without replacing the bot list; pinned copies receive the same artwork. False when
     * Android didn't take it (rate-limited, or it failed), so the caller tries again later.
     */
    @Synchronized
    internal fun publishCall(context: Context, picture: ByteArray? = null): Boolean {
        callPicture = picture
        val info = CallShortcut.info(context, picture)
        return runCatching {
            ShortcutManagerCompat.updateShortcuts(context, listOf(info))
            if (callSlots(context) == 0) return true
            if (ShortcutManagerCompat.isRateLimitingActive(context)) return false
            val bots = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id.startsWith(ID_PREFIX) }.sortedBy { it.rank }
            val extra = bots.drop(botSlots(context)).map { it.id }
            if (extra.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, extra)
            ShortcutManagerCompat.addDynamicShortcuts(context, listOf(info))
        }.getOrDefault(false)
    }


    /**
     * Publishes [bot]'s shortcut (refreshing its name and face) and returns its id, or null when Android
     * would not take it; the bot's notification then goes out as a plain one.
     */
    @Synchronized
    fun push(context: Context, bot: Bot, picture: ByteArray?): String? {
        val info = info(context, bot, picture, rank = 1)
        return runCatching {
            if (botSlots(context) == 0) return null
            // Android 11+ makes room itself, counting the fixed actions. Before that, ShortcutManagerCompat
            // counts only the dynamic shortcuts, so with the menu full the add throws: drop the bot shortcut
            // furthest down the list first.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // Rate-limited, the push is refused anyway: keep the shortcut that would make room for it.
                if (ShortcutManagerCompat.isRateLimitingActive(context)) return null
                val dynamic = ShortcutManagerCompat.getDynamicShortcuts(context)
                if (dynamic.none { it.id == info.id } && dynamic.count { it.id.startsWith(ID_PREFIX) } >= botSlots(context)) {
                    dynamic.filter { it.id.startsWith(ID_PREFIX) }.maxByOrNull { it.rank }
                        ?.let { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(it.id)) }
                }
            }
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
        }.getOrDefault(false).let { pushed -> if (pushed) info.id else null }
    }

    /**
     * The launcher's list: the roster's first few bots, in its order. Also forgets the shortcuts of bots no
     * longer in [bots] (removed, renamed or hidden): Android keeps a long-lived shortcut cached once a
     * notification used it, and the share sheet would go on offering it, under its old name.
     */
    @Synchronized
    fun publish(context: Context, bots: List<Bot>, pictures: Map<String, ByteArray>) {
        val max = runCatching { botSlots(context) }.getOrDefault(0)
        val infos = bots.take(max).mapIndexed { rank, bot -> info(context, bot, pictures[bot.name], rank + 1) }
        val calls = if (runCatching { callSlots(context) > 0 }.getOrDefault(false)) listOf(CallShortcut.info(context, callPicture)) else emptyList()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, calls + infos) }
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

    /** How many bot shortcuts fit: Android's per-activity limit, less the fixed actions and the reserved call action. */
    private fun botSlots(context: Context): Int {
        val fixed = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size
        return (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - fixed - 1).coerceIn(0, MAX_SHORTCUTS)
    }

    private fun callSlots(context: Context): Int =
        (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) -
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size).coerceAtLeast(0)

    private fun info(context: Context, bot: Bot, picture: ByteArray?, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ID_PREFIX + bot.name)
            // Under the app icon's launcher entry by name. Left to Android, a shortcut published just after the
            // user picks another icon goes under the old entry, and is dropped when Android notices it's off.
            .apply { context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let(::setActivity) }
            // The conversation's name and face in the shade come from here, so both are just the bot's.
            .setShortLabel(bot.label)
            .setLongLabel(bot.label)
            .setIcon(BotIcons.icon(bot, picture))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("hermes://bot/${Uri.encode(bot.name)}"), context, HeraldActivity::class.java))
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
