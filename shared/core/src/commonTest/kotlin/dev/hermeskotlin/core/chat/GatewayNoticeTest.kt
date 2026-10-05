package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayNoticeTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private fun ChatState.apply(vararg events: GatewayEvent) = events.fold(this) { state, e -> state.reduce(e) }

    // What credits_tracker sends when a top-up runs out.
    private val depleted = event(
        "notification.show",
        """{"text":"✕ Credit access paused · run /topup to top up","level":"error","kind":"sticky","ttl_ms":null,"key":"credits.depleted","id":"credits.depleted"}""",
    )

    @Test
    fun aNoticeShowsWithoutItsLeadingMark() {
        val notice = ChatState().apply(depleted).notices.single()
        assertEquals("credits.depleted", notice.key)
        assertEquals("Credit access paused · run /topup to top up", notice.text)
        assertEquals(GatewayNotice.Level.Error, notice.level)
        assertEquals(GatewayNotice.Kind.Sticky, notice.kind)
    }

    @Test
    fun theSameKeyReplacesInPlaceAndClearRemovesIt() {
        val other = event("notification.show", """{"text":"• Grant spent · ${'$'}5 top-up left","level":"info","kind":"sticky","key":"credits.grant_spent"}""")
        val replaced = event("notification.show", """{"text":"Credit access is back","level":"success","kind":"sticky","key":"credits.depleted"}""")
        val state = ChatState().apply(depleted, other, replaced)
        assertEquals(listOf("credits.depleted", "credits.grant_spent"), state.notices.map { it.key })
        assertEquals("Credit access is back", state.notices.first().text)
        assertEquals(GatewayNotice.Level.Success, state.notices.first().level)

        val cleared = state.apply(event("notification.clear", """{"key":"credits.depleted"}"""))
        assertEquals(listOf("credits.grant_spent"), cleared.notices.map { it.key })
        // A clear for a key that isn't shown changes nothing.
        assertEquals(cleared, cleared.apply(event("notification.clear", """{"key":"nope"}""")))
    }

    @Test
    fun noKeyFallsBackToTheIdAndThenToALocalKey() {
        val byId = ChatState().apply(event("notification.show", """{"text":"a","level":"info","kind":"sticky","id":"n1"}"""))
        assertEquals("n1", byId.notices.single().key)
        val neither = ChatState().apply(
            event("notification.show", """{"text":"a","level":"info","kind":"sticky"}"""),
            event("notification.show", """{"text":"b","level":"info","kind":"sticky"}"""),
        )
        assertEquals(2, neither.notices.map { it.key }.toSet().size)
    }

    @Test
    fun aTimedNoticeKeepsItsTimeAndOneWithoutATimeIsSticky() {
        val timed = ChatState().apply(event("notification.show", """{"text":"Saved","level":"success","kind":"ttl","ttl_ms":4000,"key":"k"}"""))
        assertEquals(GatewayNotice.Kind.Timed, timed.notices.single().kind)
        assertEquals(4000L, timed.notices.single().ttlMillis)
        val untimed = ChatState().apply(event("notification.show", """{"text":"Saved","level":"warn","kind":"ttl","key":"k"}"""))
        assertEquals(GatewayNotice.Kind.Sticky, untimed.notices.single().kind)
        assertEquals(GatewayNotice.Level.Warning, untimed.notices.single().level)
    }

    @Test
    fun theTurnEndingDropsTheSlowStartNoticeButKeepsStickyOnes() {
        val slow = event(
            "notification.show",
            """{"text":"Still starting the agent (tool discovery / model setup)","level":"info","kind":"agent","ttl_ms":null,"key":"agent.build.slow","id":"agent.build.slow"}""",
        )
        val state = ChatState().apply(depleted, slow, event("message.start"), event("message.complete", """{"text":"done"}"""))
        assertEquals(listOf("credits.depleted"), state.notices.map { it.key })
    }

    @Test
    fun anEmptyNoticeIsIgnoredAndDismissRemovesOne() {
        assertTrue(ChatState().apply(event("notification.show", """{"text":"  ","level":"info","kind":"sticky"}""")).notices.isEmpty())
        assertTrue(ChatState().apply(depleted).withoutNotice("credits.depleted").notices.isEmpty())
    }
}
