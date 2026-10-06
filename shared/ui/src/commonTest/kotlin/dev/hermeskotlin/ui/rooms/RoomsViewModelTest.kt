package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.core.rooms.RoomsApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val roomA = Room(roomId = "a", name = "Room A", latestSeq = 0)
    private val roomB = Room(roomId = "b", name = "Room B", latestSeq = 0)

    /**
     * A gateway socket that says it's ready and answers every request with [answer]: its result, or
     * an RPC error when the answer starts with `!`. Every `groups.*` request is kept, as (method, params).
     * Each answer comes back on its own, so one held answer doesn't hold the requests after it.
     */
    private class RoomsGateway(
        private val scope: CoroutineScope,
        private val answer: suspend (String, JsonObject) -> String,
    ) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        val requests = mutableListOf<Pair<String, JsonObject>>()

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val method = message["method"]?.jsonPrimitive?.content ?: return
            val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
            if (method.startsWith("groups.")) requests += method to params
            scope.launch {
                val reply = answer(method, params).replace("\n", " ")
                inbound.send(
                    if (reply.startsWith("!")) """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"${reply.drop(1)}"}}"""
                    else """{"jsonrpc":"2.0","id":$id,"result":$reply}""",
                )
            }
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }

        fun sent(method: String): List<JsonObject> = requests.filter { it.first == method }.map { it.second }
    }

    private suspend fun viewModel(socket: RoomsGateway): RoomsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json) }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> socket }, CoroutineScope(dispatcher))
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        return RoomsViewModel(RoomsApi(connection), connection)
    }

    /** The answers of a quiet room: empty state and log, whatever [roomId] is asked about. */
    private fun quietRoom(method: String, params: JsonObject): String? {
        val roomId = params["room_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (method) {
            "groups.state" -> """{"room":{"room_id":"$roomId","name":"Room $roomId","members":[],"latest_seq":0}}"""
            "groups.log" -> """{"events":[],"cursor":0,"latest_seq":0,"has_more":false}"""
            else -> null
        }
    }

    @Test
    fun theRoomsSectionShowsOnlyOnceTheGatewaySaysItHostsRooms() = runTest(dispatcher) {
        var driver = false
        val socket = RoomsGateway(CoroutineScope(dispatcher)) {method, _ ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":2,"driver":$driver}"""
                "groups.list" -> """{"rooms":[]}"""
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        // Nothing asked yet: no section on a guess.
        assertFalse(vm.state.value.available)

        vm.bind(gateway.gatewayUrl)
        vm.setVisible(true)
        assertFalse(vm.state.first { !it.loading }.available)
        vm.setVisible(false)

        driver = true
        vm.setVisible(true)
        assertTrue(vm.state.first { it.available }.available)
        vm.setVisible(false)
    }

    @Test
    fun aLoadFailureBeforeTheGatewayAnsweredShowsNoSection() = runTest(dispatcher) {
        val socket = RoomsGateway(CoroutineScope(dispatcher)) {method, _ -> if (method == "groups.capabilities") "!The gateway is busy" else "{}" }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.setVisible(true)
        val state = vm.state.first { !it.loading }
        assertFalse(state.available)
        assertEquals("The gateway is busy", state.error)
        vm.setVisible(false)
    }

    @Test
    fun sendingTheSameTextAgainAfterAFailedTryReusesItsEventId() = runTest(dispatcher) {
        var sends = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) {method, params ->
            quietRoom(method, params) ?: when (method) {
                // The first answer is lost; the second comes back.
                "groups.send" -> if (sends++ == 0) "!Lost on the way" else
                    """{"event":{"room_id":"a","seq":1,"event_id":"user:1","kind":"message.user","actor":{"kind":"user","id":"phone"},"payload":{"text":"hello"}}}"""
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.open(roomA)

        vm.composer.setTextAndPlaceCursorAtEnd("hello")
        vm.send()
        assertNotNull(vm.opened.first { it?.notice != null }?.notice)
        vm.send()
        vm.opened.first { it?.lines?.isNotEmpty() == true }

        val ids = socket.sent("groups.send").map { it["event_id"]?.jsonPrimitive?.contentOrNull }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])

        // Something new is a new message.
        vm.composer.setTextAndPlaceCursorAtEnd("and more")
        vm.send()
        assertNotEquals(ids[0], socket.sent("groups.send").last()["event_id"]?.jsonPrimitive?.contentOrNull)
        vm.close()
    }

    @Test
    fun theSameWordsTypedAgainLaterAreANewMessage() = runTest(dispatcher) {
        var sends = 0
        val yes = """{"room_id":"a","seq":1,"event_id":"user:1","kind":"message.user","actor":{"kind":"user","id":"phone"},"payload":{"text":"yes"}}"""
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
            when (method) {
                // The first answer is lost though the gateway took it; later ones come back.
                "groups.send" -> if (sends++ == 0) "!Lost on the way" else """{"event":${yes.replace("\"seq\":1", "\"seq\":2")}}"""
                "groups.state" -> """{"room":{"room_id":"a","name":"Room A","members":[],"latest_seq":${if (sends > 0) 1 else 0}}}"""
                "groups.log" -> if (sends > 0) """{"events":[$yes],"cursor":1,"latest_seq":1,"has_more":false}"""
                    else """{"events":[],"cursor":0,"latest_seq":0,"has_more":false}"""
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.open(roomA)
        try {
            vm.opened.first { it?.loading == false }
            vm.composer.setTextAndPlaceCursorAtEnd("yes")
            vm.send()
            vm.opened.first { it?.notice != null }
            // The next read shows it landed after all.
            vm.opened.first { open -> open?.lines.orEmpty().any { it is RoomLine.Message && it.text == "yes" } }

            // Later the user answers "yes" to something else.
            vm.composer.setTextAndPlaceCursorAtEnd("yes")
            vm.send()
            vm.opened.first { it?.sending == false }

            val ids = socket.sent("groups.send").map { it["event_id"]?.jsonPrimitive?.contentOrNull }
            assertEquals(2, ids.size)
            assertNotEquals(ids[0], ids[1])
        } finally {
            vm.close()
        }
    }

    @Test
    fun aDraftTypedWhileASendIsOnItsWayIsKept() = runTest(dispatcher) {
        val sendGate = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
            quietRoom(method, params) ?: when (method) {
                "groups.send" -> {
                    sendGate.await()
                    """{"event":{"room_id":"a","seq":1,"event_id":"user:1","kind":"message.user","actor":{"kind":"user","id":"phone"},"payload":{"text":"first"}}}"""
                }
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.open(roomA)
        vm.composer.setTextAndPlaceCursorAtEnd("first")
        vm.send()
        vm.composer.setTextAndPlaceCursorAtEnd("second thought")
        sendGate.complete(Unit)
        vm.opened.first { it?.lines?.isNotEmpty() == true }

        assertEquals("second thought", vm.composer.text.toString())
        vm.close()
    }

    @Test
    fun aSendThatLandsAfterLeavingTheRoomStaysOutOfTheNextOne() = runTest(dispatcher) {
        val sendGate = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) {method, params ->
            quietRoom(method, params) ?: when (method) {
                "groups.send" -> {
                    sendGate.await()
                    """{"event":{"room_id":"a","seq":1,"event_id":"user:1","kind":"message.user","actor":{"kind":"user","id":"phone"},"payload":{"text":"for room A"}}}"""
                }
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.open(roomA)
        vm.composer.setTextAndPlaceCursorAtEnd("for room A")
        // The gateway answers on its own time, as it would over a slow socket.
        vm.send()
        vm.open(roomB)
        vm.opened.first { it?.room?.roomId == "b" && !it.loading }
        vm.composer.setTextAndPlaceCursorAtEnd("draft for B")
        sendGate.complete(Unit)
        runCurrent()

        val open = vm.opened.value
        assertEquals("b", open?.room?.roomId)
        assertTrue(open?.lines.orEmpty().none { it is RoomLine.Message && it.text == "for room A" })
        assertEquals("draft for B", vm.composer.text.toString())
        vm.close()
    }

    @Test
    fun aRoomIsReadInPagesTheGatewayServesToItsEnd() = runTest(dispatcher) {
        fun event(seq: Int) =
            """{"room_id":"a","seq":$seq,"event_id":"e:$seq","kind":"message.member","actor":{"kind":"member","id":"ops"},"payload":{"text":"line $seq","member_id":"ops"}}"""
        val socket = RoomsGateway(CoroutineScope(dispatcher)) {method, params ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":2,"driver":true,"max_log_limit":2}"""
                "groups.list" -> """{"rooms":[]}"""
                "groups.state" -> """{"room":{"room_id":"a","name":"Room A","members":[],"latest_seq":6}}"""
                "groups.log" -> {
                    // A busy gateway may serve less than was asked for, and says there's more.
                    val since = params["since_seq"]!!.jsonPrimitive.int
                    val seqs = (since + 1..6).take(1)
                    """{"events":[${seqs.joinToString(",") { event(it) }}],"cursor":${seqs.lastOrNull() ?: since},"latest_seq":6,"has_more":${(seqs.lastOrNull() ?: since) < 6}}"""
                }
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.setVisible(true)
        vm.state.first { it.available }
        vm.setVisible(false)

        vm.open(roomA.copy(latestSeq = 6))
        val lines = vm.opened.first { it?.loading == false }!!.lines

        // Never more than the gateway serves at once, and the newest line is there after one read.
        assertTrue(socket.sent("groups.log").all { it["limit"]?.jsonPrimitive?.int == 2 })
        assertEquals(listOf("line 5", "line 6"), lines.map { (it as RoomLine.Message).text })
        vm.close()
    }

    @Test
    fun deletingTheOpenRoomClosesItAndTakesItOffTheList() = runTest(dispatcher) {
        var disbanded = false
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":2,"driver":true}"""
                "groups.list" -> if (disbanded) """{"rooms":[]}""" else
                    """{"rooms":[{"room_id":"a","name":"Room A","members":[]},{"room_id":"b","name":"Room B","members":[]}]}"""
                "groups.disband" -> {
                    disbanded = true
                    """{"tombstone":{"room_id":"a","disbanded_at":5.0,"idempotent":false}}"""
                }
                else -> quietRoom(method, params) ?: "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.setVisible(true)
        vm.state.first { it.rooms.size == 2 }
        vm.setVisible(false)
        vm.open(roomA)

        vm.deleteRoom(roomA)
        vm.opened.first { it == null }

        assertEquals(listOf("b"), vm.state.value.rooms.map { it.roomId })
        assertEquals("a", socket.sent("groups.disband").single()["room_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aFailedDeleteFromTheSidebarSaysWhyThere() = runTest(dispatcher) {
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, _ ->
            if (method == "groups.disband") "!The room is busy" else "{}"
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)

        vm.deleteRoom(roomB)
        val notice = vm.state.first { it.actionNotice != null }.actionNotice!!
        assertTrue(notice.contains("Room B") && notice.contains("The room is busy"))
    }

    @Test
    fun renamingTheOpenRoomRenamesItThereAndInTheList() = runTest(dispatcher) {
        var name = "Room A"
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":2,"driver":true}"""
                "groups.list" -> """{"rooms":[{"room_id":"a","name":"$name","members":[]}]}"""
                "groups.rename" -> {
                    name = params["name"]!!.jsonPrimitive.content
                    """{"room":{"room_id":"a","name":"$name","members":[]}}"""
                }
                "groups.state" -> """{"room":{"room_id":"a","name":"$name","members":[],"latest_seq":0}}"""
                else -> quietRoom(method, params) ?: "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        vm.setVisible(true)
        vm.state.first { it.rooms.isNotEmpty() }
        vm.setVisible(false)
        vm.open(roomA)
        vm.opened.first { it?.loading == false }
        try {
            vm.renameRoom(roomA, "  Release room ")
            assertEquals("Release room", vm.state.first { it.rooms.single().name != "Room A" }.rooms.single().name)
            assertEquals("Release room", vm.opened.value?.room?.name)
        } finally {
            vm.close()
        }
    }

    @Test
    fun aRoomThatWasntMadeSaysWhyAndATryAgainIsTheSameRoom() = runTest(dispatcher) {
        var creates = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) {method, params ->
            when (method) {
                "groups.create" -> if (creates++ == 0) "!Handle ops is taken" else
                    """{"room":{"room_id":"${params["room_id"]!!.jsonPrimitive.content}","name":"Release","members":[]}}"""
                "groups.capabilities" -> """{"protocol_version":2,"driver":true}"""
                "groups.list" -> """{"rooms":[]}"""
                else -> quietRoom(method, params) ?: "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        val bots = listOf(Bot(name = "ops"), Bot(name = "scribe"))

        vm.createRoom("Release", bots) {}
        val failed = vm.state.first { it.busy == null && it.notice != null }
        assertTrue(failed.notice!!.contains("Handle ops is taken"))

        var made: Room? = null
        vm.createRoom("Release", bots) { made = it }
        vm.state.first { it.busy == null }
        val ids = socket.sent("groups.create").map { it["room_id"]?.jsonPrimitive?.contentOrNull }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertEquals(ids[0], made?.roomId)
    }
}
