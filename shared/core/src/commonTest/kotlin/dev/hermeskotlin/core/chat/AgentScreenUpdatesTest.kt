package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.phone.AgentScreens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentScreenUpdatesTest {

    private fun create(id: String) = """{"version":"v0.9","createSurface":{"surfaceId":"$id","catalogId":"basic"}}"""
    private fun components(id: String, text: String) =
        """{"version":"v0.9","updateComponents":{"surfaceId":"$id","components":[{"id":"root","component":"Text","text":"$text"}]}}"""
    private fun delete(id: String) = """{"version":"v0.9","deleteSurface":{"surfaceId":"$id"}}"""

    private fun call(
        id: String,
        vararg messages: String,
        wait: Boolean = true,
        running: Boolean = false,
        failed: Boolean = false,
        output: String? = null,
    ) =
        ToolActivity(
            id = id,
            name = SHOW_UI_TOOL,
            running = running,
            failed = failed,
            output = output,
            agentScreen = AgentScreen(messages.toList(), messages.mapNotNullTo(LinkedHashSet()) { A2uiEnvelope.of(it)?.surfaceId }, wait),
        )

    @Test
    fun aLaterCallThatOnlyUpdatesDrawsOnTheFirstCard() {
        val first = call("a", create("pick"), components("pick", "One"))
        val update = call("b", components("pick", "Two"), wait = false, running = true)
        val views = screenViews(listOf(first, update))
        assertEquals(setOf("a"), views.keys)
        val view = views.getValue("a")
        assertEquals(listOf(create("pick"), components("pick", "One"), components("pick", "Two")), view.screen.messages)
        assertEquals("b", view.latest.id)
        assertEquals(false, view.screen.wait)
    }

    @Test
    fun aCallWithANewSurfaceAndAnUpdateSplitsThem() {
        val first = call("a", create("pick"), components("pick", "One"))
        val both = call("b", components("pick", "Done"), create("next"), components("next", "Next"))
        val views = screenViews(listOf(first, both))
        assertEquals(listOf(create("pick"), components("pick", "One"), components("pick", "Done")), views.getValue("a").screen.messages)
        assertEquals(listOf(create("next"), components("next", "Next")), views.getValue("b").screen.messages)
        assertEquals(setOf("next"), views.getValue("b").screen.surfaceIds)
    }

    @Test
    fun aRefusedUpdateLeavesTheScreenAsItWas() {
        val first = call("a", create("pick"), components("pick", "One"))
        val refused = call("b", components("pick", "Broken"), failed = true, output = """{"error":"${NOT_DRAWN}Unknown component"}""")
        val view = screenViews(listOf(first, refused)).getValue("a")
        assertEquals(2, view.screen.messages.size)
        assertEquals("a", view.latest.id)
    }

    @Test
    fun aScreenThePhoneWouldNotDrawHasNoCard() {
        val refused = call("a", create("pick"), components("pick", "One"), failed = true, output = """{"error":"${NOT_DRAWN}Unknown component"}""")
        assertEquals(emptyMap(), screenViews(listOf(refused)))
    }

    @Test
    fun aScreenThatHoldsItselfHasNoCardAndAnUpdateThatWouldIsLeftOut() {
        val loop = """{"version":"v0.9","updateComponents":{"surfaceId":"pick","components":[{"id":"root","component":"Column","children":["root"]}]}}"""
        assertEquals(emptyMap(), screenViews(listOf(call("a", create("pick"), loop))))
        val first = call("a", create("pick"), components("pick", "One"))
        val view = screenViews(listOf(first, call("b", loop))).getValue("a")
        assertEquals(2, view.screen.messages.size)
        assertEquals("a", view.latest.id)
    }

    @Test
    fun anUpdateThatEndedUnansweredStaysDrawn() {
        val first = call("a", create("pick"), components("pick", "One"))
        val stopped = call("b", components("pick", "Two"), failed = true, output = """{"error":"Stopped before the phone answered."}""")
        val view = screenViews(listOf(first, stopped)).getValue("a")
        assertEquals(3, view.screen.messages.size)
        assertEquals("b", view.latest.id)
    }

    @Test
    fun anUpdateToAScreenNotLoadedHasNoCard() {
        assertEquals(emptyMap(), screenViews(listOf(call("b", components("pick", "Two")))))
    }

    @Test
    fun aCardDrawnAgainDoesNotUndoANewerCall() {
        val screens = AgentScreens()
        val first = call("a", create("pick"), components("pick", "One")).agentScreen!!
        val update = call("b", components("pick", "Two")).agentScreen!!
        screens.record(first, "chat")
        screens.record(update, "chat")
        // The card still shows the screen without the update: the chat hasn't heard of it yet.
        screens.drawn(first, "chat")
        assertEquals(first.messages + update.messages, screens.earlier(update, "chat"))
        // A card drawn after Kyllene started tells the phone what's on screen.
        val fresh = AgentScreens()
        fresh.drawn(AgentScreen(first.messages + update.messages, setOf("pick"), true), "chat")
        assertEquals(first.messages + update.messages, fresh.earlier(update, "chat"))
    }

    @Test
    fun aRunningUpdateWaitsForThePhoneToTakeIt() {
        val screens = AgentScreens()
        val first = call("a", create("pick"), components("pick", "One"))
        val update = call("b", components("pick", "Two"), running = true)
        screens.record(first.agentScreen!!, "chat")
        val took = { screen: AgentScreen -> AgentScreens.took(screen, "chat", screens.got.value) }
        // The phone is still checking it: the card stays as it was, and drawing it tells the phone nothing new.
        val checking = screenViews(listOf(first, update), took).getValue("a")
        assertEquals(2, checking.screen.messages.size)
        assertEquals("a", checking.latest.id)
        screens.drawn(AgentScreen(first.agentScreen!!.messages + update.agentScreen!!.messages, setOf("pick"), true), "chat")
        assertEquals(first.agentScreen!!.messages, screens.earlier(update.agentScreen!!, "chat"))
        // Taken, it's drawn once.
        screens.record(update.agentScreen!!, "chat")
        val taken = screenViews(listOf(first, update), took).getValue("a")
        assertEquals(3, taken.screen.messages.size)
        assertEquals("b", taken.latest.id)
        assertEquals(3, screens.got.value.getValue("chat" to "pick").size)
    }

    @Test
    fun aSurfaceMadeAgainIsANewCard() {
        val first = call("a", create("pick"), components("pick", "One"))
        val again = call("b", create("pick"), components("pick", "Two"))
        val later = call("c", delete("pick"))
        val views = screenViews(listOf(first, again, later))
        assertEquals(2, views.getValue("a").screen.messages.size)
        assertEquals(listOf(create("pick"), components("pick", "Two"), delete("pick")), views.getValue("b").screen.messages)
    }

    @Test
    fun thePhoneChecksAnUpdateOnTopOfWhatTheSurfaceGot() {
        val screens = AgentScreens()
        val first = call("a", create("pick"), components("pick", "One")).agentScreen!!
        val update = call("b", components("pick", "Two")).agentScreen!!
        assertNull(screens.earlier(update, "chat"))
        assertEquals(emptyList(), screens.earlier(first, "chat"))
        screens.record(first, "chat")
        assertEquals(first.messages, screens.earlier(update, "chat"))
        // Another chat's surface of the same name is another screen.
        assertNull(screens.earlier(update, "other"))
        screens.record(update, "chat")
        assertEquals(first.messages + update.messages, screens.earlier(update, "chat"))
        // Recording the card's whole screen again changes nothing: a createSurface starts it over.
        screens.record(AgentScreen(first.messages + update.messages, setOf("pick"), true), "chat")
        assertTrue(screens.earlier(update, "chat")!!.size == 3)
    }
}
