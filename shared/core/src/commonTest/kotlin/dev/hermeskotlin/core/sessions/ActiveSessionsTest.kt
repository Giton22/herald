package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveSessionsTest {

    private class Gateway(private val fake: FakeGateway) {
        /** What `session.active_list` answers next: a result object, or an `"error":{…}` member. */
        var answer: String = """{"sessions":[]}"""
        var asked = 0
        val transport get() = fake.socket
        val connection get() = fake.connection
    }

    private fun row(key: String, status: String) =
        """{"id":"rt-$key","session_key":"$key","status":"$status","title":"","preview":"","current":false}"""

    private suspend fun connect(scope: CoroutineScope): Gateway {
        val fake = FakeGateway(scope, reconnects = false).apply { ready = { FakeGateway.QUIET_READY } }
        val gateway = Gateway(fake)
        fake.answer = { call ->
            if (call.method != "session.active_list") {
                "{}"
            } else {
                gateway.asked++
                gateway.answer.takeIf { it.startsWith(""""error"""") }?.let { """{"jsonrpc":"2.0","id":${call.id},$it}""" } ?: gateway.answer
            }
        }
        fake.start()
        return gateway
    }

    private fun TestScope.active(gateway: Gateway) =
        ActiveSessions(gateway.connection, backgroundScope, pollMs = 10_000, settleMs = 500, clock = { testScheduler.currentTime + 1 })


    @Test
    fun eachStatusMapsByStoredId() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")},${row("b", "waiting")},${row("c", "starting")},${row("d", "idle")},${row("e", "streaming")},${row("f", "resuming")},${row("g", "sleeping")}]}"""
        val live = active(gateway).live.first { it.statuses.isNotEmpty() }
        assertEquals(
            mapOf(
                "a" to LiveStatus.Working, "b" to LiveStatus.Waiting, "c" to LiveStatus.Starting, "d" to LiveStatus.Idle,
                "e" to LiveStatus.Working, "f" to LiveStatus.Working, "g" to LiveStatus.Idle,
            ),
            live.statuses,
        )
    }

    @Test
    fun itAsksAgainOnEachIntervalAndStopsWhenNothingCollects() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        val active = active(gateway)
        val collector = backgroundScope.launch { active.live.collect {} }
        active.live.first { it.statuses.isNotEmpty() }
        val first = gateway.asked

        gateway.answer = """{"sessions":[${row("a", "idle")}]}"""
        advanceTimeBy(10_001)
        active.live.first { it.statuses["a"] == LiveStatus.Idle }
        assertTrue(gateway.asked > first)

        collector.cancel()
        advanceTimeBy(6_000)
        val stopped = gateway.asked
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(stopped, gateway.asked)
    }

    @Test
    fun aTurnEventAsksAgainOnceEventsSettle() = runTest {
        val gateway = connect(backgroundScope)
        val active = active(gateway)
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.askedAtMillis > 0 }
        runCurrent()
        val before = gateway.asked

        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        gateway.transport.push(event("message.start"))
        gateway.transport.push(event("message.complete"))
        advanceTimeBy(600)
        active.live.first { it.statuses["a"] == LiveStatus.Working }
        assertEquals(before + 1, gateway.asked)
    }

    @Test
    fun comingBackInSightAsksAtOnceInsteadOfAtTheEndOfTheSlowWait() = runTest {
        val gateway = connect(backgroundScope)
        val active = active(gateway)
        active.inBackground = true
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.askedAtMillis > 0 }
        runCurrent()
        val before = gateway.asked

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before, gateway.asked)
        // Something always collects now (the watcher), so nothing restarts the poll on its own.
        active.inBackground = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before + 1, gateway.asked)
    }

    @Test
    fun anOldGatewayKeepsTheLastAnswerAndIsNotAskedAgain() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        val active = active(gateway)
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.statuses.isNotEmpty() }

        gateway.answer = """"error":{"code":-32601,"message":"Method not found"}"""
        advanceTimeBy(10_001)
        runCurrent()
        val asked = gateway.asked
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(asked, gateway.asked)
        assertEquals(mapOf("a" to LiveStatus.Working), active.live.value.statuses)
    }

    @Test
    fun aDroppedConnectionShowsNothingAsLive() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        val active = active(gateway)
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.statuses.isNotEmpty() }

        gateway.transport.serverClose(1006, "gone")
        assertEquals(LiveSessions(), active.live.first { it.statuses.isEmpty() })
    }
}
