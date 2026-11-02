package dev.hermeskotlin.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the viewer tells the app about the screen it was opened for. */
sealed interface ScreenViewerEvent {
    /** The screen is showing. */
    data object Connected : ScreenViewerEvent

    /** It could not be opened, or it closed again. [reason] says why, in the user's words. */
    data class Failed(val reason: String) : ScreenViewerEvent

    /** The viewer was let go, without the screen having failed. */
    data object Closed : ScreenViewerEvent
}

/** What the app can ask of the viewer it opened. */
interface ScreenViewerHandle {
    /** Whether the user may drive the screen. The gateway has the last word on that. */
    fun viewOnly(value: Boolean)

    /** A key on the bot's screen: pressed or released. */
    fun key(keysym: Int, down: Boolean)
}

/**
 * The bot's screen, drawn by a browser.
 *
 * The page is the app's own (see `vendor/screen-viewer`), served from a `file:` address so that it has no
 * origin of its own, which is what the gateway accepts for a display stream. The page opens the stream
 * itself: the app hands it the address, which carries the gateway's single-use ticket, and nothing else —
 * no gateway credential ever reaches a page.
 *
 * [viewOnly] says whether the user may drive the screen: the gateway decides, and the page is told so it
 * does not accept what the user does while somebody else holds the screen. [onEvent] says how the screen is
 * doing, so the pane can show a spinner, a failure or the screen itself.
 */
@Composable
expect fun ScreenViewer(
    streamUrl: String,
    viewOnly: Boolean,
    onHandle: (ScreenViewerHandle) -> Unit,
    onEvent: (ScreenViewerEvent) -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * What a `herald://screen` address says, or null when it says nothing this app knows.
 *
 * The page may only say how the screen is doing: a state and, with a failure, one of a few codes. Nothing
 * has to be decoded, so the address stays as plain as it is.
 */
fun screenEvent(url: String): ScreenViewerEvent? {
    if (!url.startsWith(SCREEN_ADDRESS)) return null
    val query = url.substringAfter('?', "")
    val parts = query.split('&')
    val state = parts.firstOrNull { it.startsWith("state=") }?.removePrefix("state=") ?: return null
    val reason = parts.firstOrNull { it.startsWith("reason=") }?.removePrefix("reason=")
    return when (state) {
        "connected" -> ScreenViewerEvent.Connected
        "closed" -> ScreenViewerEvent.Closed
        "failed" -> ScreenViewerEvent.Failed(screenFailure(reason))
        else -> null
    }
}

/** What the page says went wrong, in the user's words. */
internal fun screenFailure(code: String?): String = when (code) {
    "too-old" -> "This device's browser is too old to show the screen."
    "no-address" -> "The app did not say where the screen is."
    "refused" -> "The gateway refused the connection."
    "closed" -> "The screen closed."
    else -> "The screen could not be opened."
}

/** Where the page asks the app how the screen is doing. */
private const val SCREEN_ADDRESS = "herald://screen"
