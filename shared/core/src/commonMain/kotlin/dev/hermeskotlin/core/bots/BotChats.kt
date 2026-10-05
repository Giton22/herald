package dev.hermeskotlin.core.bots

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [BotChats] needs from the gateway; [BotsApi] in the app, a fake in tests. */
interface BotChatBackend {
    /** The profile's Bot Chat as the gateway resolves it now, or null when it has none. Throws when it can't tell. */
    suspend fun botChat(profile: String): BotSession?

    /** Starts the profile's Bot Chat and returns its stored id. Throws [BotChatTakenException] when another client just did. */
    suspend fun startBotChat(profile: String): String
}

/** Someone else started the profile's Bot Chat between our lookup and our start. */
class BotChatTakenException : Exception("The Bot Chat was just started elsewhere.")

/** The bot's chat can't be opened right now; trying again later is safe. */
class BotChatUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Finds the one permanent chat of a bot, starting it only when the bot provably has none, ported from
 * Desktop's canonical-chat.ts. A bot keeps a single "Bot Chat" forever, so the costly mistake is starting a
 * second one: Desktop users lost a bot's whole context when a lookup that failed, or answered empty while
 * the bot's backend was still waking up, was read as "no chat yet". So a lookup that can't be trusted ends
 * in "try again", never in a new chat.
 */
class BotChats(private val backend: BotChatBackend) {

    /** Bots this app has seen a Bot Chat for; for those an empty lookup means "not sure", not "none". */
    private val known = mutableSetOf<String>()
    private val knownLock = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    private val locksLock = Mutex()

    /** Remembers which bots have a Bot Chat, from a roster just read. */
    suspend fun note(roster: List<Bot>) = knownLock.withLock {
        roster.filter { it.canonicalSession?.openId != null }.forEach { known += it.name }
    }

    /**
     * The stored session to open for [bot]'s chat: the live end of its Bot Chat, started first when it has
     * none. One open per bot at a time, so a double tap can't start two chats.
     */
    suspend fun open(bot: Bot): String {
        if (bot.canonicalSession?.openId != null) knownLock.withLock { known += bot.name }
        val lock = locksLock.withLock { locks.getOrPut(bot.name) { Mutex() } }
        return lock.withLock { resolve(bot) }
    }

    private suspend fun resolve(bot: Bot): String {
        lookup(bot)?.let { return it }
        if (knownLock.withLock { bot.name in known }) {
            throw BotChatUnavailableException("Couldn't find ${bot.label}'s chat right now. Try again in a moment.")
        }
        return try {
            backend.startBotChat(bot.name).also { knownLock.withLock { known += bot.name } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: BotChatTakenException) {
            // Another client started it a moment ago: open theirs, never a second one.
            lookup(bot) ?: throw BotChatUnavailableException("Couldn't open ${bot.label}'s chat. Try again in a moment.")
        } catch (e: Exception) {
            throw BotChatUnavailableException("Couldn't start ${bot.label}'s chat. ${e.message.orEmpty()}".trim(), e)
        }
    }

    private suspend fun lookup(bot: Bot): String? = try {
        backend.botChat(bot.name)?.openId
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BotChatUnavailableException("Couldn't check ${bot.label}'s chat. Try again in a moment.", e)
    }
}
