package dev.hermeskotlin.ui.update

enum class ManagedUpdatePhase { Idle, Available, Ready }

data class ManagedUpdateState(
    val phase: ManagedUpdatePhase = ManagedUpdatePhase.Idle,
    val progress: Int? = null,
    val canRestart: Boolean = false,
)
