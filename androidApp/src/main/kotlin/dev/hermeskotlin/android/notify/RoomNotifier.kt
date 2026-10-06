package dev.hermeskotlin.android.notify

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rooms.RoomsApi
import dev.hermeskotlin.core.rooms.roomNewLines
import dev.hermeskotlin.core.rooms.roomOfTurnSession
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Tells the user when bots write in a hosted room while Herald is out of sight, one group conversation
 * per room. The gateway sends no room events on the socket, so this reads the room list every
 * [POLL_MS] and, for a room that moved on, the new part of its log. On going out of sight it first takes
 * stock without telling, so only what comes after notifies. Like [BotNotifier] it only hears anything
 * while the socket is up: in the background, while a turn runs or Notifications anywhere keeps Herald up.
 * Push doesn't carry room lines, so off the gateway's network rooms stay quiet.
 */
class RoomNotifier(
    private val api: RoomsApi,
    private val connection: GatewayConnection,
    private val visibility: AppVisibility,
    private val settings: SettingsStore,
    private val notifications: ChatNotifications,
    private val bots: BotNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Each room's newest log seq as last read here, so only lines after it notify. */
    private val notified = mutableMapOf<String, Int>()

    /** The gateway's rooms as last read, by id. */
    @Volatile
    private var roomIds: Set<String> = emptySet()

    /**
     * Whether the chat titled [title] is a room member's turn (the gateway runs each as a session). Its
     * reply is the room's line, told by this notifier, so the chat's own reply notification stays quiet.
     */
    fun isRoomTurn(title: String?): Boolean = roomOfTurnSession(title, roomIds) != null

    /** Whether this stretch out of sight has taken stock yet: only after that does anything notify. */
    private var stockTaken = false

    fun start() {
        // Back in sight, the user sees the rooms: the next stretch out of sight takes stock anew.
        scope.launch {
            visibility.visible.collect { shown ->
                if (shown) {
                    notified.clear()
                    stockTaken = false
                }
            }
        }
        scope.launch {
            combine(connection.state, visibility.visible) { state, shown -> state is ConnectionState.Connected && !shown }
                .distinctUntilChanged()
                .collectLatest { watching ->
                    if (!watching) return@collectLatest
                    // A reconnect out of sight carries on from the marks it had, so lines written while the
                    // socket was down still notify; only a stock-take that went through starts the telling.
                    while (true) {
                        if (stockTaken) check(notify = true) else stockTaken = check(notify = false)
                        delay(POLL_MS)
                    }
                }
        }
    }

    /** Reads the rooms, and with [notify] tells their new lines; false when the list couldn't be read. */
    private suspend fun check(notify: Boolean): Boolean {
        val rooms = try {
            api.list()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An older gateway without rooms, or the socket dropping: nothing to tell.
            return false
        }
        roomIds = rooms.mapTo(HashSet()) { it.roomId }
        val prefs = settings.settings.value ?: AppSettings()
        for (room in rooms) {
            val latest = room.latestSeq ?: continue
            if (!notify || !prefs.notifyReplies) {
                notified[room.roomId] = latest
                continue
            }
            // A room that appeared since stock was taken (made on another device) is new from its start.
            val since = notified[room.roomId] ?: 0
            if (latest <= since) continue
            val page = try {
                api.log(room.roomId, sinceSeq = since, limit = MAX_READ)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The mark stays, so the next check tries these lines again.
                continue
            }
            val lines = roomNewLines(page.events, room.members, since).map { line ->
                val profile = line.member?.profile ?: line.memberId
                val bot = bots.knownBot(profile) ?: Bot(name = profile, displayName = line.member?.displayName)
                RoomNotice(bot, bots.picture(bot), line.text, (line.createdAt * 1000).toLong())
            }
            if (lines.isNotEmpty()) notifications.postRoomMessages(room.roomId, room.name, lines)
            // Up to what was read: a busier stretch than one read tells the rest next time.
            notified[room.roomId] = if (page.events.isEmpty()) latest else page.cursor
        }
        return true
    }

    private companion object {
        /** How often the rooms are read while Herald is out of sight; rooms send no event to wait on. */
        const val POLL_MS = 30_000L

        /** Events read per room per check; a busier stretch notifies about its first part, stacked. */
        const val MAX_READ = 50
    }
}
