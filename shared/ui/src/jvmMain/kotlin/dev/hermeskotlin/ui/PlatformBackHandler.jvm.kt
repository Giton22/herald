package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A window's back: Escape. The window hands each Escape to [back], which runs the newest handler that's on,
 * the way Android's back reaches the innermost screen first. One per window, so Escape never closes something
 * in another one.
 */
class DesktopBack {
    private val handlers = mutableListOf<Handler>()

    internal class Handler(var enabled: Boolean, var onBack: () -> Unit)

    internal fun add(handler: Handler) {
        handlers += handler
    }

    internal fun remove(handler: Handler) {
        handlers -= handler
    }

    /** Runs the innermost enabled handler; false when none took it. */
    fun back(): Boolean {
        val handler = handlers.lastOrNull { it.enabled } ?: return false
        handler.onBack()
        return true
    }
}

/** The window's back handlers, as [LocalShortcuts] is its shortcuts. */
val LocalDesktopBack = staticCompositionLocalOf { DesktopBack() }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val back = LocalDesktopBack.current
    val latest = rememberUpdatedState(onBack)
    val handler = remember(back) { DesktopBack.Handler(enabled) { latest.value() } }
    handler.enabled = enabled
    DisposableEffect(back, handler) {
        back.add(handler)
        onDispose { back.remove(handler) }
    }
}
