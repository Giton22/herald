package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The `session.control` wire shapes: snapshots, goal phases and the reducer's `session.control.update`. */
class SessionControlTest {

    private fun event(payload: String) =
        GatewayEvent("session.control.update", HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    @Test
    fun theActiveGoalSnapshotParses() {
        val control = ControlFixtures.parse(ControlFixtures.GOAL_ACTIVE)!!
        assertEquals("4f3e15ca7c831c1c3a1918666e3e9f3fae7106716c932de8eac41a91bdf9ae81", control.revision)
        val goal = control.goal!!
        assertEquals("Get the test suite green on the feat/goals branch", goal.title)
        assertEquals("active", goal.status)
        assertEquals(4, goal.turnsUsed)
        assertEquals(20, goal.maxTurns)
        assertEquals(listOf("Fix the flaky reconnect test", "Pin the wire contract in a test"), goal.subgoals)
        assertEquals(GoalGate("./gradlew :shared:core:testAndroidHostTest", 600, 2, 0, null), goal.gates.single())
        assertEquals("continue", goal.lastVerdict)
        assertEquals("Two tests still fail in ChatSessionTest.", goal.lastReason)
        assertNull(goal.waitBarrier)
        assertTrue(goal.contract.isEmpty)
        assertEquals(GoalPhase.Active, goal.phase)
        assertNull(control.loop)
        assertNull(control.heartbeat)
    }

    @Test
    fun thePausedGoalKeepsItsReason() {
        val goal = ControlFixtures.parse(ControlFixtures.GOAL_PAUSED)!!.goal!!
        assertEquals("paused", goal.status)
        assertEquals("user-paused", goal.pausedReason)
        assertEquals(GoalPhase.Paused, goal.phase)
    }

    @Test
    fun theWaitingGoalParsesItsUntilBarrier() {
        val goal = ControlFixtures.parse(ControlFixtures.GOAL_WAITING)!!.goal!!
        val barrier = assertIs<WaitBarrier.Until>(goal.waitBarrier)
        assertEquals(1791646233.593687, barrier.untilAt)
        assertEquals("CI is running; check back when it finishes", barrier.reason)
        assertEquals(GoalPhase.Waiting, goal.phase)
    }

    @Test
    fun aGoalWaitingOnAProcessOrAnotherChatParsesItsBarrier() {
        // _extract_wait_barrier: the pid is a JSON int, the session a string.
        val onPid = WaitBarrier.parse(HermesJson.parseToJsonElement("""{"type":"pid","target":4242,"reason":"build"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(WaitBarrier.OnProcess(4242, "build"), onPid)
        val onChat = WaitBarrier.parse(HermesJson.parseToJsonElement("""{"type":"session","target":"20261010_1200_x","reason":""}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(WaitBarrier.OnSession("20261010_1200_x", ""), onChat)
    }

    @Test
    fun anUpdateWithoutASnapshotLeavesTheControlAlone() {
        val state = ChatState().reduce(event("""{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        assertSame(state, state.reduce(event("""{}""")))
        assertSame(state, state.reduce(event("""{"control":"oops"}""")))
    }

    @Test
    fun theLoopAndHeartbeatSnapshotParses() {
        val control = ControlFixtures.parse(ControlFixtures.GOAL_LOOP_HEARTBEAT)!!
        val loop = control.loop!!
        assertEquals("Check the deploy log and report errors", loop.prompt)
        assertEquals("active", loop.status)
        assertEquals(false, loop.selfPaced)
        // 1800.0 on the wire: numbers may arrive as float.
        assertEquals(1800.0, loop.intervalSeconds)
        assertEquals(6, loop.times)
        assertEquals("", loop.until)
        assertEquals(0, loop.ticksFired)
        assertEquals(0.0, loop.lastFiredAt)
        assertEquals(1791645333.61065, loop.nextDueAt)
        assertTrue(loop.deferredByGoal)
        val heartbeat = control.heartbeat!!
        assertEquals("Any new GitHub notifications?", heartbeat.prompt)
        assertEquals(3600L, heartbeat.intervalSeconds)
        assertEquals(0, heartbeat.fireCount)
    }

    @Test
    fun theDoneGoalSnapshotParses() {
        val goal = ControlFixtures.parse(ControlFixtures.GOAL_DONE)!!.goal!!
        assertEquals("done", goal.status)
        assertEquals("done", goal.lastVerdict)
        assertEquals("All tests pass and CI is green.", goal.lastReason)
        assertEquals(GoalPhase.Done, goal.phase)
    }

    @Test
    fun theEmptySnapshotParsesToNull() {
        assertNull(ControlFixtures.parse(ControlFixtures.EMPTY))
        assertNull(SessionControl.parse(null))
    }

    @Test
    fun aMalformedPartDropsItselfAndKeepsTheRest() {
        // The fixtures are re-serialized compactly; a goal without a title is dropped on its own.
        val goalless = ControlFixtures.GOAL_LOOP_HEARTBEAT.replace(
            """"title":"Get the test suite green on the feat/goals branch"""",
            """"title":null""",
        )
        val control = ControlFixtures.parse(goalless)!!
        assertNull(control.goal)
        assertEquals("Check the deploy log and report errors", control.loop?.prompt)
    }

    @Test
    fun blockedWinsThenWaitingPausedDoneActive() {
        val active = ControlFixtures.parse(ControlFixtures.GOAL_ACTIVE)!!.goal!!
        val waiting = ControlFixtures.parse(ControlFixtures.GOAL_WAITING)!!.goal!!
        assertEquals(GoalPhase.Blocked, active.copy(lastVerdict = "blocked").phase)
        // A verdict isn't cleared by a wait barrier, so blocked still wins over waiting.
        assertEquals(GoalPhase.Blocked, waiting.copy(lastVerdict = "blocked").phase)
        assertEquals(GoalPhase.Waiting, waiting.phase)
        assertEquals(GoalPhase.Waiting, waiting.copy(status = "paused").phase)
        assertEquals(GoalPhase.Paused, active.copy(status = "paused").phase)
        assertEquals(GoalPhase.Paused, active.copy(status = "paused", lastVerdict = "done").phase)
        assertEquals(GoalPhase.Done, active.copy(status = "done", lastVerdict = "done").phase)
        assertEquals(GoalPhase.Active, active.phase)
    }

    @Test
    fun aControlUpdateSetsTheStateAndAnEmptyOneClearsIt() {
        val set = ChatState().reduce(event("""{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        assertEquals("Get the test suite green on the feat/goals branch", set.control?.goal?.title)

        val cleared = set.reduce(event("""{"control":${ControlFixtures.EMPTY}}"""))
        assertNull(cleared.control)
    }

    @Test
    fun anUnchangedRevisionKeepsTheSameReference() {
        val first = ChatState().reduce(event("""{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        val again = first.reduce(event("""{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        assertSame(first, again)

        val changed = first.reduce(event("""{"control":${ControlFixtures.GOAL_PAUSED}}"""))
        assertEquals("paused", changed.control?.goal?.status)
    }
}
