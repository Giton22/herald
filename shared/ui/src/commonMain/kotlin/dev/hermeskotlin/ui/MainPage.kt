package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the sidebar's pages (Scheduled, Insights, Capabilities…) show on a desktop: in the chat's place, beside
 * the docked sidebar, as a desktop app opens its sections. A phone, or a window too narrow to dock the sidebar,
 * keeps them in the sidebar ([enabled] false).
 */
@Stable
class MainPageSlot {
    /** Pages go to the main area: the app sets it while the sidebar is docked on a desktop. */
    var enabled by mutableStateOf(false)

    /** The page shown in the chat's place, if any. */
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    /** The page lays out panes side by side (a list and what's open in it), so it may take more than a page's width. */
    var wide by mutableStateOf(false)
        private set

    /** Which page [content] is: "scheduled", "config", "routines:<bot>"… */
    var key: String? = null
        private set
    private var onClose: (() -> Unit)? = null

    /** Shows [page] (named [key]); [close] is how the sidebar puts it away. */
    fun show(key: String, page: @Composable () -> Unit, close: () -> Unit, wide: Boolean = false) {
        this.key = key
        onClose = close
        this.wide = wide
        content = page
    }

    /** The sidebar put [key] away. */
    fun clear(key: String) {
        if (this.key != key) return
        this.key = null
        onClose = null
        content = null
    }

    /** Puts the page away, as its back arrow does: a chat opened from elsewhere takes its place. */
    fun close() {
        onClose?.invoke()
    }
}
