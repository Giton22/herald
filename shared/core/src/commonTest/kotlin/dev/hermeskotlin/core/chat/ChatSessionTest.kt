package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.slash.SlashCommand
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatSessionTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private var history = """{"session_id":"stored-1","messages":[
        {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}]}"""

    private fun client() = createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/sessions/stored-1/messages" -> respond(history, HttpStatusCode.OK, json)
                else -> error("unexpected ${request.url}")
            }
        },
    )

    /** Answers every client request with the canned result for its method (`{}` otherwise). */
    private fun CoroutineScope.serve(transport: FakeTransport, results: Map<String, String>) = launch {
        var answered = 0
        transport.sent.collect { sent ->
            sent.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val canned = results[method] ?: "{}"
                // "error:<code>" answers with a JSON-RPC error instead.
                transport.push(
                    if (canned.startsWith("error:")) {
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":${canned.removePrefix("error:")},"message":"nope"}}"""
                    } else {
                        """{"jsonrpc":"2.0","id":$id,"result":$canned}"""
                    },
                )
            }
        }
    }

    private fun ChatMessage.textOf() = when (this) {
        is ChatMessage.User -> text
        is ChatMessage.Assistant -> text
        is ChatMessage.Command -> output
    }

    private fun JsonObject.isCall(method: String) = this["method"]?.jsonPrimitive?.contentOrNull == method

    private fun JsonObject.param(name: String) = this["params"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull

    private fun event(type: String, sessionId: String, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$sessionId","payload":$payload}}"""

    private fun setup(scope: CoroutineScope, results: Map<String, String>): Pair<GatewayConnection, FakeTransport> {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = client()
        val transport = FakeTransport()
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> transport }, scope)
        scope.serve(transport, results)
        connection.start(url)
        transport.push(FakeTransport.READY)
        return connection to transport
    }

    @Test
    fun resumesStoredSessionAndStreamsItsTurn() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false,"info":{"model":"m1"}}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val resume = transport.awaitSent { it.isCall("session.resume") }
        assertEquals("stored-1", resume.param("session_id"))
        assertEquals("true", resume.param("omit_messages"))
        assertEquals("desktop", resume.param("source"))
        val attached = chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        assertEquals(2, attached.messages.size)
        assertEquals("m1", attached.model)

        transport.push(event("message.delta", "someone-else", """{"text":"not ours"}"""))
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Streaming"}"""))
        // No prompt of ours started this turn, so the stored rows replace the live copy when it ends.
        history = """{"session_id":"stored-1","messages":[
            {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"},
            {"id":3,"role":"user","content":"go on"},{"id":4,"role":"assistant","content":"Streaming"}]}"""
        transport.push(event("message.complete", "rt1", """{"text":"Streaming","status":"complete"}"""))

        val done = chat.state.first { s -> !s.running && (s.messages.last() as? ChatMessage.Assistant)?.key == "row-4" }
        assertEquals("Streaming", (done.messages.last() as ChatMessage.Assistant).text)
    }

    @Test
    fun aTurnStartedByAnotherClientPullsItsPromptFromTheTranscript() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        // Desktop sends "nice": the gateway stores it, but only the reply streams to us.
        history = """{"session_id":"stored-1","messages":[
            {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"},
            {"id":3,"role":"user","content":"nice"}]}"""
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Glad"}"""))

        val live = chat.state.first { s -> s.messages.any { it is ChatMessage.User && it.text == "nice" } }
        val reply = assertIs<ChatMessage.Assistant>(live.messages.last())
        assertTrue(reply.streaming)

        history = history.replace("""{"id":3,"role":"user","content":"nice"}]""", """{"id":3,"role":"user","content":"nice"},{"id":4,"role":"assistant","content":"Glad you like it"}]""")
        transport.push(event("message.complete", "rt1", """{"text":"Glad you like it","status":"complete"}"""))

        val done = chat.state.first { s -> !s.running && (s.messages.last() as? ChatMessage.Assistant)?.key == "row-4" }
        assertEquals(listOf("hello", "Hi! What next?", "nice", "Glad you like it"), done.messages.map { it.textOf() })
    }

    @Test
    fun firstSendOnANewChatCreatesTheSessionThenSubmits() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertTrue(chat.send("  write a haiku "))

        val submit = transport.awaitSent { it.isCall("prompt.submit") }
        assertEquals("rt9", submit.param("session_id"))
        assertEquals("write a haiku", submit.param("text"))
        val state = chat.state.value
        assertEquals("stored-9", state.storedSessionId)
        assertTrue(state.running)
        val user = assertIs<ChatMessage.User>(state.messages.single())
        assertFalse(user.pending)
    }

    @Test
    fun sendWhileOfflineReportsAndLeavesNoBubble() = runTest {
        val connection = GatewayConnection(
            AuthApi(client(), PersistentCookiesStorage(InMemoryKeyValueStore())),
            { _, _ -> error("offline") },
            backgroundScope,
        )
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)

        assertFalse(chat.send("hello?"))

        assertTrue(chat.state.value.messages.isEmpty())
        assertTrue(chat.state.value.error != null)
    }

    @Test
    fun approvalRequestIsShownAnsweredAndWithdrawn() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":true}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }

        transport.push("""{"jsonrpc":"2.0","id":"srq-other","method":"approval","params":{"session_id":"rt9","command":"ls"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","command":"rm -rf build","choices":["once","session","deny"]}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-2","method":"sudo","params":{"session_id":"rt1","command":"apt install jq"}}""")

        val asked = chat.state.first { it.inputRequests.size == 2 }
        val approval = assertIs<InputRequest.Approval>(asked.inputRequests.first())
        assertEquals("srq-1", approval.id)

        assertTrue(chat.answer(approval, InputAnswers.approval(ApprovalChoice.Session)))
        val reply = transport.awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-1" }
        assertEquals("session", reply["result"]!!.jsonObject["choice"]!!.jsonPrimitive.contentOrNull)

        // Desktop typed the password first: the gateway withdraws the request everywhere.
        transport.push(event("request.cancel", "rt1", """{"id":"srq-2","method":"sudo","reason":"resolved"}"""))
        chat.state.first { it.inputRequests.isEmpty() }
    }

    @Test
    fun resumeRestoresQuestionsAskedWhileAway() = runTest {
        val (connection, _) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":true,"open_requests":[{"id":"srq-7","method":"clarify","params":{"session_id":"rt1","question":"Which branch?","choices":["main","dev"]}},{"id":"srq-8","method":"terminal.read","params":{"session_id":"rt1"}}]}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val attached = chat.state.first { it.runtimeSessionId == "rt1" }
        val clarify = assertIs<InputRequest.Clarify>(attached.inputRequests.single())
        assertEquals("Which branch?", clarify.questions.single().question)
    }

    @Test
    fun hostReusesTheOpenStoredChatAndReplacesEverythingElse() = runTest {
        val (connection, _) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val host = ChatHost(connection, SessionsApi(client()), backgroundScope)

        val first = host.open(url, "stored-1", "Greeting")
        assertTrue(first === host.open(url, "stored-1", null))
        assertTrue(first === host.session.value)

        val fresh = host.open(url, null, null)
        assertFalse(fresh === first)
        assertFalse(fresh === host.open(url, null, null))

        host.close()
        assertEquals(null, host.session.value)
    }

    @Test
    fun picksOnANewChatGoIntoSessionCreate() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","info":{"model":"m2","provider":"p2","reasoning_effort":"high","fast":true}}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertEquals(ModelSwitch.Done, chat.setModel("m2", "p2"))
        chat.setReasoningEffort("high")
        chat.setFast(true)
        assertTrue(chat.send("hi"))

        val create = transport.awaitSent { it.isCall("session.create") }
        assertEquals("desktop", create.param("source"))
        assertEquals("m2", create.param("model"))
        assertEquals("p2", create.param("provider"))
        assertEquals("high", create.param("reasoning_effort"))
        assertEquals("true", create.param("fast"))
        assertTrue(transport.sent.value.none { it.isCall("config.set") })
        val state = chat.state.value
        assertEquals("high", state.reasoningEffort)
        assertEquals(true, state.fast)
    }

    @Test
    fun aLiveChatSwitchesOverConfigSetAndRollsBackWhenAskedToConfirm() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false,"info":{"model":"m1","provider":"p1","reasoning_effort":""}}""",
                "config.set" to """{"key":"model","confirm_required":true,"confirm_message":"Costs a lot"}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        val attached = chat.state.first { it.runtimeSessionId == "rt1" }
        assertEquals("p1", attached.provider)
        assertEquals(null, attached.reasoningEffort)

        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p1"))
        val set = transport.awaitSent { it.isCall("config.set") }
        assertEquals("rt1", set.param("session_id"))
        assertEquals("big --provider p1 --session", set.param("value"))
        assertEquals("m1", chat.state.value.model)

        chat.setReasoningEffort("low")
        transport.awaitSent { it.isCall("config.set") && it.param("key") == "reasoning" && it.param("value") == "low" }
        transport.push(event("session.info", "rt1", """{"model":"m1","reasoning_effort":"low","fast":false}"""))
        chat.state.first { it.reasoningEffort == "low" && it.fast == false }
    }

    @Test
    fun attachmentsAreUploadedBeforeThePromptAndFilesBecomeReferences() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","info":{}}""",
                "image.attach_bytes" to """{"attached":true,"path":"/tmp/upload_1.jpg"}""",
                "pdf.attach" to "error:5028",
                "file.attach" to """{"attached":true,"name":"x","path":"p","ref_path":"r","ref_text":"@file:attachments/notes.txt","uploaded":true}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val image = OutgoingAttachment("a1", "cat.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val pdf = OutgoingAttachment("a2", "spec.pdf", "application/pdf", byteArrayOf(4))
        val file = OutgoingAttachment("a3", "notes.txt", "text/plain", "hi".encodeToByteArray())
        assertTrue(chat.send("  summarise  ", listOf(image, pdf, file)))

        val sent = transport.sent.value.mapNotNull { it["method"]?.jsonPrimitive?.contentOrNull }.filter { it != "client.capabilities" && it != "ping" }
        assertEquals(listOf("session.create", "image.attach_bytes", "pdf.attach", "file.attach", "file.attach", "prompt.submit"), sent)
        val upload = transport.sent.value.first { it.isCall("image.attach_bytes") }
        assertEquals("AQID", upload.param("content_base64"))
        assertEquals("rt9", upload.param("session_id"))
        // The PDF fell back to a plain file once the gateway said it can't render pages.
        assertEquals("data:application/pdf;base64,BA==", transport.sent.value.first { it.isCall("file.attach") }.param("data_url"))
        val submit = transport.sent.value.first { it.isCall("prompt.submit") }
        assertEquals("@file:attachments/notes.txt\n\n@file:attachments/notes.txt\n\nsummarise", submit.param("text"))
        val user = assertIs<ChatMessage.User>(chat.state.value.messages.single())
        assertEquals(listOf("cat.jpg", "spec.pdf", "notes.txt"), user.attachments.map { it.name })
    }

    @Test
    fun aFailedSendTakesBackTheImagesItQueued() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","info":{}}""",
                "image.attach_bytes" to """{"attached":true,"path":"/tmp/upload_1.jpg"}""",
                "prompt.submit" to "error:5000",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertFalse(chat.send("", listOf(OutgoingAttachment("a1", "cat.jpg", "image/jpeg", byteArrayOf(1)))))

        val detach = transport.awaitSent { it.isCall("image.detach") }
        assertEquals("/tmp/upload_1.jpg", detach.param("path"))
        assertTrue(chat.state.value.messages.isEmpty())
    }

    private suspend fun newChat(scope: CoroutineScope, results: Map<String, String>): Pair<ChatSession, FakeTransport> {
        val (connection, transport) = setup(scope, mapOf("session.create" to """{"session_id":"rt9","info":{}}""") + results)
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), scope)
        chat.start()
        return chat to transport
    }

    @Test
    fun aCommandShowsWhatTheSlashWorkerPrinted() = runTest {
        val (chat, transport) = newChat(backgroundScope, mapOf("slash.exec" to """{"output":"Context: 12% used\n"}"""))

        assertEquals(null, chat.runCommand(SlashCommand("context", "")))

        assertEquals("context", transport.sent.value.first { it.isCall("slash.exec") }.param("command"))
        val shown = assertIs<ChatMessage.Command>(chat.state.value.messages.single())
        assertEquals("/context", shown.command)
        assertEquals("Context: 12% used", shown.output)
        assertFalse(shown.running)
        // Output alone doesn't make a conversation worth reopening.
        assertFalse(chat.state.value.hasConversation)
    }

    @Test
    fun aSkillTheWorkerRefusesIsDispatchedAndSentShowingTheInvocation() = runTest {
        val (chat, transport) = newChat(
            backgroundScope,
            mapOf(
                "slash.exec" to "error:4018",
                "command.dispatch" to """{"type":"skill","name":"work","message":"[IMPORTANT: skill body]","display":"/work fix the leak"}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )

        chat.runCommand(SlashCommand("work", "fix the leak"))

        val dispatch = transport.sent.value.first { it.isCall("command.dispatch") }
        assertEquals("work", dispatch.param("name"))
        assertEquals("fix the leak", dispatch.param("arg"))
        assertEquals("[IMPORTANT: skill body]", transport.sent.value.first { it.isCall("prompt.submit") }.param("text"))
        val user = assertIs<ChatMessage.User>(chat.state.value.messages.single())
        assertEquals("/work fix the leak", user.text)
    }

    @Test
    fun aPrefillHandsTheTextBackAndAFailureExplainsItself() = runTest {
        val (chat, _) = newChat(backgroundScope, mapOf("slash.exec" to """{"type":"prefill","message":"my last prompt"}"""))
        assertEquals("my last prompt", chat.runCommand(SlashCommand("undo", "")))
        assertTrue(chat.state.value.messages.isEmpty())

        val (failing, _) = newChat(backgroundScope, mapOf("slash.exec" to "error:5030", "command.dispatch" to "error:4018"))
        failing.runCommand(SlashCommand("usage", ""))
        val shown = assertIs<ChatMessage.Command>(failing.state.value.messages.single())
        assertTrue(shown.failed)
    }
}
