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
import dev.hermeskotlin.core.rooms.RoomSeenStore
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
import kotlinx.coroutines.test.advanceTimeBy
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

    /** What the user has seen in each room; shared by a test's view models, as the app's single store is. */
    private val seenStore = RoomSeenStore(InMemoryKeyValueStore())

    private suspend fun viewModel(socket: RoomsGateway): RoomsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json) }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> socket }, CoroutineScope(dispatcher))
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        return RoomsViewModel(RoomsApi(connection), connection, seenStore)
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

    private fun picked(id: String, size: Int = 3, mime: String = "text/csv") =
        dev.hermeskotlin.core.chat.OutgoingAttachment(id, "$id.csv", mime, ByteArray(size) { 97 })

    private fun uploadReply(params: JsonObject) = """{"attachment_id":"att_${params["upload_id"]!!.jsonPrimitive.content}","kind":"file","name":"${params["name"]!!.jsonPrimitive.content}","mime":"text/csv","size":3}"""

    private fun attachmentReply(method: String, params: JsonObject): String? = when (method) {
        "groups.capabilities" -> """{"driver":true,"methods":["groups.send","groups.attachment.upload"]}"""
        "groups.list" -> """{"rooms":[]}"""
        "groups.attachment.upload" -> uploadReply(params)
        "groups.send" -> """{"event":{"room_id":"a","seq":1,"event_id":"accepted","kind":"message.user","actor":{"kind":"user","id":"u"},"payload":${params["payload"]}}}"""
        else -> quietRoom(method, params)
    }

    private suspend fun attachmentRoom(socket: RoomsGateway): RoomsViewModel = viewModel(socket).apply {
        bind(gateway.gatewayUrl); setVisible(true)
        state.first { !it.loading }; open(roomA)
        opened.first { it?.loading == false }
    }

    @Test
    fun aLogConfirmedFileSendCannotBeRelockedByItsLostReply() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var posted: JsonObject? = null
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            when {
                m == "groups.send" -> { posted = p["payload"]!!.jsonObject; held.await(); attachmentReply(m, p)!! }
                m == "groups.state" && posted != null -> """{"room":{"room_id":"a","name":"A","latest_seq":1}}"""
                m == "groups.log" && posted != null -> """{"events":[{"room_id":"a","seq":1,"event_id":"accepted","kind":"message.user","actor":{"kind":"user","id":"u"},"payload":$posted}],"cursor":1,"latest_seq":1}"""
                else -> attachmentReply(m, p) ?: "{}"
            }
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            advanceTimeBy(60_001); runCurrent()
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            assertFalse(vm.opened.value!!.attachmentRetry)
            assertEquals(null, vm.opened.value!!.notice)
            vm.addAttachments(listOf(picked("next")), vm.opened.value!!.draftKey)
            assertEquals("next", vm.opened.value!!.attachments.single().id)
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun sendingInAnotherRoomPreservesTheFirstRoomsUnknownFileSend() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var attempts = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send" && p["room_id"]!!.jsonPrimitive.content == "a" && attempts++ == 0) held.await()
            (attachmentReply(m, p) ?: "{}").let { reply ->
                if (m == "groups.send" && p["room_id"]!!.jsonPrimitive.content == "b") reply.replace("\"room_id\":\"a\"", "\"room_id\":\"b\"") else reply
            }
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            advanceTimeBy(60_001); runCurrent()
            vm.open(roomB); vm.composer.setTextAndPlaceCursorAtEnd("for B"); vm.send(); runCurrent()
            vm.open(roomA)
            assertEquals("report", vm.opened.value!!.attachments.single().id)
            vm.send(); runCurrent()
            val sends = socket.sent("groups.send").filter { it["room_id"]!!.jsonPrimitive.content == "a" }
            assertEquals(2, sends.size)
            assertEquals(sends[0], sends[1])
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun deletingARoomRetiresItsUnansweredFileSend() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send") held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            advanceTimeBy(60_001); runCurrent()
            vm.deleteRoom(roomA); runCurrent()
            vm.open(roomA)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            assertFalse(vm.opened.value!!.attachmentRetry)
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun leavingAndReopeningAnUnansweredFileSendKeepsTheRetryIdentity() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var sends = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send" && sends++ == 0) held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            vm.open(roomB); vm.open(roomA); held.complete(Unit); runCurrent()
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(socket.sent("groups.send")[0], socket.sent("groups.send")[1])
            assertEquals(1, socket.sent("groups.attachment.upload").size)
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun aMalformedSendAcknowledgementStillRequiresTheOriginalRetry() = runTest(dispatcher) {
        var sends = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send" && sends++ == 0) "{}" else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            vm.opened.first { it?.notice != null }
            assertTrue(vm.opened.value!!.attachmentRetry)
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(socket.sent("groups.send")[0], socket.sent("groups.send")[1])
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun anUploadFromThePreviousGatewayCannotSendToTheNewGateway() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload") held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            vm.bind(SavedGateway("https://other.example").gatewayUrl); vm.open(roomA)
            held.complete(Unit); runCurrent()
            assertTrue(socket.sent("groups.send").isEmpty())
            assertTrue(vm.opened.value!!.attachments.isEmpty())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun anUnknownSendRetriesTheOriginalFilesAndPreservesANewerDraft() = runTest(dispatcher) {
        var sends = 0
        val lost = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send" && sends++ == 0) lost.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("original")), vm.opened.value!!.draftKey)
            vm.send(); advanceTimeBy(60_001); runCurrent()
            assertTrue(vm.opened.value!!.attachmentRetry)
            vm.composer.setTextAndPlaceCursorAtEnd("a newer draft")
            vm.removeAttachment("original")
            vm.addAttachments(listOf(picked("replacement")), vm.opened.value!!.draftKey)
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(socket.sent("groups.send")[0], socket.sent("groups.send")[1])
            assertEquals(1, socket.sent("groups.attachment.upload").size)
            assertEquals("a newer draft", vm.composer.text.toString())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun lostUploadAnswerReusesItsIdAndRemovalBeforeSendDoesNotUpload() = runTest(dispatcher) {
        var uploads = 0
        val lost = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload" && uploads++ == 0) lost.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            val key = vm.opened.value!!.draftKey
            vm.addAttachments(listOf(picked("remove")), key); vm.removeAttachment("remove"); vm.send()
            assertTrue(socket.sent("groups.attachment.upload").isEmpty())
            vm.addAttachments(listOf(picked("report")), key); vm.send()
            advanceTimeBy(60_001); runCurrent()
            assertTrue(socket.sent("groups.send").isEmpty())
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(socket.sent("groups.attachment.upload")[0], socket.sent("groups.attachment.upload")[1])
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun gatewayDriverStoppingRevokesAttachmentSelection() = runTest(dispatcher) {
        var running = true
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.capabilities" && !running) """{"driver":false}""" else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            running = false; vm.setVisible(false); vm.setVisible(true)
            vm.state.first { !it.available }
            vm.addAttachments(listOf(picked("stale")), vm.opened.value!!.draftKey)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun fileOnlySendUsesRoomUploadAndRecordsAttachmentHistory() = runTest(dispatcher) {
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p -> attachmentReply(m, p) ?: "{}" }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey)
            vm.send()
            val open = vm.opened.first { it?.lines?.isNotEmpty() == true }!!
            assertEquals("report.csv", (open.lines.single() as RoomLine.Message).attachments.single().name)
            assertEquals(1, socket.sent("groups.attachment.upload").size)
            assertEquals(1, socket.sent("groups.send").size)
            assertTrue(open.attachments.isEmpty())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun partialUploadFailureReusesReceiptsAndStableIdsBeforePostingOnce() = runTest(dispatcher) {
        var failed = false
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload" && p["name"]!!.jsonPrimitive.content == "second.csv" && !failed) {
                failed = true; "!Upload failed"
            } else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("first"), picked("second")), vm.opened.value!!.draftKey)
            vm.send(); vm.opened.first { it?.notice != null }
            assertEquals(0, socket.sent("groups.send").size)
            assertEquals(2, vm.opened.value!!.attachments.size)
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            val uploads = socket.sent("groups.attachment.upload")
            assertEquals(listOf("first.csv", "second.csv", "second.csv"), uploads.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(uploads[1]["upload_id"], uploads[2]["upload_id"])
            assertEquals(1, socket.sent("groups.send").size)
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun sendRetryKeepsManifestAndDoesNotUploadAgain() = runTest(dispatcher) {
        var sends = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send" && sends++ == 0) "!Send failed" else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey)
            vm.send(); vm.opened.first { it?.notice != null }
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(socket.sent("groups.send")[0], socket.sent("groups.send")[1])
            assertEquals(1, socket.sent("groups.attachment.upload").size)
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun oldGatewaysAndOversizeSelectionsNeverUpload() = runTest(dispatcher) {
        var supported = false
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.capabilities" && !supported) """{"driver":true}""" else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("old")), vm.opened.value!!.draftKey)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            supported = true; vm.setVisible(false); vm.setVisible(true)
            vm.state.first { it.attachmentsSupported }
            vm.addAttachments(List(9) { picked("p$it") }, vm.opened.value!!.draftKey)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            vm.addAttachments(listOf(picked("large", 15_000_001)), vm.opened.value!!.draftKey)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            vm.addAttachments(listOf(picked("a", 13_000_000), picked("b", 13_000_000)), vm.opened.value!!.draftKey)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            vm.send(); assertTrue(socket.sent("groups.attachment.upload").isEmpty())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun latePickerAndUploadCannotPostIntoAnotherOpening() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload") held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            val original = vm.opened.value!!.draftKey
            vm.addAttachments(listOf(picked("report")), original); vm.send()
            vm.open(roomB); vm.open(roomA)
            vm.addAttachments(listOf(picked("late")), original)
            held.complete(Unit); runCurrent()
            // The unsent selection comes back as a draft; the late pick from the old opening doesn't.
            assertEquals(listOf("report"), vm.opened.value!!.attachments.map { it.id })
            assertFalse(vm.opened.value!!.attachmentRetry)
            assertTrue(socket.sent("groups.send").isEmpty())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun leavingMidUploadKeepsTheSelectionAndItsUploadsForTheNextOpening() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var heldOnce = false
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload" && p["name"]!!.jsonPrimitive.content == "second.csv" && !heldOnce) {
                heldOnce = true; held.await()
            }
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.composer.setTextAndPlaceCursorAtEnd("both files")
            vm.addAttachments(listOf(picked("first"), picked("second")), vm.opened.value!!.draftKey); vm.send()
            vm.open(roomB); vm.open(roomA)
            assertEquals(listOf("first", "second"), vm.opened.value!!.attachments.map { it.id })
            assertFalse(vm.opened.value!!.attachmentRetry)
            assertEquals("both files", vm.composer.text.toString())
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            val uploads = socket.sent("groups.attachment.upload")
            assertEquals(listOf("first.csv", "second.csv", "second.csv"), uploads.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(uploads[1]["upload_id"], uploads[2]["upload_id"])
            assertEquals(1, socket.sent("groups.send").size)
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun typingAfterSendThenLeavingStillKeepsTheSentFiles() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload") held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.composer.setTextAndPlaceCursorAtEnd("first")
            vm.addAttachments(listOf(picked("photo")), vm.opened.value!!.draftKey); vm.send()
            vm.composer.setTextAndPlaceCursorAtEnd("second thought")
            vm.open(roomB); vm.open(roomA)
            assertEquals("photo", vm.opened.value!!.attachments.single().id)
            assertEquals("first", vm.composer.text.toString())
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun leavingWhileSendIsOnItsWayLocksTheReopenedRoomToTheSameEvent() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var sends = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send" && sends++ == 0) held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.composer.setTextAndPlaceCursorAtEnd("first")
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send(); runCurrent()
            vm.open(roomB); vm.open(roomA)
            assertTrue(vm.opened.value!!.attachmentRetry)
            assertEquals("first", vm.opened.value!!.retryText)
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(socket.sent("groups.send")[0], socket.sent("groups.send")[1])
            assertEquals(1, socket.sent("groups.attachment.upload").size)
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun aRefusalAfterAnUnknownSendDoesNotReuseItsUploadsForANewMessage() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var sends = 0
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            when {
                m == "groups.send" && sends == 0 -> { sends++; held.await(); attachmentReply(m, p)!! }
                m == "groups.send" && sends == 1 -> { sends++; "!Busy" }
                else -> attachmentReply(m, p) ?: "{}"
            }
        }
        val vm = attachmentRoom(socket)
        try {
            vm.composer.setTextAndPlaceCursorAtEnd("first")
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            advanceTimeBy(60_001); runCurrent()
            vm.send(); runCurrent()
            assertFalse(vm.opened.value!!.attachmentRetry)
            vm.composer.setTextAndPlaceCursorAtEnd("edited")
            vm.send(); runCurrent()
            assertEquals(2, socket.sent("groups.send").size)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            assertTrue(vm.opened.value!!.notice!!.contains("report.csv"))
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun aClearedDraftIsNotRestoredOnTheNextOpening() = runTest(dispatcher) {
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload") "!Upload failed" else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey)
            vm.send(); vm.opened.first { it?.notice != null }
            vm.removeAttachment("report")
            vm.open(roomB); vm.open(roomA)
            assertTrue(vm.opened.value!!.attachments.isEmpty())
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun aChangedSelectionAfterAFailedUploadKeepsTheFilesAlreadyUploaded() = runTest(dispatcher) {
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.attachment.upload" && p["name"]!!.jsonPrimitive.content == "bad.csv") "!Upload failed"
            else attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("good"), picked("bad")), vm.opened.value!!.draftKey)
            vm.send(); vm.opened.first { it?.notice != null }
            vm.removeAttachment("bad")
            vm.send(); vm.opened.first { it?.lines?.isNotEmpty() == true }
            assertEquals(listOf("good.csv", "bad.csv"), socket.sent("groups.attachment.upload").map { it["name"]!!.jsonPrimitive.content })
            val manifest = socket.sent("groups.send").single()["payload"]!!.jsonObject["attachments"].toString()
            assertTrue("good.csv" in manifest)
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun aFileOnlySendTheLogKeptWithoutTextStillCountsAsLanded() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var posted: JsonObject? = null
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            when {
                m == "groups.send" -> { posted = p["payload"]!!.jsonObject; held.await(); attachmentReply(m, p)!! }
                m == "groups.state" && posted != null -> """{"room":{"room_id":"a","name":"A","latest_seq":1}}"""
                // The gateway's copy has no text and a normalized mime: only the ids are the same.
                m == "groups.log" && posted != null -> {
                    val id = posted!!["attachments"]!!.toString().substringAfter("\"attachment_id\":\"").substringBefore('"')
                    """{"events":[{"room_id":"a","seq":1,"event_id":"user:x","kind":"message.user","actor":{"kind":"user","id":"u"},"payload":{"thread_id":"main","attachments":[{"attachment_id":"$id","kind":"file","name":"report.csv","mime":"text/csv; charset=utf-8","size":3}]}}],"cursor":1,"latest_seq":1}"""
                }
                else -> attachmentReply(m, p) ?: "{}"
            }
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            advanceTimeBy(60_001); runCurrent()
            assertTrue(vm.opened.value!!.attachments.isEmpty())
            assertFalse(vm.opened.value!!.attachmentRetry)
            assertEquals("report.csv", (vm.opened.value!!.lines.single() as RoomLine.Message).attachments.single().name)
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
    }

    @Test
    fun aTextSendAnsweredAfterReopeningShowsInTheNewOpening() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send") held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.composer.setTextAndPlaceCursorAtEnd("hi"); vm.send()
            vm.open(roomB); vm.open(roomA); vm.opened.first { it?.loading == false }
            held.complete(Unit); runCurrent()
            assertEquals("hi", (vm.opened.value!!.lines.single() as RoomLine.Message).text)
        } finally { vm.close(); vm.setVisible(false) }
    }

    @Test
    fun anUnknownFileSendKeepsOnlyReceiptsNotTheBytes() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { m, p ->
            if (m == "groups.send") held.await()
            attachmentReply(m, p) ?: "{}"
        }
        val vm = attachmentRoom(socket)
        try {
            vm.addAttachments(listOf(picked("report")), vm.opened.value!!.draftKey); vm.send()
            advanceTimeBy(60_001); runCurrent()
            assertTrue(vm.opened.value!!.attachmentRetry)
            assertTrue(vm.opened.value!!.attachments.single().bytes.isEmpty())
            assertEquals("report", vm.opened.value!!.attachments.single().id)
        } finally { vm.close(); vm.setVisible(false); held.complete(Unit) }
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

    /**
     * A room of six member lines, read two at a time ([perPage] at most per page, as a gateway near its byte
     * cap serves), and [stuckBelow] as a seq it can't read past (a page that comes back empty).
     */
    private fun sixLineRoom(
        perPage: Int = 2,
        stuckBelow: Int? = null,
        /** Below seq 4, only a request for this many events or fewer gets an answer (a gateway at its byte cap). */
        servesBelowFourAtMost: Int? = null,
        /** Holds the answer for a read below seq 4 until completed. */
        gate: CompletableDeferred<Unit>? = null,
    ) = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
        fun event(seq: Int) =
            """{"room_id":"a","seq":$seq,"event_id":"e:$seq","kind":"message.member","actor":{"kind":"member","id":"ops"},"payload":{"text":"line $seq","member_id":"ops"}}"""
        when (method) {
            "groups.capabilities" -> """{"protocol_version":2,"driver":true,"max_log_limit":2}"""
            "groups.list" -> """{"rooms":[]}"""
            "groups.state" -> """{"room":{"room_id":"a","name":"Room A","members":[],"latest_seq":6}}"""
            "groups.log" -> {
                val since = params["since_seq"]!!.jsonPrimitive.int
                val limit = params["limit"]!!.jsonPrimitive.int
                if (since < 4) gate?.await()
                val capped = servesBelowFourAtMost != null && since < 4 && limit > servesBelowFourAtMost
                val seqs = if ((stuckBelow != null && since < stuckBelow) || capped) emptyList() else (since + 1..6).take(minOf(limit, perPage))
                val cursor = seqs.lastOrNull() ?: since
                """{"events":[${seqs.joinToString(",") { event(it) }}],"cursor":$cursor,"latest_seq":6,"has_more":${cursor < 6}}"""
            }
            else -> "{}"
        }
    }

    private suspend fun RoomsViewModel.openSixLineRoom() {
        bind(gateway.gatewayUrl)
        setVisible(true)
        state.first { it.available }
        setVisible(false)
        open(roomA.copy(latestSeq = 6))
        opened.first { it?.loading == false }
    }

    private fun RoomsViewModel.shownTexts() = opened.value?.lines.orEmpty().map { (it as RoomLine.Message).text }

    @Test
    fun scrollingUpReadsTheRoomBackToItsFirstLine() = runTest(dispatcher) {
        val vm = viewModel(sixLineRoom())
        try {
            vm.openSixLineRoom()
            assertEquals(listOf("line 5", "line 6"), vm.shownTexts())
            assertTrue(vm.opened.value!!.canLoadEarlier)

            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.lines.size == 4 }
            assertEquals(listOf("line 3", "line 4", "line 5", "line 6"), vm.shownTexts())

            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.lines.size == 6 }
            assertEquals((1..6).map { "line $it" }, vm.shownTexts())
            // The first line is in: nothing further back to read.
            assertFalse(vm.opened.value!!.canLoadEarlier)
        } finally {
            vm.close()
        }
    }

    @Test
    fun aWindowServedInSmallPagesIsReadWhole() = runTest(dispatcher) {
        val socket = sixLineRoom(perPage = 1)
        val vm = viewModel(socket)
        try {
            vm.openSixLineRoom()
            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.lines.size == 4 }
            assertEquals(listOf("line 3", "line 4", "line 5", "line 6"), vm.shownTexts())
            // Each page asks only for what's left of the window, never past what's shown.
            val earlier = socket.sent("groups.log").filter { it["since_seq"]!!.jsonPrimitive.int < 4 }
            assertEquals(listOf(2 to 2, 3 to 1), earlier.map { it["since_seq"]!!.jsonPrimitive.int to it["limit"]!!.jsonPrimitive.int })
        } finally {
            vm.close()
        }
    }

    @Test
    fun aWindowTooBigToComeWholeIsTriedSmallerAfterThePoll() = runTest(dispatcher) {
        val vm = viewModel(sixLineRoom(servesBelowFourAtMost = 1))
        try {
            vm.openSixLineRoom()
            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.canLoadEarlier == false }
            assertEquals(listOf("line 5", "line 6"), vm.shownTexts())

            // The room's next poll allows it again, and the next window is half the size: it comes whole.
            vm.opened.first { it?.canLoadEarlier == true }
            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.lines.size == 3 }
            assertEquals(listOf("line 4", "line 5", "line 6"), vm.shownTexts())
        } finally {
            vm.close()
        }
    }

    @Test
    fun anOlderReadFromBeforeTheRoomWasReopenedStaysOut() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(sixLineRoom(gate = gate))
        try {
            vm.openSixLineRoom()
            vm.loadEarlier()
            // The older read waits on the gateway while the user leaves and comes back.
            vm.close()
            vm.open(roomA.copy(latestSeq = 6))
            gate.complete(Unit)
            vm.opened.first { it?.loading == false }

            assertEquals(listOf("line 5", "line 6"), vm.shownTexts())
            // The new opening still reads back from its own start.
            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.lines.size == 4 }
            assertEquals(listOf("line 3", "line 4", "line 5", "line 6"), vm.shownTexts())
        } finally {
            vm.close()
        }
    }

    @Test
    fun aWindowThatDoesntAllComeLeavesNoGapAndDoesntRetryAtOnce() = runTest(dispatcher) {
        val socket = sixLineRoom(stuckBelow = 4)
        val vm = viewModel(socket)
        try {
            vm.openSixLineRoom()
            vm.loadEarlier()
            vm.opened.first { it?.loadingEarlier == false && it.canLoadEarlier == false }
            assertEquals(listOf("line 5", "line 6"), vm.shownTexts())
            // Paused until the room's next poll, so a reader parked at the top doesn't loop.
            val asked = socket.sent("groups.log").size
            vm.loadEarlier()
            assertEquals(asked, socket.sent("groups.log").size)
        } finally {
            vm.close()
        }
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
    fun aRoomThatMovesOnIsUnreadUntilItsOpened() = runTest(dispatcher) {
        var latest = 2
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":2,"driver":true}"""
                "groups.list" -> """{"rooms":[{"room_id":"a","name":"Room A","members":[],"latest_seq":$latest}]}"""
                "groups.state" -> """{"room":{"room_id":"a","name":"Room A","members":[],"latest_seq":$latest}}"""
                "groups.log" -> {
                    // A bot's line at the newest seq: something to read.
                    val since = params["since_seq"]!!.jsonPrimitive.int
                    val line = """{"room_id":"a","seq":$latest,"event_id":"e:$latest","kind":"message.member","actor":{"kind":"member","id":"ops"},"payload":{"member_id":"ops","text":"hi"}}"""
                    """{"events":[${if (since < latest) line else ""}],"cursor":$latest,"latest_seq":$latest,"has_more":false}"""
                }
                else -> quietRoom(method, params) ?: "{}"
            }
        }
        val vm = viewModel(socket)
        try {
            vm.bind(gateway.gatewayUrl)
            vm.setVisible(true)
            // First met: read so far, not unread.
            assertTrue(vm.state.first { it.available }.unread.isEmpty())
            vm.setVisible(false)

            latest = 5
            vm.setVisible(true)
            assertEquals(setOf("a"), vm.state.first { it.unread.isNotEmpty() }.unread)
            vm.setVisible(false)

            vm.open(roomA)
            vm.opened.first { it?.loading == false }
            assertTrue(vm.state.first { it.unread.isEmpty() }.unread.isEmpty())
        } finally {
            vm.close()
        }
    }

    @Test
    fun onlyABotsLineMakesARoomUnreadNotItsBookkeeping() = runTest(dispatcher) {
        var latest = 2
        fun event(seq: Int, kind: String, payload: String) =
            """{"room_id":"a","seq":$seq,"event_id":"e:$seq","kind":"$kind","actor":{"kind":"gateway","id":"gw"},"payload":$payload}"""
        val log = mapOf(
            3 to event(3, "room.renamed", """{"name":"Room A"}"""),
            4 to event(4, "message.member", """{"member_id":"ops","text":"hi"}"""),
        )
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":2,"driver":true}"""
                "groups.list" -> """{"rooms":[{"room_id":"a","name":"Room A","members":[],"latest_seq":$latest}]}"""
                "groups.log" -> {
                    val since = params["since_seq"]!!.jsonPrimitive.int
                    val events = (since + 1..latest).mapNotNull { log[it] }
                    """{"events":[${events.joinToString(",")}],"cursor":$latest,"latest_seq":$latest,"has_more":false}"""
                }
                else -> "{}"
            }
        }
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        suspend fun readList() {
            vm.setVisible(true)
            vm.state.first { it.available && it.rooms.single().latestSeq == latest }
            vm.setVisible(false)
        }
        readList()

        // A rename (the user's own, or from elsewhere) moves the seq on, but there's nothing to read.
        latest = 3
        readList()
        assertTrue(vm.state.value.unread.isEmpty())

        latest = 4
        readList()
        assertEquals(setOf("a"), vm.state.first { it.unread.isNotEmpty() }.unread)
    }

    @Test
    fun aRoomOpenedFromANotificationAtLaunchStaysOpen() = runTest(dispatcher) {
        val socket = RoomsGateway(CoroutineScope(dispatcher)) { method, params -> quietRoom(method, params) ?: "{}" }
        val vm = viewModel(socket)
        try {
            // The tap opens the room before the sidebar binds the gateway: the first bind isn't a switch.
            vm.openById("a", "Room A")
            vm.bind(gateway.gatewayUrl)
            assertEquals("a", vm.opened.value?.room?.roomId)
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
