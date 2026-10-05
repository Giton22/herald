package dev.hermeskotlin.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.BOT_SWATCHES
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotDetails
import dev.hermeskotlin.core.bots.BotDraft
import dev.hermeskotlin.core.bots.BotMeta
import dev.hermeskotlin.core.bots.BotShape
import dev.hermeskotlin.core.bots.botIdentity
import dev.hermeskotlin.core.bots.botLook
import dev.hermeskotlin.core.bots.cssHex
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Making a bot or changing one, as a page over the app: its face (shape and colour), name, role,
 * what it's for, and optionally the persona it runs with. A new bot's name becomes its profile id; an
 * edit sends only what changed, so a change made on Desktop meanwhile isn't undone.
 */
@Composable
fun BotEditor(
    /** The bot to change, or null to make a new one. */
    bot: Bot?,
    /** Profile ids already in use. */
    taken: Set<String>,
    busy: String?,
    loadDetails: suspend () -> BotDetails?,
    onBack: () -> Unit,
    onCreate: (BotDraft) -> Unit,
    onSave: (description: String?, soul: String?, look: Map<String, JsonElement?>) -> Unit,
) {
    PlatformBackHandler(enabled = busy == null, onBack = onBack)
    val meta = bot?.meta ?: BotMeta()
    val name = rememberTextFieldState()
    val title = rememberTextFieldState(meta.title.orEmpty())
    val description = rememberTextFieldState()
    val soul = rememberTextFieldState()
    var details by remember { mutableStateOf<BotDetails?>(null) }
    // The look the bot has now, so picking what it already wears changes nothing.
    val current = remember(bot) { bot?.let(::botLook) }
    var shape by remember { mutableStateOf(current?.shape) }
    var color by remember { mutableStateOf(current?.color) }
    var typedName by remember { mutableStateOf("") }
    LaunchedEffect(name) { snapshotFlow { name.text.toString() }.collect { typedName = it } }
    LaunchedEffect(bot) {
        if (bot == null) return@LaunchedEffect
        loadDetails()?.let { loaded ->
            details = loaded
            description.setTextAndPlaceCursorAtEnd(loaded.description)
            soul.setTextAndPlaceCursorAtEnd(loaded.soul)
        }
    }
    val (slug, autoTitle) = remember(typedName) { botIdentity(typedName, "") }
    val profile = bot?.name ?: slug
    val problem = if (bot == null && typedName.isNotBlank()) BotDraft(slug).problem(taken) else null
    val preview = remember(profile, shape, color) {
        Bot(
            name = profile.ifEmpty { "agent" },
            uiMeta = JsonObject(
                mapOf(
                    BotMeta.KEY to JsonObject(
                        buildMap {
                            shape?.let { put("shape", JsonPrimitive(it.name.lowercase())) }
                            color?.let { put("color", JsonPrimitive(cssHex(it))) }
                            put("custom", JsonPrimitive(shape != null || color != null))
                        },
                    ),
                ),
            ),
        )
    }

    Box(
        Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader(if (bot == null) "New bot" else "Edit ${bot.label}", onBack = { if (busy == null) onBack() })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    BotAvatar(preview, picture = null, size = 72.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Face")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BotShape.entries.forEach { option ->
                            val look = Bot(name = profile.ifEmpty { "agent" }, uiMeta = JsonObject(mapOf(BotMeta.KEY to JsonObject(mapOf(
                                "shape" to JsonPrimitive(option.name.lowercase()),
                                "custom" to JsonPrimitive(true),
                            ) + (color?.let { mapOf("color" to JsonPrimitive(cssHex(it))) } ?: emptyMap())))))
                            Choice(selected = option == (shape ?: botLook(preview).shape), label = option.name, onClick = { shape = option }) {
                                BotAvatar(look, picture = null, size = 32.dp)
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BOT_SWATCHES.forEach { swatch ->
                            Choice(selected = color == swatch, label = cssHex(swatch), onClick = { color = swatch }) {
                                Box(Modifier.size(28.dp).background(Color(0xFF000000 or swatch.toLong()), CircleShape))
                            }
                        }
                    }
                }
                if (bot == null) {
                    TextField(
                        name,
                        label = "Name",
                        placeholder = "e.g. Researcher",
                        supportingText = when {
                            typedName.isBlank() -> "Becomes the bot's profile on the gateway."
                            problem == null -> "Profile: $slug"
                            else -> null
                        },
                        error = problem,
                    )
                }
                TextField(title, label = "Title", placeholder = autoTitle.ifEmpty { "What it is, e.g. Research analyst" })
                TextField(
                    description,
                    label = "Description",
                    placeholder = "What should this bot help with?",
                    singleLine = false,
                    maxLines = 5,
                )
                TextField(
                    soul,
                    label = "Persona (SOUL.md)",
                    placeholder = if (bot == null) "Optional. Left empty, one is written from the name, title and description." else null,
                    supportingText = "How the bot thinks of itself and works, read at the start of every chat.",
                    singleLine = false,
                    maxLines = 12,
                )
                busy?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
                if (bot == null) {
                    Button(
                        "Make bot",
                        onClick = {
                            onCreate(
                                BotDraft(
                                    profile = slug,
                                    title = title.text.toString().trim().ifEmpty { autoTitle },
                                    description = description.text.toString().trim(),
                                    shape = shape,
                                    color = color?.let(::cssHex),
                                    soul = soul.text.toString().trim(),
                                ),
                            )
                        },
                        enabled = busy == null && typedName.isNotBlank() && problem == null,
                        loading = busy != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Button(
                        "Save",
                        onClick = {
                            val look = buildMap<String, JsonElement?> {
                                val newTitle = title.text.toString().trim()
                                if (newTitle != meta.title.orEmpty()) put("title", JsonPrimitive(newTitle))
                                if (shape != current?.shape) shape?.let { put("shape", JsonPrimitive(it.name.lowercase())) }
                                if (color != current?.color) color?.let { put("color", JsonPrimitive(cssHex(it))) }
                                if (shape != current?.shape || color != current?.color) {
                                    put("custom", JsonPrimitive(true))
                                    put("imageKind", JsonPrimitive("shape"))
                                }
                            }
                            val loaded = details
                            onSave(
                                description.text.toString().trim().takeIf { loaded != null && it != loaded.description.trim() },
                                soul.text.toString().trim().takeIf { loaded != null && it.isNotEmpty() && it != loaded.soul.trim() },
                                look,
                            )
                        },
                        enabled = busy == null,
                        loading = busy != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (bot != null && details == null) {
                    Text("Reading ${bot.label}'s description and persona…", style = Theme[typography][caption], color = Theme[colors][textTertiary])
                }
            }
        }
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Theme[colors][accent] else Theme[colors][stroke], shape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
