package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.GoalGate
import dev.hermeskotlin.core.chat.GoalPhase
import dev.hermeskotlin.core.chat.HeartbeatControl
import dev.hermeskotlin.core.chat.LoopControl
import dev.hermeskotlin.core.chat.WaitBarrier
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionControlLabelsTest {

    private fun loop(
        status: String = "active",
        deferredByGoal: Boolean = false,
        ticksFired: Int = 0,
        times: Int = 0,
        nextDueAt: Double = 0.0,
    ) = LoopControl(
        prompt = "Check the deploy log",
        status = status,
        selfPaced = false,
        intervalSeconds = 1800.0,
        times = times,
        until = "",
        ticksFired = ticksFired,
        lastFiredAt = 0.0,
        nextDueAt = nextDueAt,
        awaitingResponse = false,
        deferredByGoal = deferredByGoal,
        pausedReason = null,
        lastStopReason = null,
    )

    @Test
    fun phaseLabelsMatchTheStrip() {
        assertEquals("Working", goalPhaseLabel(GoalPhase.Active))
        assertEquals("Paused", goalPhaseLabel(GoalPhase.Paused))
        assertEquals("Waiting", goalPhaseLabel(GoalPhase.Waiting))
        assertEquals("Blocked", goalPhaseLabel(GoalPhase.Blocked))
        assertEquals("Done", goalPhaseLabel(GoalPhase.Done))
    }

    @Test
    fun turnsCountTheCapWhenThereIsOne() {
        assertEquals("4 of 20 turns", turnsLabel(4, 20))
        assertEquals("turn 4", turnsLabel(4, 0))
        assertEquals("", turnsLabel(0, 20))
    }

    @Test
    fun loopStatesSayRunningPausedFinishedAndHeld() {
        assertEquals("Running", loopStateLabel(loop()))
        assertEquals("Paused", loopStateLabel(loop(status = "paused")))
        assertEquals("Finished", loopStateLabel(loop(status = "done")))
        assertEquals("Held by the goal", loopStateLabel(loop(deferredByGoal = true)))
        // A paused loop held by the goal reads as paused, not running.
        assertEquals("Paused", loopStateLabel(loop(status = "paused", deferredByGoal = true)))
    }

    @Test
    fun runsCountTheCapWhenThereIsOne() {
        assertEquals("2 of 6 runs", runsLabel(2, 6))
        assertEquals("2 runs", runsLabel(2, 0))
        assertEquals("", runsLabel(0, 6))
    }

    @Test
    fun cadenceReadsAsEverySoOften() {
        assertEquals("every 45 s", everyLabel(45.0))
        assertEquals("every 30 min", everyLabel(1800.0))
        assertEquals("every 1 h", everyLabel(3600.0))
        assertEquals("every 1 h 30 min", everyLabel(5400.0))
        // A float interval keeps only the whole seconds.
        assertEquals("every 30 min", everyLabel(1800.9))
    }

    @Test
    fun gatesCountAttemptsAgainstTheRetries() {
        assertEquals("not run yet", gateLabel(GoalGate("./gradlew test", 600, 2, attempts = 0, lastExitCode = null)))
        assertEquals("2 of 3 attempts · exit 1", gateLabel(GoalGate("./gradlew test", 600, 2, attempts = 2, lastExitCode = 1)))
        assertEquals("1 of 3 attempts", gateLabel(GoalGate("./gradlew test", 600, 2, attempts = 1, lastExitCode = null)))
    }

    @Test
    fun waitBarriersNameWhatTheGoalIsParkedOn() {
        // 2026-10-10 15:45 local in the test zone is derived from the clock, so only the shape is pinned here.
        val until = waitBarrierLabel(WaitBarrier.Until(1791819900.0, "CI is running"), use24Hour = true, nowMillis = 1791810000000L)
        assert(until.startsWith("Waiting until ")) { until }
        assertEquals("Waiting on another chat", waitBarrierLabel(WaitBarrier.OnSession("s_abc", ""), use24Hour = true))
        assertEquals("Waiting on process 4242", waitBarrierLabel(WaitBarrier.OnProcess(4242, ""), use24Hour = true))
    }

    @Test
    fun loopLinesJoinStateRunsAndTheNextRun() {
        val held = loop(deferredByGoal = true, ticksFired = 2, times = 6)
        assertEquals("Loop · Held by the goal · 2 of 6 runs", loopLine(held, use24Hour = true))
        val due = loop(ticksFired = 2, times = 6, nextDueAt = 1791819900.0)
        assert(loopLine(due, use24Hour = true, nowMillis = 1791810000000L).startsWith("Loop · Running · 2 of 6 runs · next "))
    }

    @Test
    fun loopLineSaysDueNowOnceTheNextRunPassed() {
        val overdue = loop(ticksFired = 2, times = 6, nextDueAt = 1791810000.0)
        val line = loopLine(overdue, use24Hour = true, nowMillis = 1791819900000L)
        assertEquals("Loop · Running · 2 of 6 runs · due now", line)
    }

    @Test
    fun loopLineHonoursTheTwelveHourClock() {
        val due = loop(ticksFired = 2, times = 6, nextDueAt = 1791819900.0)
        val line = loopLine(due, use24Hour = false, nowMillis = 1791810000000L)
        assert(line.startsWith("Loop · Running · 2 of 6 runs · next ")) { line }
        assert(line.contains("AM") || line.contains("PM")) { line }
    }

    @Test
    fun waitBarrierSaysWaitingToResumeOnceItsTimePassed() {
        val past = WaitBarrier.Until(1791810000.0, "")
        assertEquals("Waiting to resume", waitBarrierLabel(past, use24Hour = true, nowMillis = 1791819900000L))
        val pastWithReason = WaitBarrier.Until(1791810000.0, "CI is running")
        assertEquals("Waiting to resume: CI is running", waitBarrierLabel(pastWithReason, use24Hour = true, nowMillis = 1791819900000L))
    }

    @Test
    fun heartbeatLinesCoverPausedFreshAndIntervalless() {
        val paused = HeartbeatControl("Check in", "paused", 3600, lastFiredAt = 0.0, fireCount = 0)
        assertEquals("Heartbeat · Paused", heartbeatLine(paused))
        // Before the first fire there is no count to show.
        val fresh = HeartbeatControl("Check in", "active", 3600, lastFiredAt = 0.0, fireCount = 0)
        assertEquals("Heartbeat · every 1 h", heartbeatLine(fresh))
        // No interval means no cadence part, not a dangling "every 0 s".
        val noInterval = HeartbeatControl("Check in", "active", 0, lastFiredAt = 0.0, fireCount = 3)
        assertEquals("Heartbeat · 3 fired", heartbeatLine(noInterval))
    }

    @Test
    fun everyLabelIsEmptyWithoutAnInterval() {
        assertEquals("", everyLabel(0.0))
        assertEquals("", everyLabel(-5.0))
    }

    @Test
    fun subgoalIndexResolvesAtConfirmTime() {
        val subgoals = listOf("Write the tests", "Ship it", "Ship it")
        assertEquals(2, subgoalIndexOf(subgoals, "Ship it"))
        // A sub-goal removed while the dialog was open sends nothing.
        assertEquals(null, subgoalIndexOf(subgoals, "Gone"))
        assertEquals(null, subgoalIndexOf(emptyList(), "Ship it"))
    }
}
