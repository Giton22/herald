package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.designsystem.components.Tooltip
import dev.hermeskotlin.designsystem.components.handCursor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import dev.hermeskotlin.designsystem.components.VerticalScrollbar
import androidx.compose.ui.focus.FocusRequester
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.inbox.Words
import dev.hermeskotlin.ui.inbox.text
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.models.displayProviderName
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.text
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
 * The faces or colours to pick from: one row that scrolls sideways on a phone, and rows that wrap on a desktop,
 * where a mouse wheel can't scroll sideways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    if (isDesktop) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

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
    busy: Words?,
    loadDetails: suspend () -> BotDetails?,
    onBack: () -> Unit,
    onCreate: (BotDraft) -> Unit,
    onSave: (description: String?, soul: String?, look: Map<String, JsonElement?>) -> Unit,
    /** The bot as the roster has it now, for what's saved apart from the form (its model). */
    live: Bot? = bot,
    /** Picks the model the bot runs; saved on its own, at once. */
    onPickModel: () -> Unit = {},
    /** Opens the bot's skills, tools and connectors. */
    onCapabilities: () -> Unit = {},
    /** Opens the bot's own Hermes settings. */
    onConfig: () -> Unit = {},
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

    val scroll = rememberScrollState()
    Box(
        Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader(
                if (bot == null) stringResource(Res.string.bots_new_bot) else stringResource(Res.string.bots_edit_named, bot.label),
                onBack = { if (busy == null) onBack() })
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    BotAvatar(preview, picture = null, size = 72.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(Res.string.bots_face))
                    ChoiceRow {
                        BotShape.entries.forEach { option ->
                            val look = Bot(name = profile.ifEmpty { "agent" }, uiMeta = JsonObject(mapOf(BotMeta.KEY to JsonObject(mapOf(
                                "shape" to JsonPrimitive(option.name.lowercase()),
                                "custom" to JsonPrimitive(true),
                            ) + (color?.let { mapOf("color" to JsonPrimitive(cssHex(it))) } ?: emptyMap())))))
                            Choice(selected = option == (shape ?: botLook(preview).shape), label = shapeName(option), onClick = { shape = option }) {
                                BotAvatar(look, picture = null, size = 32.dp)
                            }
                        }
                    }
                    ChoiceRow {
                        BOT_SWATCHES.forEach { swatch ->
                            Choice(selected = color == swatch, label = cssHex(swatch), onClick = { color = swatch }) {
                                Box(Modifier.size(28.dp).background(Color(0xFF000000 or swatch.toLong()), CircleShape))
                            }
                        }
                    }
                }
                if (bot == null) {
                    // With a keyboard, a new bot's page opens ready for its name to be typed.
                    val nameFocus = remember { FocusRequester() }
                    if (isDesktop) LaunchedEffect(Unit) { nameFocus.requestFocus() }
                    TextField(
                        name,
                        focusRequester = nameFocus,
                        label = stringResource(Res.string.bots_name),
                        placeholder = stringResource(Res.string.bots_name_example),
                        supportingText = when {
                            typedName.isBlank() -> stringResource(Res.string.bots_name_detail)
                            problem == null -> stringResource(Res.string.bots_profile_named, slug)
                            else -> null
                        },
                        error = problem,
                    )
                }
                val titleExample = stringResource(Res.string.bots_title_example)
                TextField(title, label = stringResource(Res.string.bots_title), placeholder = autoTitle.ifEmpty { titleExample })
                TextField(
                    description,
                    label = stringResource(Res.string.bots_description),
                    placeholder = stringResource(Res.string.bots_description_placeholder),
                    singleLine = false,
                    maxLines = 5,
                )
                TextField(
                    soul,
                    label = stringResource(Res.string.bots_persona),
                    placeholder = if (bot == null) stringResource(Res.string.bots_persona_placeholder) else null,
                    supportingText = stringResource(Res.string.bots_persona_detail),
                    singleLine = false,
                    maxLines = 12,
                )
                // What it runs and can do: each saved as soon as it's picked, apart from the form.
                if (bot != null) {
                    val now = live ?: bot
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel(stringResource(Res.string.bots_how_it_works))
                        // The two settings on one ringed card, split by a hairline.
                        val card = RoundedCornerShape(Theme[radii][radiusLarge])
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(card)
                                .background(Theme[colors][surface])
                                .border(1.dp, Theme[colors][stroke], card),
                        ) {
                            SettingRow(
                                Lucide.Cpu,
                                stringResource(Res.string.bots_model),
                                now.model?.takeIf { it.isNotBlank() }?.let { model ->
                                    listOfNotNull(displayModelName(model), now.provider?.takeIf { it.isNotBlank() }?.let { displayProviderName(it) })
                                        .joinToString(" · ")
                                } ?: stringResource(Res.string.bots_model_default),
                                onClick = onPickModel,
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
                            SettingRow(
                                Lucide.Blocks,
                                stringResource(Res.string.bots_capabilities),
                                stringResource(Res.string.bots_capabilities_detail, bot.label),
                                onClick = onCapabilities,
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
                            SettingRow(
                                Lucide.SlidersHorizontal,
                                stringResource(Res.string.bots_config),
                                stringResource(Res.string.bots_config_detail, bot.label),
                                onClick = onConfig,
                            )
                        }
                    }
                }
                busy?.let { Text(it.text(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
                if (bot == null) {
                    Button(
                        stringResource(Res.string.bots_make),
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
                        stringResource(Res.string.bots_save),
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
                    Text(stringResource(Res.string.bots_reading_details, bot.label), style = Theme[typography][caption], color = Theme[colors][textTertiary])
                }
            }
        }
        // The desktop's scrollbar, at the window's edge as a page's is.
        VerticalScrollbar(scroll, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(vertical = 2.dp, horizontal = 1.dp))
    }
}

/** A face's shape, as its tooltip names it. */
@Composable
private fun shapeName(shape: BotShape): String = stringResource(
    when (shape) {
        BotShape.Circle -> Res.string.bots_shape_circle
        BotShape.Squircle -> Res.string.bots_shape_squircle
        BotShape.Pill -> Res.string.bots_shape_pill
        BotShape.Triangle -> Res.string.bots_shape_triangle
        BotShape.Hexagon -> Res.string.bots_shape_hexagon
        BotShape.Cloud -> Res.string.bots_shape_cloud
        BotShape.Drop -> Res.string.bots_shape_drop
    },
)

/** A row that opens one of the bot's settings: what it is, and what it's set to now. */
@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .handCursor()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(Theme[colors][surface2], RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            Text(value, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

/** A face or color tile: picked, it sits on the accent tint with an accent edge, as the job editor's choices do. */
@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    // Its name, for a pointer that rests on it.
    Tooltip(label) {
        Box(
            Modifier
                .size(52.dp)
                .clip(shape)
                .background(if (selected) Theme[colors][accentSoft] else Theme[colors][surface2], shape)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme[colors][accentText] else Theme[colors][stroke], shape)
                .handCursor()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
