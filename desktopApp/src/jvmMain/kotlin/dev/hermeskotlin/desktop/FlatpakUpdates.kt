package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.update.ManagedUpdateFailure
import dev.hermeskotlin.ui.update.ManagedUpdatePhase
import dev.hermeskotlin.ui.update.ManagedUpdateState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class FlatpakUpdateInfo(val running: String?, val local: String?, val remote: String?) {
    fun offer(): ManagedUpdateState {
        val installed = local?.takeUnless { it.isBlank() } ?: return ManagedUpdateState()
        val canRestart = !running.isNullOrBlank() && running != installed
        val phase = when {
            !remote.isNullOrBlank() && remote != installed -> ManagedUpdatePhase.Available
            canRestart -> ManagedUpdatePhase.Ready
            else -> ManagedUpdatePhase.Idle
        }
        return ManagedUpdateState(phase, canRestart = canRestart)
    }
}

internal sealed interface FlatpakUpdateEvent {
    data class Info(val info: FlatpakUpdateInfo) : FlatpakUpdateEvent
    data class Progress(val status: Int?, val progress: Int? = null) : FlatpakUpdateEvent
    data class Failure(val failure: ManagedUpdateFailure) : FlatpakUpdateEvent
}

internal interface FlatpakUpdateMonitor : AutoCloseable {
    suspend fun update(parent: String)
    suspend fun restart(): Long
}

internal class FlatpakUpdateController(
    private val scope: CoroutineScope,
    private val open: suspend ((FlatpakUpdateEvent) -> Unit) -> FlatpakUpdateMonitor?,
    private val onRestartAccepted: () -> Unit,
    private val operationTimeoutMillis: Long = 900_000,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ManagedUpdateState())
    val state = mutableState.asStateFlow()
    private var enabled = false
    private var closed = false
    private var generation = 0L
    private var monitor: FlatpakUpdateMonitor? = null
    private var operation: Job? = null
    private var terminal: CompletableDeferred<Unit>? = null
    private val requested = AtomicBoolean()
    private var confirmedRestart = false
    private var info: FlatpakUpdateInfo? = null
    private var dismissed: FlatpakUpdateInfo? = null

    // Actions and the supplied scope belong to the UI thread; wire callbacks are marshalled onto that scope.
    fun setEnabled(value: Boolean) {
        if (closed || value == enabled) return
        enabled = value
        release()
        confirmedRestart = false
        info = null
        dismissed = null
        mutableState.value = ManagedUpdateState()
        if (value) {
            val owner = generation
            scope.launch { connect(owner) }
        }
    }

    fun update(parent: String = "") {
        val current = state.value
        if (!enabled || closed || !(current.phase == ManagedUpdatePhase.Available ||
                current.phase == ManagedUpdatePhase.Cancelled ||
                current.phase == ManagedUpdatePhase.Failed && current.failure in
                listOf(ManagedUpdateFailure.Denied, ManagedUpdateFailure.Failed))) return
        release()
        mutableState.value = ManagedUpdateState(ManagedUpdatePhase.Updating, canRestart = current.canRestart)
        val owner = generation
        operation = scope.launch {
            val done = CompletableDeferred<Unit>()
            terminal = done
            try {
                withTimeout(operationTimeoutMillis) {
                    val active = connect(owner) ?: return@withTimeout
                    requested.set(true)
                    active.update(parent)
                    done.await()
                }
            } catch (_: TimeoutCancellationException) {
                if (owner == generation && state.value.phase == ManagedUpdatePhase.Updating) fail(ManagedUpdateFailure.Uncertain)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner == generation && state.value.phase == ManagedUpdatePhase.Updating) fail(ManagedUpdateFailure.Uncertain)
            }
        }
    }

    fun cancel() {
        if (!enabled || closed || state.value.phase != ManagedUpdatePhase.Updating) return
        val canRestart = state.value.canRestart
        release()
        mutableState.value = ManagedUpdateState(ManagedUpdatePhase.Cancelled, canRestart = canRestart)
    }

    fun restart() {
        val current = state.value
        if (!enabled || closed || !current.canRestart || current.failure == ManagedUpdateFailure.RestartFailed ||
            current.phase in listOf(ManagedUpdatePhase.Updating, ManagedUpdatePhase.Restarting)) return
        mutableState.value = current.copy(phase = ManagedUpdatePhase.Restarting, progress = null, failure = null)
        val owner = generation
        operation = scope.launch {
            try {
                withTimeout(operationTimeoutMillis) {
                    val active = monitor ?: connect(owner, ManagedUpdateFailure.RestartFailed) ?: return@withTimeout
                    check(active.restart() > 0)
                    if (owner == generation) onRestartAccepted()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException && owner == generation) fail(ManagedUpdateFailure.RestartFailed)
                else throw cancelled
            } catch (_: Exception) {
                if (owner == generation) fail(ManagedUpdateFailure.RestartFailed)
            }
        }
    }

    fun dismiss() {
        if (state.value.phase in listOf(ManagedUpdatePhase.Updating, ManagedUpdatePhase.Restarting)) return
        dismissed = info
        mutableState.value = ManagedUpdateState()
    }

    override fun close() {
        closed = true
        enabled = false
        release()
        mutableState.value = ManagedUpdateState()
    }

    private suspend fun connect(owner: Long, unavailable: ManagedUpdateFailure = ManagedUpdateFailure.Unavailable): FlatpakUpdateMonitor? {
        val created = try {
            open { event ->
                // A queued pre-invocation signal must not become eligible when Update starts later.
                val afterRequest = requested.get()
                scope.launch {
                    if (owner == generation && enabled && !closed &&
                        (event !is FlatpakUpdateEvent.Progress || afterRequest)) accept(event)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (owner != generation || !enabled || closed) {
            runCatching { created?.close() }
            return null
        }
        monitor = created
        if (created == null) fail(unavailable)
        return created
    }

    private fun accept(event: FlatpakUpdateEvent) {
        when (event) {
            is FlatpakUpdateEvent.Info -> {
                info = event.info
                val facts = event.info.offer()
                if (state.value.phase in listOf(ManagedUpdatePhase.Updating, ManagedUpdatePhase.Restarting)) {
                    mutableState.value = state.value.copy(canRestart = state.value.canRestart || facts.canRestart)
                } else if (event.info != dismissed) {
                    mutableState.value = facts.copy(canRestart = facts.canRestart || confirmedRestart)
                }
            }
            is FlatpakUpdateEvent.Progress -> if (requested.get() && state.value.phase == ManagedUpdatePhase.Updating) {
                when (event.status) {
                    1 -> finish(ManagedUpdatePhase.NoUpdate, state.value.canRestart)
                    2 -> { confirmedRestart = true; finish(ManagedUpdatePhase.Ready, true) }
                    3 -> fail(ManagedUpdateFailure.Failed)
                    else -> mutableState.value = state.value.copy(progress = event.progress?.takeIf { it in 0..100 })
                }
            }
            is FlatpakUpdateEvent.Failure -> fail(event.failure)
        }
    }

    private fun finish(phase: ManagedUpdatePhase, canRestart: Boolean) {
        requested.set(false)
        mutableState.value = ManagedUpdateState(phase, canRestart = canRestart)
        terminal?.complete(Unit)
    }

    private fun fail(failure: ManagedUpdateFailure) {
        val next = ManagedUpdateState(ManagedUpdatePhase.Failed, canRestart = state.value.canRestart, failure = failure)
        release()
        mutableState.value = next
    }

    private fun release() {
        generation++
        requested.set(false)
        operation?.cancel()
        operation = null
        terminal?.cancel()
        terminal = null
        val owned = monitor
        monitor = null
        runCatching { owned?.close() }
    }
}
