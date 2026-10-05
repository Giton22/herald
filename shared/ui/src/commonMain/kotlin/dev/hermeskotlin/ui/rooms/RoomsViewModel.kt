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
import dev.hermeskotlin.core.rooms.RoomMemberInput
import dev.hermeskotlin.core.rooms.RoomPendingAction
import dev.hermeskotlin.core.rooms.RoomsApi
import dev.hermeskotlin.core.rooms.mergeRoomEvents
import dev.hermeskotlin.core.rooms.roomLines
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** The gateway doesn't host rooms (an older Hermes, or the driver is off). */
    val unsupported: Boolean = false,
    /** What the last action said: why a room wasn't made. */
    val notice: String? = null,
    /** A room is being made right now, e.g. "Making the room…". */
    val busy: String? = null,
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
)

/**
 * Hosted rooms: the gateway's own group chats (`groups.*`). The sidebar lists them while it shows
 * (a slow poll — rooms change on other clients too), and one room at a time is followed live: its log
 * is read from a cursor with [RoomsApi.log] and turned into a transcript, faster while the driver is
 * working or the room waits on the user, slower when it's quiet. A sent message is appended
 * idempotently, so a lost answer retries into the same line instead of a second one.
 */
@OptIn(ExperimentalUuidApi::class)
class RoomsViewModel(
    private val api: RoomsApi,
    private val connection: GatewayConnection,
) : ViewModel() {

    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state.asStateFlow()

    private val _opened = MutableStateFlow<OpenRoom?>(null)

    /** The room open on screen, or null when the chat shows. */
    val opened: StateFlow<OpenRoom?> = _opened.asStateFlow()

    /** The open room's composer. */
    val composer = TextFieldState()

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
    }

    fun bind(url: GatewayUrl) {
        if (gateway.value == url) return
        // Another gateway's rooms are other rooms, whatever their names.
        gateway.value = url
        _state.value = RoomsUiState()
        close()
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissRoomNotice() = _opened.update { it?.copy(notice = null) }

    /**
     * Opens [room]'s conversation: reads it from the end of its log and follows it while it's on
     * screen, until [close]. A room already open is left as it is.
     */
    fun open(room: Room) {
        if (_opened.value?.room?.roomId == room.roomId && roomJob?.isActive == true) return
        close()
        openEvents = emptyList()
        readThrough = 0
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
        composer.clearText()
    }

    /** Sends what the composer holds into the open room. */
    fun send() {
        val open = _opened.value ?: return
        if (open.sending) return
        val text = composer.text.toString().trim()
        if (text.isEmpty()) return
        _opened.update { it?.copy(sending = true, notice = null) }
        viewModelScope.launch {
            try {
                val event = api.send(open.room.roomId, text = text, threadId = MAIN_THREAD, eventId = newId("message"))
                composer.clearText()
                openEvents = mergeRoomEvents(openEvents, listOf(event))
                _opened.update { it?.copy(lines = roomLines(openEvents, it.room.members)) }
                readRoomSoon(open.room.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _opened.update { it?.copy(notice = "Couldn't send. ${e.message.orEmpty()}".trim()) }
            } finally {
                _opened.update { it?.copy(sending = false) }
            }
        }
    }

    /** Asks the open room to stop what it's doing. */
    fun stop() {
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
    fun approve(action: RoomPendingAction, choice: String) {
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
    fun retry(action: RoomPendingAction) {
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
     * Makes a room from [members] (2-6 bots) and hands it to [onCreated]. The room runs on the
     * gateway, so it keeps going whether or not any app is watching.
     */
    fun createRoom(name: String, members: List<Bot>, onCreated: (Room) -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = "Making the room…", notice = null) }
        viewModelScope.launch {
            try {
                val room = api.create(
                    roomId = newId("room"),
                    name = name.trim(),
                    members = members.map { bot ->
                        RoomMemberInput(memberId = bot.name, profile = bot.name, handle = bot.name, displayName = bot.label)
                    },
                )
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
            if (!capabilities.driver) {
                if (gateway.value == url) _state.update { it.copy(loading = false, unsupported = true, error = null) }
                return
            }
            val rooms = api.list()
            if (gateway.value != url) return
            _state.update { it.copy(rooms = rooms, loading = false, error = null, unsupported = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            val unsupported = UNKNOWN_METHOD.containsMatchIn(e.message)
            _state.update { it.copy(loading = false, unsupported = unsupported, error = e.message.takeUnless { unsupported }) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the rooms.") }
        }
    }

    /** Reads the open room once, right away, e.g. after an action the driver should notice at once. */
    private fun readRoomSoon(roomId: String) {
        viewModelScope.launch { readRoom(roomId) }
    }

    private suspend fun readRoom(roomId: String) = readMutex.withLock {
        try {
            val state = api.state(roomId)
            if (_opened.value?.room?.roomId != roomId) return
            // A first read starts a bounded way back from the end: a room's whole history can be long.
            if (readThrough == 0) readThrough = maxOf(0, (state.room.latestSeq ?: 0) - PAGE)
            val page = api.log(roomId, sinceSeq = readThrough, limit = PAGE)
            if (_opened.value?.room?.roomId != roomId) return
            openEvents = mergeRoomEvents(openEvents, page.events)
            readThrough = maxOf(readThrough, page.cursor)
            _opened.update { open ->
                open?.copy(
                    room = state.room,
                    lines = roomLines(openEvents, state.room.members),
                    working = state.driverStatus?.working == true,
                    pendingActions = state.driverStatus?.pendingActions.orEmpty(),
                    loading = false,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _opened.update { open ->
                open?.takeIf { it.room.roomId == roomId }?.copy(loading = false, error = e.message ?: "Couldn't read the room.")
            }
        }
    }

    private fun newId(what: String): String = "$what-${Uuid.random()}"

    private companion object {
        /** The roster's fallback read; rooms change on other clients too, and send no event here. */
        const val REFRESH_MS = 30_000L

        /** How fast an open room is followed while the driver works or something waits on the user. */
        const val ACTIVE_POLL_MS = 2_000L

        /** …and when it's quiet. */
        const val IDLE_POLL_MS = 10_000L

        /** Events per log page. */
        const val PAGE = 200

        /** All of an app's messages go on one line of the conversation. */
        const val MAIN_THREAD = "main"

        val UNKNOWN_METHOD = Regex("method not found|unknown method|no handler", RegexOption.IGNORE_CASE)
    }
}
