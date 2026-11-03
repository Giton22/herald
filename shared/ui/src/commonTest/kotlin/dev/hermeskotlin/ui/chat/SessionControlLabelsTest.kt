package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.GoalGate
import dev.hermeskotlin.core.chat.GoalPhase
import dev.hermeskotlin.core.chat.HeartbeatControl
import dev.hermeskotlin.core.chat.LoopControl
import dev.hermeskotlin.core.chat.WaitBarrier
import dev.hermeskotlin.ui.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The labels choose catalogue keys and their arguments; the words themselves are the catalogue's. */
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
        assertEquals(Res.string.chat_goal_working, goalPhaseLabel(GoalPhase.Active))
        assertEquals(Res.string.chat_paused, goalPhaseLabel(GoalPhase.Paused))
        assertEquals(Res.string.chat_goal_waiting, goalPhaseLabel(GoalPhase.Waiting))
        assertEquals(Res.string.chat_goal_blocked, goalPhaseLabel(GoalPhase.Blocked))
        assertEquals(Res.string.chat_subagent_done, goalPhaseLabel(GoalPhase.Done))
    }

    @Test
    fun turnsCountTheCapWhenThereIsOne() {
        assertEquals(Words.Count(Res.plurals.chat_goal_turns_of, 20, listOf(4, 20)), turnsLabel(4, 20))
        assertEquals(Words.Of(Res.string.chat_goal_turn, listOf(4)), turnsLabel(4, 0))
        assertNull(turnsLabel(0, 20))
    }

    @Test
    fun loopStatesSayRunningPausedFinishedAndHeld() {
        assertEquals(Res.string.chat_process_running, loopStateLabel(loop()))
        assertEquals(Res.string.chat_paused, loopStateLabel(loop(status = "paused")))
        assertEquals(Res.string.chat_loop_finished, loopStateLabel(loop(status = "done")))
        assertEquals(Res.string.chat_loop_held, loopStateLabel(loop(deferredByGoal = true)))
        // A paused loop held by the goal reads as paused, not running.
        assertEquals(Res.string.chat_paused, loopStateLabel(loop(status = "paused", deferredByGoal = true)))
    }

    @Test
    fun runsCountTheCapWhenThereIsOne() {
        assertEquals(Words.Count(Res.plurals.chat_loop_runs_of, 6, listOf(2, 6)), runsLabel(2, 6))
        assertEquals(Words.Count(Res.plurals.chat_loop_runs, 2), runsLabel(2, 0))
        assertNull(runsLabel(0, 6))
        // The strip read "1 runs" after a loop's first wakeup: the count now goes through a plural.
        assertEquals(Words.Count(Res.plurals.chat_loop_runs, 1), runsLabel(1, 0))
        assertEquals(Words.Count(Res.plurals.chat_loop_runs_of, 6, listOf(1, 6)), runsLabel(1, 6))
    }

    @Test
    fun aPlainPauseHasNoReasonToShow() {
        // The gateway's /goal pause and /loop pause say "user-paused"; the Paused label says it already.
        assertEquals(null, pausedReasonLabel("user-paused"))
        assertEquals(null, pausedReasonLabel(" "))
        assertEquals(null, pausedReasonLabel(null))
        assertEquals("turn budget exhausted (20/20)", pausedReasonLabel("turn budget exhausted (20/20)"))
    }

    @Test
    fun cadenceReadsAsEverySoOften() {
        assertEquals(Words.Of(Res.string.chat_every_seconds, listOf(45L)), everyLabel(45.0))
        assertEquals(Words.Of(Res.string.chat_every_minutes, listOf(30L)), everyLabel(1800.0))
        assertEquals(Words.Of(Res.string.chat_every_hours, listOf(1L)), everyLabel(3600.0))
        assertEquals(Words.Of(Res.string.chat_every_hours_minutes, listOf(1L, 30L)), everyLabel(5400.0))
        // A float interval keeps only the whole seconds.
        assertEquals(Words.Of(Res.string.chat_every_minutes, listOf(30L)), everyLabel(1800.9))
    }

    @Test
    fun gatesCountAttemptsAgainstTheRetries() {
        assertEquals(listOf(Words.Of(Res.string.chat_gate_not_run)), gateLabel(GoalGate("./gradlew test", 600, 2, attempts = 0, lastExitCode = null)))
        assertEquals(
            listOf(Words.Count(Res.plurals.chat_gate_attempts, 3, listOf(2, 3)), Words.Of(Res.string.chat_gate_exit, listOf(1))),
            gateLabel(GoalGate("./gradlew test", 600, 2, attempts = 2, lastExitCode = 1)),
        )
        assertEquals(
            listOf(Words.Count(Res.plurals.chat_gate_attempts, 3, listOf(1, 3))),
            gateLabel(GoalGate("./gradlew test", 600, 2, attempts = 1, lastExitCode = null)),
        )
    }

    @Test
    fun waitBarriersNameWhatTheGoalIsParkedOn() {
        // 2026-10-10 15:45 local in the test zone is derived from the clock, so only the shape is pinned here.
        val until = assertIs<Words.Of>(waitBarrierLabel(WaitBarrier.Until(1791819900.0, "CI is running"), use24Hour = true, nowMillis = 1791810000000L))
        assertEquals(Res.string.chat_wait_until, until.res)
        assertEquals(1, until.args.size)
        assertEquals(Words.Of(Res.string.chat_wait_other_chat), waitBarrierLabel(WaitBarrier.OnSession("s_abc", ""), use24Hour = true))
        assertEquals(Words.Of(Res.string.chat_wait_process, listOf(4242L)), waitBarrierLabel(WaitBarrier.OnProcess(4242, ""), use24Hour = true))
    }

    @Test
    fun loopLinesJoinStateRunsAndTheNextRun() {
        val held = loop(deferredByGoal = true, ticksFired = 2, times = 6)
        assertEquals(
            listOf(Words.Of(Res.string.chat_loop), Words.Of(Res.string.chat_loop_held), Words.Count(Res.plurals.chat_loop_runs_of, 6, listOf(2, 6))),
            loopLine(held, use24Hour = true),
        )
        val due = loopLine(loop(ticksFired = 2, times = 6, nextDueAt = 1791819900.0), use24Hour = true, nowMillis = 1791810000000L)
        assertEquals(4, due.size)
        assertEquals(Res.string.chat_loop_next, assertIs<Words.Of>(due.last()).res)
    }

    @Test
    fun loopLineSaysDueNowOnceTheNextRunPassed() {
        val overdue = loop(ticksFired = 2, times = 6, nextDueAt = 1791810000.0)
        val line = loopLine(overdue, use24Hour = true, nowMillis = 1791819900000L)
        assertEquals(Words.Of(Res.string.chat_loop_due_now), line.last())
    }

    @Test
    fun loopLineHonoursTheTwelveHourClock() {
        val due = loop(ticksFired = 2, times = 6, nextDueAt = 1791819900.0)
        val next = assertIs<Words.Of>(loopLine(due, use24Hour = false, nowMillis = 1791810000000L).last())
        assertEquals(Res.string.chat_loop_next, next.res)
        val time = next.args.single() as String
        assert(time.contains("AM") || time.contains("PM")) { time }
    }

    @Test
    fun waitBarrierSaysWaitingToResumeOnceItsTimePassed() {
        // The reason, when there is one, goes after the label in the sheet.
        val past = WaitBarrier.Until(1791810000.0, "")
        assertEquals(Words.Of(Res.string.chat_wait_resume), waitBarrierLabel(past, use24Hour = true, nowMillis = 1791819900000L))
        val pastWithReason = WaitBarrier.Until(1791810000.0, "CI is running")
        assertEquals(Words.Of(Res.string.chat_wait_resume), waitBarrierLabel(pastWithReason, use24Hour = true, nowMillis = 1791819900000L))
    }

    @Test
    fun heartbeatLinesCoverPausedFreshAndIntervalless() {
        val paused = HeartbeatControl("Check in", "paused", 3600, lastFiredAt = 0.0, fireCount = 0)
        assertEquals(listOf(Words.Of(Res.string.chat_heartbeat), Words.Of(Res.string.chat_paused)), heartbeatLine(paused))
        // Before the first fire there is no count to show.
        val fresh = HeartbeatControl("Check in", "active", 3600, lastFiredAt = 0.0, fireCount = 0)
        assertEquals(listOf(Words.Of(Res.string.chat_heartbeat), Words.Of(Res.string.chat_every_hours, listOf(1L))), heartbeatLine(fresh))
        // No interval means no cadence part, not a dangling "every 0 s".
        val noInterval = HeartbeatControl("Check in", "active", 0, lastFiredAt = 0.0, fireCount = 3)
        assertEquals(listOf(Words.Of(Res.string.chat_heartbeat), Words.Count(Res.plurals.chat_heartbeat_fired, 3)), heartbeatLine(noInterval))
    }

    @Test
    fun everyLabelIsEmptyWithoutAnInterval() {
        assertNull(everyLabel(0.0))
        assertNull(everyLabel(-5.0))
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
