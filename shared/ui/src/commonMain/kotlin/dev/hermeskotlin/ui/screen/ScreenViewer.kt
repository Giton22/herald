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

    /**
     * The view was zoomed: [factor] is how much bigger the screen is than the page, and 1 is the whole
     * screen. The user says so with a pinch, which the viewer is the only one that sees.
     */
    data class Zoomed(val factor: Float) : ScreenViewerEvent
}

/** What the app can ask of the viewer it opened. */
interface ScreenViewerHandle {
    /** Whether the user may drive the screen. The gateway has the last word on that. */
    fun viewOnly(value: Boolean)

    /** A key on the bot's screen: pressed or released. */
    fun key(keysym: Int, down: Boolean)

    /** How much of the screen there is to look at: 1 is the whole screen, 8 is eight times closer. */
    fun setZoom(factor: Float)
}

/**
 * How far the app's own browser has come.
 *
 * On the desktop the browser is the runtime's own Chromium, and it takes a moment to start. A browser that
 * is still starting is not a failure: the page has not had its chance yet, and saying otherwise takes the
 * screen away before it was ever opened.
 */
enum class ScreenBrowserStage {
    /** The browser is coming up. */
    Starting,

    /** The browser is up, and the page may open in it. */
    Ready,

    /** The browser is not coming up. */
    Failed,
}

/**
 * What the browser's state means for the screen, or null when it means nothing yet.
 *
 * Only a browser that will not start is a failure.
 */
fun screenBrowserEvent(stage: ScreenBrowserStage): ScreenViewerEvent? = when (stage) {
    ScreenBrowserStage.Starting, ScreenBrowserStage.Ready -> null
    ScreenBrowserStage.Failed -> ScreenViewerEvent.Failed("The browser didn't start.")
}

/**
 * The event, if it is about the screen in hand.
 *
 * A viewer that was replaced — another bot, another gateway, another ticket — says nothing about the
 * screen in front of the user now, so what it says is dropped.
 */
fun screenEventAbout(stream: String, current: String, event: ScreenViewerEvent): ScreenViewerEvent? =
    if (stream == current) event else null

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
    val zoom = parts.firstOrNull { it.startsWith("zoom=") }?.removePrefix("zoom=")?.toFloatOrNull()
    return when (state) {
        "connected" -> ScreenViewerEvent.Connected
        "closed" -> ScreenViewerEvent.Closed
        "failed" -> ScreenViewerEvent.Failed(screenFailure(reason))
        "zoom" -> zoom?.let { ScreenViewerEvent.Zoomed(it) }
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
