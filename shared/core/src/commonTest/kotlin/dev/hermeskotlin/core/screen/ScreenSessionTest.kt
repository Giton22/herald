package dev.hermeskotlin.core.screen

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bot's screen, and the app's hold on it.
 *
 * The phone is on Wi-Fi and the gateway may restart, so the link drops while the screen is being watched.
 * That is not the screen's fault and it is not a reason for the app to fall over. And a screen that could
 * not be opened has to say so, with a way to try again: the ticket the gateway mints is single-use and
 * short-lived, so a viewer that misses it has to ask for another.
 */
class ScreenSessionTest {

    private val url = FakeGateway.URL

    private val status = """{"profile":"scribe","supported":true,"installed":true,"running":true,
        "geometry":"1440x900","lease":{"holder":"agent","epoch":3},"profile_key":"scribe"}"""

    /** A ticket for the display stream, with the viewer id that goes with it. */
    private val observe = """{"ticket":"T","path":"/api/display/ws","viewer_id":"V1","profile":"scribe",
        "supported":true,"installed":true,"running":true,"lease":{"holder":"agent","epoch":3}}"""

    /** The lease, naming this client: the gateway's hash of the viewer id it minted. */
    private val lease = """{"lease":{"holder":"human","viewer_hash":"${viewerHash("V1")}","epoch":4}}"""

    /** The lease after a hand-back: the bot holds it again. */
    private val released = """{"lease":{"holder":"agent","epoch":5}}"""

    /** What the gateway answers each call with; null means it says nothing at all. */
    private inner class Answers {
        var observe: String? = null
        var acquire: String? = null
        var release: String? = null
        var start: String? = null
        /** What the gateway says about the screen; null holds the answer back. */
        var status: String? = this@ScreenSessionTest.status
    }

    /** A gateway that answers the screen's calls; the link never comes back on its own. */
    private suspend fun gateway(scope: CoroutineScope, answers: Answers = Answers()): FakeGateway {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            when (call.method) {
                "display.status" -> answers.status
                "display.observe" -> answers.observe
                "display.lease.acquire" -> answers.acquire
                "display.lease.release" -> answers.release
                "display.start" -> answers.start
                else -> "{}"
            }
        }
        gateway.start()
        return gateway
    }

    /** A gateway that answers as it should, with a ticket to hand out. */
    private suspend fun workingGateway(scope: CoroutineScope): FakeGateway =
        gateway(scope, Answers().also {
            it.observe = observe
            it.acquire = lease
            it.release = released
        })

    private fun session(gateway: FakeGateway, scope: CoroutineScope) =
        ScreenSession("scribe", gateway.connection, url, scope)

    /** Drops the link and waits until the app knows it is gone. */
    private suspend fun drop(gateway: FakeGateway) {
        gateway.socket.serverClose(1006)
        gateway.connection.state.first { it !is ConnectionState.Connected }
    }

    @Test
    fun aDroppedLinkIsNotACrash() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.refresh()
        assertEquals("1440x900", session.state.value.status?.geometry)

        drop(gateway)

        // Asking anyway must not take the app down.
        session.refresh()
        assertNotNull(session.state.value.error)
    }

    @Test
    fun theLastAnswerStandsWhileTheLinkIsDown() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.refresh()

        drop(gateway)
        session.refresh()

        // The screen may well be fine: what the gateway last said still stands.
        assertEquals("1440x900", session.state.value.status?.geometry)
        assertEquals(DisplayLease.AGENT, session.state.value.lease?.holder)
    }

    @Test
    fun aRefusalIsTheGatewaysOwnWords() = runTest {
        val gateway = FakeGateway(backgroundScope).also {
            it.answer = { call -> call.error(5300, "a human holds this screen") }
            it.start()
        }
        val session = session(gateway, backgroundScope)

        session.refresh()

        // The gateway answered and said no: its words are the best explanation.
        assertEquals("a human holds this screen", session.state.value.error)
    }

    @Test
    fun takingOverWithNoLinkIsNotACrash() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        drop(gateway)

        assertFalse(session.takeOver())

        assertNotNull(session.state.value.error)
    }

    @Test
    fun handingBackWithNoLinkIsNotACrash() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        drop(gateway)

        assertFalse(session.handBack())

        assertNotNull(session.state.value.error)
    }

    @Test
    fun theScreenIsOursWhenTheLeaseNamesOurViewer() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)

        session.open()
        runCurrent()
        assertTrue(session.takeOver())

        // The gateway names this client, so the app knows it holds the screen.
        assertTrue(session.state.value.holding)
    }

    @Test
    fun theScreenIsNotOursWhenTheLeaseNamesSomebodyElse() = runTest {
        val answers = Answers().also {
            it.observe = observe
            it.acquire = """{"lease":{"holder":"human","viewer_hash":"0123456789ab","epoch":4}}"""
        }
        val gateway = gateway(backgroundScope, answers)
        val session = session(gateway, backgroundScope)

        session.open()
        runCurrent()
        session.takeOver()

        // Somebody else's lease is not this app's to hold.
        assertFalse(session.state.value.holding)
    }

    @Test
    fun aFailedHandBackKeepsTheLease() = runTest {
        val answers = Answers().also {
            it.observe = observe
            it.acquire = lease
            // The gateway answers, but with no lease: it refuses to let go.
            it.release = "{}"
        }
        val gateway = gateway(backgroundScope, answers)
        val session = session(gateway, backgroundScope)
        session.open()
        runCurrent()
        session.takeOver()
        assertTrue(session.state.value.holding)

        assertFalse(session.handBack())

        // The gateway may still consider this viewer in control: the app says so rather than the other way round.
        assertTrue(session.state.value.holding)
        assertNotNull(session.state.value.error)
    }

    @Test
    fun aViewerThatCouldNotOpenTheScreenOffersAnotherTry() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.open()
        runCurrent()

        session.viewerFailed("The gateway refused the connection.")

        // A failure is a state of its own: the pane offers another try rather than a spinner.
        assertEquals(ScreenStage.Failed, session.state.value.stage)
        assertEquals("The gateway refused the connection.", session.state.value.error)
    }

    @Test
    fun anOpeningThatFailsOffersAnotherTry() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false).also {
            it.answer = { call -> if (call.method == "display.observe") call.error(5300, "no screen here") else "{}" }
            it.start()
        }
        val session = session(gateway, backgroundScope)

        session.open()
        runCurrent()

        // The opening did not work: that is a state of its own, so the pane offers another try.
        assertEquals(ScreenStage.Failed, session.state.value.stage)
        assertEquals("no screen here", session.state.value.error)
    }

    @Test
    fun aRefreshDoesNotClearAFailure() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.viewerFailed("The screen closed.")

        session.refresh()

        // The screen may well be fine, but the last attempt at opening it did not work.
        assertEquals(ScreenStage.Failed, session.state.value.stage)
        assertEquals("The screen closed.", session.state.value.error)
    }

    @Test
    fun theViewerSaysTheScreenIsShowing() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.open()
        runCurrent()

        session.viewerConnected()

        assertEquals(ScreenStage.Connected, session.state.value.stage)
        assertNull(session.state.value.error)
    }

    @Test
    fun anOpeningThatIsStillWaitingOpensNothingWhenThePaneCloses() = runTest {
        // The gateway says nothing about the display stream, so the opening never finishes.
        val answers = Answers().also { it.observe = null }
        val gateway = gateway(backgroundScope, answers)
        val session = session(gateway, backgroundScope)

        session.open()
        runCurrent()
        assertEquals(ScreenStage.Opening, session.state.value.stage)

        session.close()

        // The pane is gone: nothing may open, and the screen is not left half-open.
        assertNull(session.state.value.connection)
        assertEquals(ScreenStage.Idle, session.state.value.stage)
    }

    @Test
    fun anAnswerThatArrivesAfterThePaneClosedOpensNothing() = runTest {
        val answers = Answers().also { it.observe = null }
        val gateway = gateway(backgroundScope, answers)
        val session = session(gateway, backgroundScope)
        session.open()
        runCurrent()
        val id = gateway.sent("display.observe").last()["id"]

        session.close()

        // The gateway's answer arrives late: the pane that asked for it is gone.
        // The gateway sends one frame per line, so the answer has to be on one line too.
        gateway.socket.push("""{"jsonrpc":"2.0","id":$id,"result":${observe.replace("\n", " ")}}""")
        runCurrent()

        assertNull(session.state.value.connection)
    }

    @Test
    fun theTicketIsTakenFromTheGatewayAndTheViewerIdKept() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)

        session.open()
        runCurrent()

        val connection = session.state.value.connection
        assertNotNull(connection)
        assertTrue(connection.url.contains("display_ticket=T"))
        assertEquals("V1", session.state.value.viewerId)
    }

    @Test
    fun aStartThatArrivesAfterThePaneClosedOpensNothing() = runTest {
        // The gateway says nothing about the start: the answer is held until the test says so.
        val answers = Answers().also {
            it.observe = observe
        }
        val gateway = gateway(backgroundScope, answers)
        val session = session(gateway, backgroundScope)

        val starting = launch { session.start() }
        runCurrent()
        val id = gateway.sent("display.start").last()["id"]

        session.close()

        // The gateway's answer arrives late: the pane that asked for it is gone, so the screen is not
        // started for nobody and nothing is opened.
        gateway.socket.push("""{"jsonrpc":"2.0","id":$id,"result":${status.replace("\n", " ")}}""")
        runCurrent()
        starting.join()

        assertNull(session.state.value.connection)
        assertEquals(emptyList(), gateway.sent("display.observe"))
    }

    @Test
    fun aStatusThatArrivesAfterThePaneClosedChangesNothing() = runTest {
        // The gateway says nothing about the screen yet: the answer is held until the test says so.
        val answers = Answers().also {
            it.observe = observe
            it.status = null
        }
        val gateway = gateway(backgroundScope, answers)
        val session = session(gateway, backgroundScope)

        val refreshing = launch { session.refresh() }
        runCurrent()
        val id = gateway.sent("display.status").last()["id"]

        session.close()

        gateway.socket.push("""{"jsonrpc":"2.0","id":$id,"result":${status.replace("\n", " ")}}""")
        runCurrent()
        refreshing.join()

        // The pane is gone: the answer is about a screen nobody watches. Nothing is opened, and the
        // answer is not taken for the screen's state either.
        assertNull(session.state.value.connection)
        assertNull(session.state.value.status)
        assertEquals(emptyList(), gateway.sent("display.observe"))
    }

    @Test
    fun aSessionThatWasPutAwayAsksNothingMore() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        runCurrent()
        val before = gateway.sent("display.status").size

        session.dispose()

        // The gateway says the screen moved on: a session that is gone asks nothing.
        gateway.socket.push(event("display.status", payload = """{"profile_key":"scribe"}"""))
        runCurrent()

        assertEquals(before, gateway.sent("display.status").size)
    }

    @Test
    fun anEventAboutAnotherProfileIsIgnored() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        runCurrent()
        val before = gateway.sent("display.status").size

        // One connection serves every profile on the gateway; this event is about another bot's screen.
        gateway.socket.push(event("display.status", payload = """{"profile_key":"another"}"""))
        runCurrent()

        assertEquals(before, gateway.sent("display.status").size)
    }

    @Test
    fun anEventAboutThisProfileAsksTheGatewayAgain() = runTest {
        val gateway = workingGateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        runCurrent()
        val before = gateway.sent("display.status").size

        gateway.socket.push(event("display.status", payload = """{"profile_key":"scribe"}"""))
        runCurrent()

        assertTrue(gateway.sent("display.status").size > before)
    }
}
