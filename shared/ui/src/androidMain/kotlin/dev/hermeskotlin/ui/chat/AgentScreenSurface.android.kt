package dev.hermeskotlin.ui.chat

import android.content.Context
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.radiusXLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

internal actual val drawsAgentScreens: Boolean = true

@Composable
internal actual fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    takesTaps: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val drawn = remember(key, screen) { DrawnScreens.of(context, key, screen) }
    val answer by rememberUpdatedState(onAnswer)
    val fail by rememberUpdatedState(onFail)
    // Taps go somewhere only when the card says so; at other times the screen is what the agent showed.
    DisposableEffect(drawn, takesTaps) {
        drawn.onTap = if (takesTaps) { tap -> answer(tap.surfaceId, tap.toAnswer()) } else null
        onDispose { drawn.onTap = null }
    }
    val error by drawn.error.collectAsStateWithLifecycle()
    LaunchedEffect(live, error) {
        val (surface, message) = error ?: return@LaunchedEffect
        if (live) fail(surface, message)
    }
    val surfaces by drawn.processor.activeSurfaces.collectAsStateWithLifecycle()
    val accents = remember(screen) { primaryColors(screen) }
    val textColor = Theme[colors][text]
    MaterialTheme(colorScheme = materialColors(), shapes = materialShapes(), typography = materialType()) {
        // Material's text takes its color from a Surface around it; there's none here, so it's given the app's.
        CompositionLocalProvider(LocalContentColor provides textColor) {
            Box(modifier) {
                // Taking no taps: presses are held back before the screen sees them, so a drag still scrolls the
                // card and the chat, and a player still plays. TalkBack still reads it; a click it sends goes
                // nowhere, since onTap is off.
                val players = remember { mutableStateMapOf<Any, Rect>() }
                var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val gate = if (takesTaps) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes
                                .filter { it.changedToDownIgnoreConsumed() || it.changedToUpIgnoreConsumed() }
                                .filterNot { change ->
                                    val at = placed?.takeIf { it.isAttached }?.localToWindow(change.position)
                                    at != null && players.values.any { it.contains(at) }
                                }
                                .forEach { it.consume() }
                        }
                    }
                }
                Column(
                    Modifier.onGloballyPositioned { placed = it }.then(gate).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompositionLocalProvider(LocalPlayerAreas provides players) {
                        surfaces.forEach { key(it.id) { AgentColor(accents[it.id]) { A2uiSurface(surfaceModel = it) } } }
                    }
                    if (surfaces.isEmpty() && screen.messages.any { "\"deleteSurface\"" in it }) {
                        Text(
                            "The agent closed this screen.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A screen's processor, run apart from the chat's list: scrolled out of sight and back, the screen keeps what the
 * user typed or picked. The first error drawing what a call sent is kept for the agent until the screen is waited on;
 * a later call's update starts afresh.
 */
private class DrawnScreen(context: Context, private val screen: AgentScreen) {
    val error = MutableStateFlow<Pair<String, String>?>(null)

    /** Its processing threw, so nothing more is drawn on it: the next look at the screen makes a new one. */
    @Volatile var broken = false
        private set

    // Whatever the screen's processing throws is this screen's error, never the app's.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            broken = true
            error.value = screen.surfaceIds.first() to "The screen couldn't be drawn: ${e.message ?: e::class.simpleName}"
        },
    )
    val processor: A2uiMessageProcessor = A2uiMessageProcessor(catalogs = listOf(catalog(context)))
    private val parser = A2uiMessageParser()

    @Volatile var onTap: ((A2uiClientEventMessage) -> Unit)? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { message ->
                when (message) {
                    is A2uiClientEventMessage -> onTap?.invoke(message)
                    is A2uiClientErrorMessage -> error.compareAndSet(null, screen.surfaceOf(message) to message.describe())
                }
            }
        }
        scope.launch { processor.collectMessages() }
        feed(screen.messages)
    }

    /**
     * More messages for the screen: an update from a later call, drawn over what's there. An error the earlier
     * messages had was said already; the update is judged on its own.
     */
    fun feed(messages: List<String>) {
        if (messages.isEmpty()) return
        error.value = null
        messages.forEach { processor.processInput(parser, it) }
    }

    fun close() = scope.cancel()
}

/** The screens drawn lately, by tool call: a few, the oldest let go. */
private object DrawnScreens {
    private const val MAX = 12
    private val drawn = object : LinkedHashMap<String, Pair<AgentScreen, DrawnScreen>>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<AgentScreen, DrawnScreen>>): Boolean =
            (size > MAX).also { if (it) eldest.value.second.close() }
    }

    fun of(context: Context, key: String, screen: AgentScreen): DrawnScreen = synchronized(drawn) {
        val (seen, kept) = drawn[key] ?: (null to null)
        // The same screen, or the same with more messages after (a later call updated it): only those are drawn,
        // over what's there.
        if (seen != null && kept != null && !kept.broken && screen.messages.size >= seen.messages.size &&
            screen.messages.subList(0, seen.messages.size) == seen.messages
        ) {
            kept.feed(screen.messages.drop(seen.messages.size))
            drawn[key] = screen to kept
            return kept
        }
        DrawnScreen(context.applicationContext, screen).also { new ->
                drawn.put(key, screen to new)?.second?.close()
            }
    }
}

/**
 * Why [screen]'s messages aren't a screen Kyllene can draw, in words for the agent to fix them; null when they are.
 * Each must parse, and together they must make their surfaces with no error (an unknown catalog, a missing root):
 * found here, before the call waits, rather than once the user looks.
 */
suspend fun agentScreenProblem(context: Context, screen: AgentScreen): String? {
    // Before anything draws it: a layout that holds itself would never finish drawing.
    screen.layoutProblem?.let { return it }
    val parser = A2uiMessageParser()
    screen.messages.forEachIndexed { i, message ->
        try {
            parser.parse(message)
        } catch (e: A2uiException) {
            return "Message ${i + 1} isn't A2UI: ${e.message}"
        }
    }
    // A screen the messages close at the end has nothing left to show.
    val closed = screen.messages.mapNotNullTo(HashSet()) { message ->
        (runCatching { HermesJson.parseToJsonElement(message) }.getOrNull() as? JsonObject)
            ?.let { it["deleteSurface"] as? JsonObject }?.let { (it["surfaceId"] as? JsonPrimitive)?.contentOrNull }
    }
    val shown = screen.surfaceIds - closed
    // What the processing throws is a screen it can't draw, said to the agent, not a crash of the tool.
    return try {
        drawsWithoutError(context, screen, shown)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "The screen couldn't be drawn: ${e.message ?: e::class.simpleName}"
    }
}

private suspend fun drawsWithoutError(context: Context, screen: AgentScreen, shown: Set<String>): String? =
    coroutineScope {
        val parser = A2uiMessageParser()
        val processor = A2uiMessageProcessor(catalogs = listOf(catalog(context.applicationContext)))
        val errors = MutableStateFlow<String?>(null)
        val listen = launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { if (it is A2uiClientErrorMessage) errors.compareAndSet(null, it.describe()) }
        }
        val run = launch { processor.collectMessages() }
        screen.messages.forEach { processor.processInput(parser, it) }
        withTimeoutOrNull(CHECK_MS) {
            while (errors.value == null && !processor.activeSurfaces.value.map { it.id }.containsAll(shown)) delay(20)
            // A moment more for an error about a surface just made.
            delay(50)
        }
        listen.cancel()
        run.cancel()
        errors.value
    }

private const val CHECK_MS = 1_500L

private fun AgentScreen.surfaceOf(error: A2uiClientErrorMessage): String =
    // A message that didn't parse belongs to no surface yet; it's still this screen's.
    error.surfaceId.takeIf { it in surfaceIds } ?: surfaceIds.first()

private fun A2uiClientErrorMessage.describe() = "$code: $message"

/**
 * The Basic Catalog in Material 3, with the app's cards, buttons, fields and rows (AgentScreenComponents.kt) and
 * its pictures, video and audio (AgentScreenMedia.kt).
 */
private fun catalog(context: Context): A2uiCatalog = materialA2uiBasicCatalogV1(
    image = MaterialA2uiBasicCatalogV1Defaults.image(ScreenImages),
    video = MaterialA2uiBasicCatalogV1Defaults.video(ScreenVideos),
    audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer(ScreenAudio),
    functions = A2uiBasicCatalogV1.Functions(context),
    row = ScreenRow,
    list = ScreenList,
    card = ScreenCard,
    button = ScreenButton,
    textField = ScreenTextField,
)

/** The app's colors in Material's roles, so the catalog's components look like the rest of the chat. */
@Composable
private fun materialColors(): ColorScheme {
    val dark = Theme[colors][background].luminance() < 0.5f
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Theme[colors][accent],
        onPrimary = Theme[colors][onAccent],
        primaryContainer = Theme[colors][accentSoft],
        onPrimaryContainer = Theme[colors][accentText],
        secondary = Theme[colors][accent],
        onSecondary = Theme[colors][onAccent],
        secondaryContainer = Theme[colors][accentSoft],
        onSecondaryContainer = Theme[colors][accentText],
        background = Theme[colors][surface],
        onBackground = Theme[colors][text],
        surface = Theme[colors][surface],
        onSurface = Theme[colors][text],
        surfaceVariant = Theme[colors][surface2],
        onSurfaceVariant = Theme[colors][textSecondary],
        surfaceContainerLowest = Theme[colors][surface],
        surfaceContainerLow = Theme[colors][surface2],
        surfaceContainer = Theme[colors][surface2],
        surfaceContainerHigh = Theme[colors][surface3],
        surfaceContainerHighest = Theme[colors][surface3],
        outline = Theme[colors][strokeStrong],
        outlineVariant = Theme[colors][stroke],
        error = Theme[colors][danger],
        errorContainer = Theme[colors][dangerSoft],
        onErrorContainer = Theme[colors][danger],
    )
}

/**
 * The app's type steps in Material's roles, sized for a card in a reply rather than a screen of its own. The
 * catalog's Text takes h1-h3 from the headline roles and h4-h5 from the title ones: only h1 gets the title size,
 * and the rest are no bigger than a heading, so a screen with three headings doesn't shout.
 */
@Composable
private fun materialType(): Typography {
    val display = Theme[typography][display]
    val title = Theme[typography][title]
    val heading = Theme[typography][heading]
    val body = Theme[typography][body]
    val small = Theme[typography][bodySmall]
    val label = Theme[typography][label]
    val caption = Theme[typography][caption]
    val strong = body.copy(fontWeight = FontWeight.SemiBold)
    return Typography(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = title, headlineMedium = heading, headlineSmall = heading,
        titleLarge = heading, titleMedium = strong, titleSmall = label,
        bodyLarge = body, bodyMedium = body, bodySmall = small,
        labelLarge = label, labelMedium = label, labelSmall = caption,
    )
}

/**
 * The color each surface's `createSurface` asked for (`theme.primaryColor`, `#RGB`, `#RRGGBB` or `#AARRGGBB`),
 * by surface id: what the screen highlights with in place of the app's accent.
 */
internal fun primaryColors(screen: AgentScreen): Map<String, Color> = screen.messages.mapNotNull { message ->
    val create = (runCatching { HermesJson.parseToJsonElement(message) }.getOrNull() as? JsonObject)
        ?.get("createSurface") as? JsonObject ?: return@mapNotNull null
    val id = (create["surfaceId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    val hex = ((create["theme"] as? JsonObject)?.get("primaryColor") as? JsonPrimitive)?.contentOrNull
    hexColor(hex)?.let { id to it }
}.toMap()

internal fun hexColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#")?.takeIf { it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        ?: return null
    val argb = when (digits.length) {
        3 -> "FF" + digits.map { "$it$it" }.joinToString("")
        6 -> "FF$digits"
        8 -> digits
        else -> return null
    }
    return Color(argb.toLong(16).toInt())
}

/** [content] in the agent's [color], when it chose one: its main buttons, picked chips and focus take it. */
@Composable
private fun AgentColor(color: Color?, content: @Composable () -> Unit) {
    if (color == null) return content()
    val on = if (color.luminance() > 0.5f) Color.Black else Color.White
    val soft = color.copy(alpha = 0.22f).compositeOver(Theme[colors][surface])
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = color, onPrimary = on, secondary = color, onSecondary = on,
            primaryContainer = soft, secondaryContainer = soft,
            onPrimaryContainer = Theme[colors][text], onSecondaryContainer = Theme[colors][text],
        ),
        content = content,
    )
}

/** The app's corner radii in Material's shape steps: fields, menus and cards round off like the rest of the chat. */
@Composable
private fun materialShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(Theme[radii][radiusSmall]),
    small = RoundedCornerShape(Theme[radii][radiusSmall]),
    medium = RoundedCornerShape(Theme[radii][radiusMedium]),
    large = RoundedCornerShape(Theme[radii][radiusLarge]),
    extraLarge = RoundedCornerShape(Theme[radii][radiusXLarge]),
)

/** What the agent reads back from a tap: which action, on which component, and what the screen's form holds. */
private fun A2uiClientEventMessage.toAnswer(): JsonElement = buildJsonObject {
    put("event", type)
    put("surface_id", surfaceId)
    put("component_id", componentId)
    put("timestamp", Instant.ofEpochMilli(timestamp).toString())
    put("context", toJson(context))
    clientDataModel?.surfaces?.get(surfaceId)?.let { put("data", toJson(it)) }
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map(::toJson))
    is Array<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}
