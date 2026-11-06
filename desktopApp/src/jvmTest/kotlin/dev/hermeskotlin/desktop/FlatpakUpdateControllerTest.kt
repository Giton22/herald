package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.update.ManagedUpdateFailure
import dev.hermeskotlin.ui.update.ManagedUpdatePhase
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FlatpakUpdateControllerTest {
    @Test
    fun monitoringRequiresEnableAndNeverRequestsAnUpdate() = Fixture().use { f ->
        assertTrue(f.monitors.isEmpty())
        f.controller.setEnabled(true)
        f.controller.setEnabled(true)
        assertEquals(1, f.monitors.size)
        assertEquals(0, f.latest.updates)
        assertEquals(ManagedUpdatePhase.Available, f.phase)
    }

    @Test
    fun unsupportedMonitorHasASafeTypedFailure() = Fixture(supported = false).use { f ->
        f.controller.setEnabled(true)
        f.controller.update()
        assertEquals(ManagedUpdateFailure.Unavailable, f.controller.state.value.failure)
        assertTrue(f.monitors.isEmpty())
    }

    @Test
    fun repeatedUpdateClicksRequestOnceAndRunning100IsNotCompletion() = Fixture().use { f ->
        f.startUpdate()
        f.controller.update()
        assertEquals(1, f.latest.updates)
        f.latest.emit(FlatpakUpdateEvent.Progress(0, 100))
        assertEquals(ManagedUpdatePhase.Updating, f.phase)
        assertEquals(100, f.controller.state.value.progress)
        assertFalse(f.controller.state.value.canRestart)
        assertEquals(0, f.restarts)
    }

    @Test
    fun terminalDoneOffersRestartWithoutExiting() = Fixture().use { f ->
        f.startUpdate()
        f.latest.emit(FlatpakUpdateEvent.Progress(2))
        assertEquals(ManagedUpdatePhase.Ready, f.phase)
        assertTrue(f.controller.state.value.canRestart)
        assertEquals(0, f.restarts)
    }

    @Test
    fun terminalEmptyDoesNotInventAnInstalledUpdate() = Fixture().use { f ->
        f.startUpdate()
        f.latest.emit(FlatpakUpdateEvent.Progress(1))
        assertEquals(ManagedUpdatePhase.NoUpdate, f.phase)
        assertFalse(f.controller.state.value.canRestart)
    }

    @Test
    fun failuresStayTypedAndDoNotRestart() {
        for (failure in listOf(ManagedUpdateFailure.Denied, ManagedUpdateFailure.SystemUpdateRequired,
            ManagedUpdateFailure.Failed, ManagedUpdateFailure.Uncertain)) Fixture().use { f ->
            f.startUpdate()
            f.latest.emit(FlatpakUpdateEvent.Failure(failure))
            assertEquals(ManagedUpdatePhase.Failed, f.phase)
            assertEquals(failure, f.controller.state.value.failure)
            assertEquals(0, f.restarts)
        }
    }

    @Test
    fun cancelClosesMonitorAndOldCompletionCannotFinishANewAttempt() = Fixture().use { f ->
        f.startUpdate()
        val old = f.latest
        f.controller.cancel()
        assertTrue(old.closed)
        assertEquals(ManagedUpdatePhase.Cancelled, f.phase)
        f.controller.update()
        val current = f.latest
        assertTrue(old !== current)
        old.emit(FlatpakUpdateEvent.Progress(2))
        assertEquals(ManagedUpdatePhase.Updating, f.phase)
        current.emit(FlatpakUpdateEvent.Progress(2))
        assertEquals(ManagedUpdatePhase.Ready, f.phase)
    }

    @Test
    fun disableDisposesResourcesAndIgnoresLateCallbacks() = Fixture().use { f ->
        f.startUpdate()
        val old = f.latest
        f.controller.setEnabled(false)
        old.emit(FlatpakUpdateEvent.Progress(2))
        assertTrue(old.closed)
        assertEquals(ManagedUpdatePhase.Idle, f.phase)
        f.controller.setEnabled(true)
        f.controller.close()
        assertTrue(f.latest.closed)
        f.latest.emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "B", "B")))
        assertEquals(ManagedUpdatePhase.Idle, f.phase)
    }

    @Test
    fun disableWhileOpeningClosesTheLateMonitor() = Fixture().use { f ->
        val gate = CompletableDeferred<Unit>()
        f.openGate = gate
        f.controller.setEnabled(true)
        assertEquals(1, f.monitors.size)
        f.controller.setEnabled(false)
        gate.complete(Unit)
        assertTrue(f.latest.closed)
        assertEquals(ManagedUpdatePhase.Idle, f.phase)
    }

    @Test
    fun externalUpdateAndNewRemoteRemainIndependent() = Fixture().use { f ->
        f.controller.setEnabled(true)
        assertEquals(ManagedUpdatePhase.Available, f.phase)
        f.latest.emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "B", "C")))
        assertEquals(ManagedUpdatePhase.Available, f.phase)
        assertTrue(f.controller.state.value.canRestart)
        f.startUpdate()
        f.latest.emit(FlatpakUpdateEvent.Progress(1))
        assertTrue(f.controller.state.value.canRestart)
        assertEquals(0, f.restarts)
    }

    @Test
    fun invalidProgressDoesNotCompleteOrDisplayAnInvalidPercent() = Fixture().use { f ->
        f.startUpdate()
        f.latest.emit(FlatpakUpdateEvent.Progress(null, 999))
        assertEquals(ManagedUpdatePhase.Updating, f.phase)
        assertEquals(null, f.controller.state.value.progress)
        assertFalse(f.controller.state.value.canRestart)
    }

    @Test
    fun missingTerminalTimesOutWithoutReplayingTheRequest() = runBlocking {
        Fixture(timeoutMillis = 30).use { f ->
            f.startUpdate()
            val requested = f.latest
            withTimeout(2_000) { f.controller.state.first { it.phase == ManagedUpdatePhase.Failed } }
            assertEquals(ManagedUpdateFailure.Uncertain, f.controller.state.value.failure)
            assertTrue(requested.closed)
            assertEquals(1, requested.updates)
            f.controller.update()
            assertEquals(1, f.monitors.sumOf { it.updates })
            assertEquals(0, f.restarts)
        }
    }

    @Test
    fun restartRequiresExplicitActionAndPositiveAcknowledgement() = Fixture().use { f ->
        f.startUpdate()
        f.latest.emit(FlatpakUpdateEvent.Progress(2))
        f.controller.restart()
        f.controller.restart()
        assertEquals(1, f.latest.spawns)
        assertEquals(1, f.restarts)
    }

    @Test
    fun failedOrUnacknowledgedRestartLeavesOldAppRunningWithoutReplay() {
        for (pid in listOf(0L, -1L)) Fixture().use { f ->
            f.startUpdate()
            f.latest.emit(FlatpakUpdateEvent.Progress(2))
            val spawner = f.latest
            spawner.restartPid = pid
            f.controller.restart()
            f.controller.restart()
            assertEquals(ManagedUpdateFailure.RestartFailed, f.controller.state.value.failure)
            assertEquals(0, f.restarts)
            assertEquals(1, f.monitors.sumOf { it.spawns })
            assertEquals(1, spawner.spawns)
        }
        Fixture().use { f ->
            f.startUpdate()
            f.latest.emit(FlatpakUpdateEvent.Progress(2))
            f.latest.restartError = IOException("private detail")
            f.controller.restart()
            assertEquals(ManagedUpdateFailure.RestartFailed, f.controller.state.value.failure)
            assertEquals(0, f.restarts)
        }
    }

    @Test
    fun completionEmittedBeforeUpdateInvocationCannotCompleteTheRequest() = Fixture(earlyProgress = true).use { f ->
        f.startUpdate()
        assertEquals(1, f.latest.updates)
        assertFalse(f.controller.state.value.canRestart)
    }

    @Test
    fun staleCancelAfterDisableOrCloseCannotRestoreAnOffer() {
        for (close in listOf(false, true)) Fixture().use { f ->
            f.startUpdate()
            if (close) f.controller.close() else f.controller.setEnabled(false)
            f.controller.cancel()
            assertEquals(ManagedUpdatePhase.Idle, f.phase)
        }
    }

    @Test
    fun restartTimeoutNeverExitsOrReplaysSpawn() = runBlocking {
        Fixture(timeoutMillis = 30).use { f ->
            f.startUpdate()
            f.latest.emit(FlatpakUpdateEvent.Progress(2))
            f.latest.restartGate = CompletableDeferred()
            f.controller.restart()
            withTimeout(2_000) { f.controller.state.first { it.phase == ManagedUpdatePhase.Failed } }
            f.controller.restart()
            assertEquals(ManagedUpdateFailure.RestartFailed, f.controller.state.value.failure)
            assertEquals(1, f.monitors.sumOf { it.spawns })
            assertEquals(0, f.restarts)
        }
    }

    @Test
    fun transportExceptionKeepsOutcomeUncertainWithoutRetry() = Fixture(updateError = IOException("private detail")).use { f ->
        f.controller.setEnabled(true)
        f.controller.update()
        assertEquals(ManagedUpdateFailure.Uncertain, f.controller.state.value.failure)
        f.controller.update()
        // Enable, the attempt, then watching again after its failure; an uncertain outcome isn't retried.
        assertEquals(3, f.monitors.size)
        assertEquals(1, f.monitors.sumOf { it.updates })
        assertFalse(f.latest.closed)
        assertEquals(0, f.restarts)
    }

    @Test
    fun aCancelledOrFailedUpdateKeepsWatchingForTheNextRelease() {
        for (end in listOf<(Fixture) -> Unit>({ it.controller.cancel() },
            { it.latest.emit(FlatpakUpdateEvent.Failure(ManagedUpdateFailure.Denied)) })) Fixture().use { f ->
            f.startUpdate()
            val requested = f.latest
            end(f)
            assertTrue(requested.closed)
            val watching = f.latest
            assertTrue(watching !== requested && !watching.closed)
            // The outcome stays on screen while the new monitor reports the same release.
            val outcome = f.controller.state.value
            watching.emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "A", "B")))
            assertEquals(outcome.phase, f.phase)
            // Not now, then a newer release: it's offered.
            f.controller.dismiss()
            watching.emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "A", "C")))
            assertEquals(ManagedUpdatePhase.Available, f.phase)
            assertEquals(0, f.restarts)
        }
    }

    @Test
    fun anUnreachablePortalIsNotReopenedInALoop() = Fixture(supported = false).use { f ->
        f.controller.setEnabled(true)
        assertEquals(ManagedUpdateFailure.Unavailable, f.controller.state.value.failure)
        assertEquals(1, f.opens)
    }

    @Test
    fun dismissSuppressesTheSameFactsButAllowsANewCandidate() = Fixture().use { f ->
        f.controller.setEnabled(true)
        f.controller.dismiss()
        f.latest.emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "A", "B")))
        assertEquals(ManagedUpdatePhase.Idle, f.phase)
        f.latest.emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "A", "C")))
        assertEquals(ManagedUpdatePhase.Available, f.phase)
    }

    @Test
    fun confirmedTerminalRemainsAuthoritativeWhenTheMethodReplyIsLost() =
        Fixture(terminalBeforeReply = true, updateError = IOException("reply lost")).use { f ->
            f.controller.setEnabled(true)
            f.controller.update()
            assertEquals(ManagedUpdatePhase.Ready, f.phase)
            f.latest.updateGate!!.complete(Unit)
            assertEquals(ManagedUpdatePhase.Ready, f.phase)
            assertEquals(0, f.restarts)
        }

    private class Fixture(val supported: Boolean = true, timeoutMillis: Long = 60_000,
        val earlyProgress: Boolean = false, val updateError: Exception? = null,
        val terminalBeforeReply: Boolean = false) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val monitors = mutableListOf<Monitor>()
        var restarts = 0
        var openGate: CompletableDeferred<Unit>? = null
        var opens = 0
        val controller = FlatpakUpdateController(scope, open = { emit ->
            opens++
            if (!supported) null else Monitor(emit).also {
                monitors += it
                it.updateError = updateError
                if (terminalBeforeReply) it.updateGate = CompletableDeferred()
                openGate?.await()
                emit(FlatpakUpdateEvent.Info(FlatpakUpdateInfo("A", "A", "B")))
                if (earlyProgress) emit(FlatpakUpdateEvent.Progress(2))
            }
        }, onRestartAccepted = { restarts++ }, operationTimeoutMillis = timeoutMillis)
        val latest get() = monitors.last()
        val phase get() = controller.state.value.phase
        fun startUpdate() {
            controller.setEnabled(true)
            controller.update()
            assertEquals(ManagedUpdatePhase.Updating, phase)
        }
        override fun close() { controller.close(); scope.cancel() }
    }

    private class Monitor(val emit: (FlatpakUpdateEvent) -> Unit) : FlatpakUpdateMonitor {
        var updates = 0
        var spawns = 0
        var closed = false
        var restartPid = 42L
        var restartError: Exception? = null
        var updateError: Exception? = null
        var updateGate: CompletableDeferred<Unit>? = null
        var restartGate: CompletableDeferred<Long>? = null
        override suspend fun update(parent: String) {
            updates++
            updateGate?.let { emit(FlatpakUpdateEvent.Progress(2)); it.await() }
            updateError?.let { throw it }
        }
        override suspend fun restart(): Long { spawns++; restartError?.let { throw it }; return restartGate?.await() ?: restartPid }
        override fun close() { closed = true }
    }
}
