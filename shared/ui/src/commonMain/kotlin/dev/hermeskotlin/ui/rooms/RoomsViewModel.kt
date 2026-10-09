package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.RoomEvent
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.core.rooms.RoomMember
import dev.hermeskotlin.core.rooms.waitingFor
import dev.hermeskotlin.core.rooms.RoomMemberInput
import dev.hermeskotlin.core.rooms.RoomPendingAction
import dev.hermeskotlin.core.rooms.RoomSeenStore
import dev.hermeskotlin.core.rooms.RoomsApi
import dev.hermeskotlin.core.rooms.isUnread
import dev.hermeskotlin.core.rooms.mergeRoomEvents
import dev.hermeskotlin.core.rooms.messageText
import dev.hermeskotlin.core.rooms.roomLines
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The Rooms section of the sidebar. */
data class RoomsUiState(
    val rooms: List<Room> = emptyList(),
    /** Nothing read yet. */
    val loading: Boolean = true,
    /** The list failed to load. */
    val error: String? = null,
    /**
     * The gateway said it hosts rooms (`groups.capabilities` with `driver`). False until it has said so,
     * and on an older Hermes or with the driver off, so the section never shows on a guess.
     */
    val available: Boolean = false,
    /** What the last action said: why a room wasn't made. */
    val notice: String? = null,
    /** A room is being made right now, e.g. "Making the room…". */
    val busy: String? = null,
    /** Why deleting or renaming a room from the sidebar didn't work. */
    val actionNotice: String? = null,
    /** The rooms with lines the user hasn't seen, by id. */
    val unread: Set<String> = emptySet(),
)

/** The room open on screen: its transcript as far as it's read, and what it's waiting on. */
data class OpenRoom(
    val room: Room,
    val lines: List<RoomLine> = emptyList(),
    /** The driver is working on a turn right now. */
    val working: Boolean = false,
    /** Approvals and retries the room waits on the user for. */
    val pendingActions: List<RoomPendingAction> = emptyList(),
    /** The first read hasn't come back yet. */
    val loading: Boolean = true,
    /** The last read failed; the loop keeps trying. */
    val error: String? = null,
    /** What the last action said: why a message wasn't sent. */
    val notice: String? = null,
    /** A message is on its way out. */
    val sending: Boolean = false,
    /** The log goes back further than what's read: scrolling up reads more. */
    val canLoadEarlier: Boolean = false,
    /** Older lines are being read right now. */
    val loadingEarlier: Boolean = false,
    /** The members still to answer the user's newest message, in turn order ([waitingFor]). */
    val waiting: List<RoomMember> = emptyList(),
)

/**
 * Hosted rooms: the gateway's own group chats (`groups.*`). The sidebar lists them while it shows
 * (a slow poll — rooms change on other clients too), and one room at a time is followed live: its log
 * is read from a cursor with [RoomsApi.log] and turned into a transcript, faster while the driver is
 * working or the room waits on the user, slower when it's quiet. A sent message is appended
 * idempotently, so a lost answer retries into the same line instead of a second one.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class RoomsViewModel(
    private val api: RoomsApi,
    private val connection: GatewayConnection,
    private val seen: RoomSeenStore,
) : ViewModel(), RoomActions {

    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state.asStateFlow()

    private val _opened = MutableStateFlow<OpenRoom?>(null)

    /** The room open on screen, or null when the chat shows. */
    val opened: StateFlow<OpenRoom?> = _opened.asStateFlow()

    /** The open room's composer. */
    override val composer = TextFieldState()

    private val gateway = MutableStateFlow<GatewayUrl?>(null)

    /** Whether the sidebar is on screen; it stays composed while closed. */
    private val visible = MutableStateFlow(false)

    private var roomJob: Job? = null

    /** Serializes reads, so a catch-up right after an action can't race the loop. */
    private val readMutex = Mutex()

    /** The open room's events, by seq; the transcript is drawn from these. */
    private var openEvents: List<RoomEvent> = emptyList()

    /** Where the open room's log has been read to (the page cursor). */
    private var readThrough = 0

    /** Where the open room's read part starts (the `since_seq` of its oldest read); null before the first read. */
    private var loadedFrom: Int? = null

    /** How many events the next [loadEarlier] asks for, smaller after a read that came back short; null for a full page. */
    private var earlierWindow: Int? = null

    /** Counts the room's openings, so a read started before a close never lands in the next opening. */
    private var openGeneration = 0

    /** Counts deletes and renames that landed, so a list read from before one doesn't undo it. */
    private var listEdits = 0

    /** The ids of deletes asked without an answer back, by room, so asking again is the same ask. */
    private val disbandIds = mutableMapOf<String, String>()

    /** Events per log page: [PAGE], or less when the gateway's `max_log_limit` says so. */
    private var logLimit = PAGE

    /** The last message sent without an answer back, kept so a retry of it can't post twice. */
    private var unansweredSend: UnansweredSend? = null

    /** The last room asked for (name and roster) without an answer back, with the id it was asked under. */
    private var unansweredCreate: Pair<Pair<String, List<RoomMemberInput>>, String>? = null

    init {
        // While the sidebar and the connection are live: read the rooms, then slowly besides.
        viewModelScope.launch {
            combine(gateway, visible, connection.state) { url, shown, conn ->
                url != null && shown && conn is ConnectionState.Connected
            }.distinctUntilChanged().collectLatest { live ->
                while (live) {
                    readRooms()
                    delay(REFRESH_MS)
                }
            }
        }
        // Unread follows both the list and what the user has seen, on this gateway.
        viewModelScope.launch {
            gateway.filterNotNull().flatMapLatest { url -> seen.seen(url) }.collect { read ->
                seenSeqs = read
                _state.update { it.withUnread() }
            }
        }
    }

    /** Each room's seen seq on the bound gateway ([RoomSeenStore]). */
    private var seenSeqs: Map<String, Int> = emptyMap()

    private fun RoomsUiState.withUnread() = copy(unread = rooms.filter { it.isUnread(seenSeqs) }.map { it.roomId }.toSet())

    /**
     * Opens the room [roomId] from outside the screens (a tapped notification): as the list knows it, or
     * by its id and [name] alone, the first read filling in the rest.
     */
    fun openById(roomId: String, name: String?) {
        open(_state.value.rooms.firstOrNull { it.roomId == roomId } ?: Room(roomId = roomId, name = name ?: "Room"))
    }

    fun bind(url: GatewayUrl) {
        val previous = gateway.value
        if (previous == url) return
        // Another gateway's rooms are other rooms, whatever their names.
        gateway.value = url
        _state.value = RoomsUiState()
        logLimit = PAGE
        unansweredSend = null
        unansweredCreate = null
        disbandIds.clear()
        quietChecked.clear()
        // The first bind isn't a switch: a room opened from a notification at launch stays open.
        if (previous != null) close()
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    override fun dismissRoomNotice() = _opened.update { it?.copy(notice = null) }

    /**
     * Opens [room]'s conversation: reads it from the end of its log and follows it while it's on
     * screen, until [close]. A room already open is left as it is.
     */
    fun open(room: Room) {
        if (_opened.value?.room?.roomId == room.roomId && roomJob?.isActive == true) return
        close()
        _opened.value = OpenRoom(room = room)
        roomJob = viewModelScope.launch {
            while (true) {
                val current = _opened.value ?: break
                if (current.room.roomId != room.roomId) break
                readRoom(room.roomId)
                val after = _opened.value ?: break
                delay(if (after.working || after.pendingActions.isNotEmpty()) ACTIVE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    /** Leaves the open room; its polling stops. */
    fun close() {
        roomJob?.cancel()
        roomJob = null
        _opened.value = null
        openEvents = emptyList()
        readThrough = 0
        loadedFrom = null
        earlierWindow = null
        openGeneration++
        composer.clearText()
    }

    /**
     * Reads the stretch of the open room's log just before what's shown, for a reader scrolling up. The
     * gateway only reads forward, so this reads the window that ends where the shown part starts, and
     * adds it only once it's all in: a page cut short never leaves a gap in the transcript.
     */
    override fun loadEarlier() {
        val open = _opened.value ?: return
        if (!open.canLoadEarlier || open.loadingEarlier) return
        val roomId = open.room.roomId
        // Closing the room (even to open it again) ends this read: its window belongs to that opening.
        val generation = openGeneration
        fun stillOpen() = openGeneration == generation && _opened.value?.room?.roomId == roomId
        fun updateOpen(change: (OpenRoom) -> OpenRoom) = _opened.update { current ->
            current?.takeIf { stillOpen() }?.let(change) ?: current
        }
        updateOpen { it.copy(loadingEarlier = true) }
        viewModelScope.launch {
            readMutex.withLock {
                try {
                    val end = loadedFrom ?: return@withLock
                    if (!stillOpen() || end <= 0) return@withLock
                    val limit = logLimit
                    val window = minOf(earlierWindow ?: limit, limit)
                    val from = maxOf(0, end - window)
                    val earlier = mutableListOf<RoomEvent>()
                    var since = from
                    var pages = 0
                    while (since < end && pages < MAX_PAGES_PER_READ) {
                        val page = api.log(roomId, sinceSeq = since, limit = minOf(limit, end - since))
                        if (!stillOpen()) return@withLock
                        earlier += page.events.filter { it.seq <= end }
                        if (page.cursor <= since) break
                        since = page.cursor
                        pages++
                    }
                    // Not all of it came: keep what's shown whole, and try a smaller window after the next poll,
                    // so a stretch of very large events still gets through in the end.
                    if (since < end) {
                        earlierWindow = maxOf(1, window / 2)
                        updateOpen { it.copy(canLoadEarlier = false) }
                        return@withLock
                    }
                    earlierWindow = null
                    openEvents = mergeRoomEvents(openEvents, earlier)
                    loadedFrom = from
                    updateOpen {
                        it.copy(
                            lines = roomLines(openEvents, it.room.members),
                            waiting = waitingFor(openEvents, it.room.members),
                            canLoadEarlier = from > 0,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Paused until the next poll, so a reader parked at the top doesn't retry in a loop.
                    updateOpen {
                        it.copy(notice = "Couldn't read older messages. ${e.message.orEmpty()}".trim(), canLoadEarlier = false)
                    }
                } finally {
                    updateOpen { it.copy(loadingEarlier = false) }
                }
            }
        }
    }

    /** Sends what the composer holds into the open room. */
    override fun send() {
        val open = _opened.value ?: return
        if (open.sending) return
        val text = composer.text.toString().trim()
        if (text.isEmpty()) return
        val roomId = open.room.roomId
        // Sending the same text again after an unanswered try reuses its id, so the gateway takes it once.
        val attempt = unansweredSend?.takeIf { it.roomId == roomId && it.text == text }
            ?: UnansweredSend(roomId, text, newId("message"), sentAfter = readThrough)
        unansweredSend = attempt
        val eventId = attempt.eventId
        _opened.update { it?.copy(sending = true, notice = null) }
        viewModelScope.launch {
            try {
                val event = api.send(roomId, text = text, threadId = MAIN_THREAD, eventId = eventId)
                if (unansweredSend?.eventId == eventId) unansweredSend = null
                // The user may have left for another room while this was on its way.
                if (_opened.value?.room?.roomId != roomId) return@launch
                // Only what was sent goes: a draft typed meanwhile (or in the room reopened since) stays.
                if (composer.text.toString().trim() == text) composer.clearText()
                openEvents = mergeRoomEvents(openEvents, listOf(event))
                _opened.update {
                    it?.copy(lines = roomLines(openEvents, it.room.members), waiting = waitingFor(openEvents, it.room.members))
                }
                readRoomSoon(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _opened.update { current ->
                    current?.takeIf { it.room.roomId == roomId }?.copy(notice = "Couldn't send. ${e.message.orEmpty()}".trim()) ?: current
                }
            } finally {
                _opened.update { current -> current?.takeIf { it.room.roomId == roomId }?.copy(sending = false) ?: current }
            }
        }
    }

    /** Asks the open room to stop what it's doing. */
    override fun stop() {
        val open = _opened.value ?: return
        viewModelScope.launch {
            try {
                api.stop(open.room.roomId, cancelId = newId("stop"))
                readRoomSoon(open.room.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _opened.update { it?.copy(notice = "Couldn't stop the room. ${e.message.orEmpty()}".trim()) }
            }
        }
    }

    /** Answers one pending approval: `once` or `deny`, exactly as the room's state carried it. */
    override fun approve(action: RoomPendingAction, choice: String) {
        val open = _opened.value ?: return
        val memberId = action.memberId
        val taskId = action.taskId
        val generation = action.executionGeneration
        val requestId = action.requestId
        if (memberId == null || taskId == null || generation == null || requestId == null) {
            _opened.update { it?.copy(notice = "The gateway didn't say enough to answer that approval.") }
            return
        }
        viewModelScope.launch {
            try {
                api.approve(open.room.roomId, memberId, taskId, generation, choice, requestId)
                readRoomSoon(open.room.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _opened.update { it?.copy(notice = "Couldn't answer the approval. ${e.message.orEmpty()}".trim()) }
            }
        }
    }

    /** Gives one failed turn another go. */
    override fun retry(action: RoomPendingAction) {
        val open = _opened.value ?: return
        val taskId = action.taskId
        if (taskId == null) {
            _opened.update { it?.copy(notice = "The gateway didn't say which turn to retry.") }
            return
        }
        viewModelScope.launch {
            try {
                api.retry(open.room.roomId, taskId)
                readRoomSoon(open.room.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _opened.update { it?.copy(notice = "Couldn't retry. ${e.message.orEmpty()}".trim()) }
            }
        }
    }

    /**
     * Deletes [room] on the gateway, for every client: its work stops and it leaves the list. A try
     * again after a lost answer is the same ask, so it can't fail on a room already gone.
     */
    override fun deleteRoom(room: Room) {
        val cancelId = disbandIds.getOrPut(room.roomId) { newId("disband") }
        viewModelScope.launch {
            try {
                api.disband(room.roomId, cancelId)
                disbandIds.remove(room.roomId)
                listEdits++
                _state.update { state -> state.copy(rooms = state.rooms.filterNot { it.roomId == room.roomId }) }
                if (_opened.value?.room?.roomId == room.roomId) close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(room.roomId, "Couldn't delete “${room.name}”. ${e.message.orEmpty()}".trim())
            }
        }
    }

    /** Renames [room] to [name] on the gateway; the list and the open room follow. */
    override fun renameRoom(room: Room, name: String) {
        val newName = name.trim()
        if (newName.isEmpty() || newName == room.name) return
        viewModelScope.launch {
            try {
                val renamed = api.rename(room.roomId, newName, eventId = newId("rename"))
                listEdits++
                _state.update { state -> state.copy(rooms = state.rooms.map { if (it.roomId == renamed.roomId) renamed else it }) }
                _opened.update { open -> open?.takeIf { it.room.roomId == renamed.roomId }?.copy(room = renamed) ?: open }
                readRoomSoon(renamed.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(room.roomId, "Couldn't rename “${room.name}”. ${e.message.orEmpty()}".trim())
            }
        }
    }

    fun dismissActionNotice() = _state.update { it.copy(actionNotice = null) }

    /** Tells the user about [roomId]: on its screen while it's open, else in the sidebar's Rooms. */
    private fun say(roomId: String, message: String) {
        if (_opened.value?.room?.roomId == roomId) {
            _opened.update { it?.copy(notice = message) }
        } else {
            _state.update { it.copy(actionNotice = message) }
        }
    }

    /**
     * Makes a room from [members] (2-6 bots) and hands it to [onCreated]. The room runs on the
     * gateway, so it keeps going whether or not any app is watching.
     */
    fun createRoom(name: String, members: List<Bot>, onCreated: (Room) -> Unit) {
        if (_state.value.busy != null) return
        val roster = members.map { bot ->
            RoomMemberInput(memberId = bot.name, profile = bot.name, handle = bot.name, displayName = bot.label)
        }
        // Trying the same room again after an unanswered try reuses its id, so the gateway makes it once.
        val ask = name.trim() to roster
        val roomId = unansweredCreate?.takeIf { it.first == ask }?.second ?: newId("room")
        unansweredCreate = ask to roomId
        _state.update { it.copy(busy = "Making the room…", notice = null) }
        viewModelScope.launch {
            try {
                val room = api.create(roomId = roomId, name = ask.first, members = roster)
                unansweredCreate = null
                readRooms()
                onCreated(room)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't make the room. ${e.message.orEmpty()}".trim()) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private suspend fun readRooms() {
        val url = gateway.value ?: return
        try {
            val capabilities = api.capabilities()
            if (gateway.value != url) return
            if (!capabilities.driver) {
                _state.update { it.copy(loading = false, available = false, error = null) }
                return
            }
            logLimit = capabilities.maxLogLimit.coerceIn(1, PAGE)
            val edits = listEdits
            val rooms = api.list()
            if (gateway.value != url) return
            // A delete or rename landed while this was read: the list may predate it, so wait for the next.
            if (listEdits != edits) return
            // Rooms first met now count as read so far: only what comes after is new.
            seen.takeStock(url, rooms)
            settleQuietRooms(url, rooms)
            // Stored meanwhile: the same checks again, as a switch or an edit may have landed during it.
            if (gateway.value != url || listEdits != edits) return
            _state.update { it.copy(rooms = rooms, loading = false, error = null, available = true).withUnread() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            if (gateway.value != url) return
            // An older gateway doesn't know `groups.*`: no section. Any other failure keeps what's known.
            val unsupported = UNKNOWN_METHOD.containsMatchIn(e.message)
            _state.update {
                it.copy(loading = false, available = it.available && !unsupported, error = e.message.takeUnless { unsupported })
            }
        } catch (e: Exception) {
            if (gateway.value != url) return
            _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the rooms.") }
        }
    }

    /**
     * A room's newest seq counts every event, the bookkeeping too (a rename, a turn settling after its
     * reply, a stop): only a bot's line makes a room unread. For a room past its seen mark, reads what's new
     * once per new seq, and when it holds no bot line, moves the mark up to it.
     */
    private suspend fun settleQuietRooms(url: GatewayUrl, rooms: List<Room>) {
        for (room in rooms) {
            val read = seenSeqs[room.roomId] ?: continue
            val latest = room.latestSeq ?: continue
            if (latest <= read || quietChecked[room.roomId] == latest) continue
            quietChecked[room.roomId] = latest
            val page = try {
                api.log(room.roomId, sinceSeq = read, limit = logLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                quietChecked.remove(room.roomId)
                continue
            }
            if (page.cursor >= latest && page.events.none { it.kind == "message.member" }) seen.markSeen(url, room.roomId, latest)
        }
    }

    /** The newest seq [settleQuietRooms] read each room up to, so a room waiting to be read isn't read again. */
    private val quietChecked = mutableMapOf<String, Int>()

    /** Reads the open room once, right away, e.g. after an action the driver should notice at once. */
    private fun readRoomSoon(roomId: String) {
        viewModelScope.launch { readRoom(roomId) }
    }

    private suspend fun readRoom(roomId: String): Unit = readMutex.withLock {
        try {
            val state = api.state(roomId)
            if (_opened.value?.room?.roomId != roomId) return
            // Never ask for more than the gateway serves in one page.
            val limit = logLimit
            // A first read starts a bounded way back from the end: a room's whole history can be long, and
            // scrolling up reads the rest (loadEarlier).
            if (loadedFrom == null) {
                val from = maxOf(0, (state.room.latestSeq ?: 0) - limit)
                loadedFrom = from
                readThrough = from
            }
            // A page that stops short of the end is followed right away, so the newest lines aren't a poll late.
            var pages = 0
            do {
                val page = api.log(roomId, sinceSeq = readThrough, limit = limit)
                if (_opened.value?.room?.roomId != roomId) return
                openEvents = mergeRoomEvents(openEvents, page.events)
                val advanced = page.cursor > readThrough
                readThrough = maxOf(readThrough, page.cursor)
                pages++
            } while (page.hasMore && advanced && pages < MAX_PAGES_PER_READ)
            // An unanswered send that shows up in the log did land: the same words later are a new message.
            unansweredSend?.takeIf { it.roomId == roomId }?.let { pending ->
                val landed = openEvents.any {
                    it.seq > pending.sentAfter && it.kind == "message.user" && it.messageText?.trim() == pending.text
                }
                if (landed) unansweredSend = null
            }
            _opened.update { open ->
                open?.copy(
                    room = state.room,
                    lines = roomLines(openEvents, state.room.members),
                    waiting = waitingFor(openEvents, state.room.members),
                    working = state.driverStatus?.working == true,
                    pendingActions = state.driverStatus?.pendingActions.orEmpty(),
                    loading = false,
                    error = null,
                    canLoadEarlier = (loadedFrom ?: 0) > 0,
                )
            }
            // On screen, so read: the sidebar's dot and the next notification start after this.
            gateway.value?.let { seen.markSeen(it, roomId, readThrough) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A late failure for a room that's no longer open leaves the one on screen alone.
            _opened.update { open ->
                open?.takeIf { it.room.roomId == roomId }?.copy(loading = false, error = e.message ?: "Couldn't read the room.") ?: open
            }
        }
    }

    private fun newId(what: String): String = "$what-${Uuid.random()}"

    /** A send with no answer back: [sentAfter] is the log seq read through when it went out. */
    private data class UnansweredSend(val roomId: String, val text: String, val eventId: String, val sentAfter: Int)

    private companion object {
        /** The roster's fallback read; rooms change on other clients too, and send no event here. */
        const val REFRESH_MS = 30_000L

        /** How fast an open room is followed while the driver works or something waits on the user. */
        const val ACTIVE_POLL_MS = 2_000L

        /** …and when it's quiet. */
        const val IDLE_POLL_MS = 10_000L

        /** Events per log page, at most; the gateway's own `max_log_limit` can make it smaller. */
        const val PAGE = 200

        /** How many pages one read follows before leaving the rest to the next poll. */
        const val MAX_PAGES_PER_READ = 5

        /** All of an app's messages go on one line of the conversation. */
        const val MAIN_THREAD = "main"

        val UNKNOWN_METHOD = Regex("method not found|unknown method|no handler", RegexOption.IGNORE_CASE)
    }
}
