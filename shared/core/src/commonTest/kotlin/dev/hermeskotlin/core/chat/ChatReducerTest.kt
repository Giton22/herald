package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatReducerTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private fun ChatState.apply(vararg events: GatewayEvent) = events.fold(this) { state, e -> state.reduce(e) }

    @Test
    fun theNextTurnReleasesTheOldestQueuedPrompt() {
        val running = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User("u1", "write it"),
                ChatMessage.Assistant("a1", "Writing", streaming = true),
                ChatMessage.User("q1", "then test", queued = true),
                ChatMessage.User("q2", "then ship", queued = true),
            ),
        )
        // A start while the reply streams belongs to that turn; the queue keeps waiting.
        val still = running.apply(event("message.start"))
        assertEquals(listOf(true, true), still.messages.filterIsInstance<ChatMessage.User>().drop(1).map { it.queued })

        val next = still.apply(event("message.complete", """{"text":"Written","status":"complete"}"""), event("message.start"))
        assertEquals(listOf(false, true), next.messages.filterIsInstance<ChatMessage.User>().drop(1).map { it.queued })
        assertTrue(assertIs<ChatMessage.Assistant>(next.messages.last()).streaming)
    }

    @Test
    fun aReplyIsDatedWhenItsTurnEnds() {
        val streaming = ChatState(messages = listOf(ChatMessage.User("u", "hi", timestamp = 100.0)))
            .reduce(event("message.start"), now = 101.0)
            .reduce(event("message.delta", """{"text":"Hello"}"""), now = 102.0)
        // No time while it streams: it isn't finished yet.
        assertEquals(null, assertIs<ChatMessage.Assistant>(streaming.messages.last()).timestamp)

        val done = streaming.reduce(event("message.complete", """{"text":"Hello","status":"complete"}"""), now = 105.0)
        assertEquals(105.0, assertIs<ChatMessage.Assistant>(done.messages.last()).timestamp)
        assertEquals(100.0, assertIs<ChatMessage.User>(done.messages.first()).timestamp)
    }

    @Test
    fun streamedTurnBuildsOneReplyWithTools() {
        val state = ChatState(messages = listOf(ChatMessage.User("u", "list files"))).apply(
            event("message.start"),
            event("reasoning.delta", """{"text":"Let me look."}"""),
            event("tool.start", """{"tool_id":"t1","name":"terminal","context":"ls -la"}"""),
            event("status.update", """{"kind":"status","text":"running ls"}"""),
            event("tool.complete", """{"tool_id":"t1","name":"terminal","summary":"3 files","duration_s":0.4}"""),
            event("message.delta", """{"text":"There are "}"""),
            event("message.delta", """{"text":"3 files."}"""),
        )
        assertTrue(state.running)
        assertEquals("running ls", state.status)

        val done = state.reduce(event("message.complete", """{"text":"There are 3 files.","status":"complete"}"""))

        val reply = assertIs<ChatMessage.Assistant>(done.messages.last())
        assertEquals("There are 3 files.", reply.text)
        assertEquals("Let me look.", reply.reasoning)
        assertEquals(ToolActivity("t1", "terminal", "ls -la", running = false, summary = "3 files", durationSeconds = 0.4), reply.tools.single())
        assertFalse(reply.streaming)
        assertFalse(done.running)
        assertEquals(null, done.status)
    }

    @Test
    fun spinnerFramesStayOutOfTheReasoningButProviderWaitsShow() {
        val state = ChatState().apply(
            event("message.start"),
            event("thinking.delta", """{"text":"(>∀<☆)☆ musing..."}"""),
            event("reasoning.delta", """{"text":"Real thought."}"""),
            event("thinking.delta", """{"text":"⏳ still waiting on the provider (45s)"}"""),
        )
        assertEquals("Real thought.", assertIs<ChatMessage.Assistant>(state.messages.single()).reasoning)
        assertEquals("⏳ still waiting on the provider (45s)", state.status)
        assertEquals("(>∀<☆)☆ musing...", state.thinkingFrame)

        val done = state.reduce(event("message.complete", """{"text":"ok","status":"complete"}"""))
        assertEquals(null, done.thinkingFrame)
    }

    @Test
    fun aPromptQueuedMidTurnDoesNotSplitTheReply() {
        val streaming = ChatState().apply(event("message.start"), event("message.delta", """{"text":"Working"}"""))
        val queued = streaming.copy(messages = streaming.messages + ChatMessage.User("q", "also this", queued = true))

        val state = queued.apply(event("message.delta", """{"text":" on it"}"""), event("message.complete", """{"status":"complete"}"""))

        assertEquals(2, state.messages.size)
        assertEquals("Working on it", (state.messages[0] as ChatMessage.Assistant).text)
        assertIs<ChatMessage.User>(state.messages[1])
    }

    @Test
    fun nonStreamingProviderTakesTheFinalText() {
        val state = ChatState().apply(event("message.start"), event("message.complete", """{"text":"Hi!","status":"complete"}"""))
        assertEquals("Hi!", (state.messages.single() as ChatMessage.Assistant).text)
    }

    @Test
    fun emptyInterruptedTurnLeavesNoBubbleButErrorsDo() {
        val interrupted = ChatState().apply(event("message.start"), event("message.complete", """{"status":"interrupted"}"""))
        assertTrue(interrupted.messages.isEmpty())

        val failed = ChatState().apply(event("message.start"), event("message.complete", """{"status":"error","error":"Rate limited"}"""))
        val reply = assertIs<ChatMessage.Assistant>(failed.messages.single())
        assertEquals(TurnOutcome.Error, reply.outcome)
        assertEquals("Rate limited", reply.error)
    }

    @Test
    fun keysStayUniqueAcrossRemovedReplies() {
        val state = ChatState().apply(
            event("message.start"), event("message.complete", """{"status":"interrupted"}"""),
            event("message.start"), event("message.delta", """{"text":"a"}"""), event("message.complete"),
            event("message.start"), event("message.delta", """{"text":"b"}"""), event("message.complete"),
        )
        assertEquals(state.messages.size, state.messages.map { it.key }.toSet().size)
    }

    @Test
    fun titleAndModelFollowSessionEvents() {
        val state = ChatState().apply(
            event("session.title", """{"session_id":"stored","title":"Fix the build"}"""),
            event("session.info", """{"model":"claude-x","title":""}"""),
        )
        assertEquals("Fix the build", state.title)
        assertEquals("claude-x", state.model)
    }

    @Test
    fun afterACorrectionTheFinalTextDoesNotRepeatWhatWasShown() {
        val shown = ChatMessage.Assistant("a", text = "Hello there", tools = listOf(ToolActivity("t1", "terminal", running = true)))
        val state = ChatState(
            messages = listOf(ChatMessage.User("u", "greet"), shown, ChatMessage.User("c", "in French")),
            running = true,
            correctedReplyKey = "a",
        ).apply(
            event("tool.complete", """{"tool_id":"t1","summary":"ok"}"""),
            // A non-streaming provider: only the final text, which covers the whole turn.
            event("message.complete", """{"text":"Hello there Bonjour","status":"complete"}"""),
        )
        assertEquals(listOf("greet", "Hello there", "in French", "Bonjour"), state.messages.map {
            when (it) {
                is ChatMessage.User -> it.text
                is ChatMessage.Assistant -> it.text
                is ChatMessage.Command -> it.output
                is ChatMessage.Notice -> it.text
            }
        })
        assertEquals("ok", assertIs<ChatMessage.Assistant>(state.messages[1]).tools.single().summary)
        assertEquals(null, state.correctedReplyKey)
    }
}
