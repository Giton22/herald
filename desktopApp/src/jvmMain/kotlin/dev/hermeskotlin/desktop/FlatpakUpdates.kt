package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.update.ManagedUpdatePhase
import dev.hermeskotlin.ui.update.ManagedUpdateState

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
