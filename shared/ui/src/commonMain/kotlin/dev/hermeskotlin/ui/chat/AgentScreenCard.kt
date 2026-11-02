package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ScreenView
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.screenViews
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * What the app lends the agent's screens: the screens a `phone_show_ui` call waits on now, and where it's said
 * that one is on screen ([onShown]), a tap ([onAnswer]), a screen that failed or ended ([onFail]) and what a card
 * draws, for a later call that updates it ([onDrawn]) go. Each takes the chat's stored id first.
 */
internal class AgentScreenContext(
    val live: List<AgentScreens.Live>,
    val onShown: (chat: String?, surfaceIds: Set<String>) -> Unit,
    /** True when a call waiting on the screen took the tap. */
    val onAnswer: (chat: String?, surfaceId: String, result: JsonElement) -> Boolean,
    val onFail: (chat: String?, surfaceId: String, message: String) -> Unit,
    val onDrawn: (chat: String?, screen: AgentScreen) -> Unit = { _, _ -> },
    val took: (chat: String?, screen: AgentScreen) -> Boolean = { _, _ -> true },
) {
    companion object {
        val None = AgentScreenContext(emptyList(), { _, _ -> }, { _, _, _ -> false }, { _, _, _ -> })
    }
}

internal val LocalAgentScreens = compositionLocalOf { AgentScreenContext.None }

/** The stored id of the chat the screens are drawn in, so a tap answers that chat's call only. */
internal val LocalAgentScreenChat = compositionLocalOf<String?> { null }

/**
 * Whether this platform draws agent screens. Off, a `phone_show_ui` call is only its line among the tools.
 */
internal expect val drawsAgentScreens: Boolean

/**
 * Where a tap on a screen goes when no call waits on it: the chat's next message, from the user. Null where
 * taps can't be sent (a preview, a chat that isn't open).
 */
internal val LocalAgentScreenSend = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * Draws [screen], the call [key]'s. Taps go to [onAnswer] only while [takesTaps]; at other times it's a picture of
 * what the agent showed. A drawing error goes to [onFail] once [live] (a call waits on it), even if it came before.
 */
@Composable
internal expect fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    takesTaps: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * The chat's screens ([byCall], from [screenViews]) and the calls in its last message, once finished ([newest]):
 * a tap on a screen that one of them made or changed, and no call waits on, is sent as the next message. A
 * message after it (the user's own, or a new turn) means the conversation moved on.
 */
internal class ScreenViews(val byCall: Map<String, ScreenView>, val newest: Set<String>) {
    companion object {
        fun of(messages: List<ChatMessage>, took: (AgentScreen) -> Boolean = { true }) =
            ScreenViews(screenViews(calls(messages), took), newest(messages))

        /** Every tool call in the chat, in order: what the screens are made from. */
        fun calls(messages: List<ChatMessage>): List<ToolActivity> =
            messages.flatMap { (it as? ChatMessage.Assistant)?.tools.orEmpty() }

        /** The calls of the newest reply, once it finished. */
        fun newest(messages: List<ChatMessage>): Set<String> =
            (messages.lastOrNull() as? ChatMessage.Assistant)?.takeUnless { it.streaming }?.tools.orEmpty().mapTo(HashSet()) { it.id }
    }
}

internal val LocalScreenViews = compositionLocalOf { ScreenViews(emptyMap(), emptySet()) }

/**
 * A screen the agent drew with `phone_show_ui`, under the reply that made it, with what later calls changed on
 * it: a card, with an accent edge while it waits. The newest of those calls is the one that waits or ended.
 */
@Composable
internal fun AgentScreenCard(view: ScreenView, newest: Boolean) {
    val screen = view.screen
    val call = view.latest
    if (!drawsAgentScreens) return
    val context = LocalAgentScreens.current
    val chat = LocalAgentScreenChat.current
    // So a call that updates it is checked against what's here, even one made after Kyllene restarted.
    LaunchedEffect(chat, screen.messages) { context.onDrawn(chat, screen) }
    val waits = screen.wait && call.running
    val live = waits && context.live.any { waiting -> screen.surfaceIds.any { waiting.matches(chat, it) } }
    // Saved, so it outlives the card scrolling out of the list.
    var wasLive by rememberSaveable(call.id) { mutableStateOf(false) }
    var tappedLive by rememberSaveable(call.id) { mutableStateOf(false) }
    if (live) wasLive = true
    // Only while the app is in front: in the background nobody sees the screen, and the call should know.
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (waits && !wasLive && resumed.isAtLeast(Lifecycle.State.RESUMED)) {
        // Said again and again while the call runs: the phone may hear of the call after the card is drawn.
        LaunchedEffect(chat, screen.surfaceIds) {
            while (true) {
                context.onShown(chat, screen.surfaceIds)
                delay(SHOWN_EVERY_MS)
            }
        }
    }
    // The call ended unanswered here (stopped, or the agent gave up): the phone stops waiting too. Only a call
    // seen running: an old one in the history must not end a new call that reuses its surface id.
    var wasRunning by rememberSaveable(call.id) { mutableStateOf(call.running) }
    LaunchedEffect(call.running) {
        if (wasRunning && !call.running) screen.surfaceIds.forEach { context.onFail(chat, it, ENDED) }
        wasRunning = call.running
    }
    // No call waits on it (the agent didn't wait, or the call ended without an answer): on the newest reply, a tap
    // still reaches the agent, as the user's next message. Once only, and never for a screen already answered.
    val send = LocalAgentScreenSend.current
    // Read when the tap comes, not as composed: taps come one after another, faster than the card recomposes.
    val sent = rememberSaveable(call.id) { mutableStateOf(false) }
    // Answered: the call returned a tap, or one was tapped here while it waited (its result may not be in yet). A
    // call that failed wasn't, even after a tap (the answer was too long, say): the user can tap again.
    val answered = !call.failed && (tappedLive || call.output.orEmpty().contains("\"event\""))
    val replies = newest && send != null && !call.running && !answered && !sent.value
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentScreenSurface(
            // The card's own call: a later update is drawn on the same screen, keeping what the user typed.
            key = view.owner.id,
            screen = screen,
            live = live,
            takesTaps = live || replies,
            onAnswer = { surface, result ->
                if (live) {
                    // Only a tap a call took: one just after the turn stopped went nowhere, and may be tapped again.
                    if (context.onAnswer(chat, surface, result)) tappedLive = true
                } else if (replies && send != null && !sent.value) {
                    sent.value = true
                    send(screenTapMessage(result))
                }
            },
            onFail = { surface, message -> context.onFail(chat, surface, message) },
            modifier = Modifier
                .fillMaxWidth()
                // Bounded, since the chat is a lazy list; a taller screen scrolls inside the card.
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(Theme[colors][surface])
                .border(1.dp, if (live || replies) Theme[colors][accentSoft] else Theme[colors][stroke], shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        val note = when {
            live -> "Waiting for your answer"
            waits && !wasLive -> "Opening…"
            replies -> "A tap answers in the chat"
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = Theme[typography][caption],
                color = if (live || replies) Theme[colors][accentText] else Theme[colors][textTertiary],
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * A tap on a screen no call waits on, as the user's message to the agent: the action's name, and what it and
 * the screen's form carried, so the agent reads the same as a waiting call would have returned.
 */
internal fun screenTapMessage(result: JsonElement): String {
    val tap = result as? JsonObject ?: return "Tapped a button on the screen you showed."
    val event = (tap["event"] as? JsonPrimitive)?.contentOrNull ?: "a button"
    val more = buildJsonObject {
        (tap["context"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("context", it) }
        (tap["data"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("data", it) }
    }
    val said = "Tapped “$event” on the screen you showed"
    return if (more.isEmpty()) "$said." else "$said: $more"
}

private const val SHOWN_EVERY_MS = 2_000L
private const val ENDED = "The call ended before the user answered."
