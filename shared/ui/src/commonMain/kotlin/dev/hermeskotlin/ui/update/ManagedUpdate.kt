package dev.hermeskotlin.ui.update

import androidx.compose.runtime.staticCompositionLocalOf

enum class ManagedUpdatePhase { Idle, Available, Ready, Updating, NoUpdate, Failed, Cancelled, Restarting }

enum class ManagedUpdateFailure { Unavailable, Denied, SystemUpdateRequired, Failed, Uncertain, RestartFailed }

data class ManagedUpdateState(
    val phase: ManagedUpdatePhase = ManagedUpdatePhase.Idle,
    val progress: Int? = null,
    val canRestart: Boolean = false,
    val failure: ManagedUpdateFailure? = null,
)

class ManagedUpdateOffer(
    val state: ManagedUpdateState,
    val onUpdate: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

val LocalManagedUpdateOffer = staticCompositionLocalOf<ManagedUpdateOffer?> { null }
