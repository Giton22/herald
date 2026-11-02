package dev.hermeskotlin.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** Where the window was and how big, so it opens there again, as Hermes Desktop's does. */
data class SavedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean) {

    fun encode() = "$x,$y,$width,$height,$maximized"

    /**
     * Whether enough of its title strip is on a screen there is now to grab it (a monitor may have gone): a
     * sliver at an edge isn't, as a mouse could hardly find it.
     */
    fun onScreen(screens: List<Rectangle>): Boolean = screens.any { screen ->
        val strip = screen.intersection(Rectangle(x, y + 8, width, 32))
        !strip.isEmpty && strip.width >= MIN_GRAB_WIDTH && strip.height >= MIN_GRAB_HEIGHT
    }

    companion object {
        fun decode(text: String): SavedWindow? {
            val parts = text.split(',')
            if (parts.size != 5) return null
            val (x, y, w, h) = parts.take(4).map { it.toIntOrNull() ?: return null }
            if (w < MIN_EDGE || h < MIN_EDGE) return null
            return SavedWindow(x, y, w, h, parts[4] == "true")
        }

        private const val MIN_EDGE = 200
        private const val MIN_GRAB_WIDTH = 100
        private const val MIN_GRAB_HEIGHT = 20
    }
}

object WindowMemory {
    private const val KEY = "desktop.window"

    /** The last chat window's bounds: a chat popped out opens as big as the one before it. */
    const val CHAT_KEY = "desktop.chatWindow"

    /** The window as last left, read before it opens; null the first time, or when it would open off every screen. */
    fun read(store: KeyValueStore, key: String = KEY): SavedWindow? {
        val saved = runCatching { runBlocking { store.get(key) } }.getOrNull()?.let(SavedWindow::decode) ?: return null
        val screens = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())
        return saved.takeIf { it.onScreen(screens) }
    }

    /** The state a window opens with: where it was, or [default] centred. */
    fun initial(saved: SavedWindow?, default: DpSize): Triple<WindowPlacement, WindowPosition, DpSize> = when (saved) {
        null -> Triple(WindowPlacement.Floating, WindowPosition.PlatformDefault, default)
        else -> Triple(
            if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            WindowPosition(saved.x.dp, saved.y.dp),
            DpSize(saved.width.dp, saved.height.dp),
        )
    }

    /** Keeps [state] saved as it moves and resizes, a moment after it settles. A maximized window keeps its floating bounds. */
    @OptIn(FlowPreview::class)
    @Composable
    fun Remember(state: WindowState, store: KeyValueStore, saved: SavedWindow?, key: String = KEY) {
        LaunchedEffect(state) {
            var floating: SavedWindow? = saved
            snapshotFlow { Triple(state.placement, state.position, state.size) }
                .distinctUntilChanged()
                .debounce(500)
                .collect { (placement, position, size) ->
                    val maximized = placement == WindowPlacement.Maximized
                    if (placement == WindowPlacement.Floating && position.isSpecified) {
                        floating = SavedWindow(position.x.value.toInt(), position.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt(), false)
                    }
                    val bounds = floating ?: return@collect
                    runCatching { store.put(key, bounds.copy(maximized = maximized).encode()) }
                }
        }
    }
}
