package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.min
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleUser
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.projects.Project
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.MenuChoice
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.PROJECT_MENU_MAX_HEIGHT
import dev.hermeskotlin.ui.sessions.projectIcon
import org.jetbrains.compose.resources.stringResource

/**
 * Where a new chat can start, offered on its greeting while nothing has been sent in it: the project it runs in,
 * and the profile and gateway it runs on. Picking a profile or a gateway starts a new chat there.
 */
class GreetingPlaces(
    /** The list's projects for this gateway and profile, Home among them when it has chats. */
    val projects: List<Project> = emptyList(),
    /** The gateway's profiles. */
    val profiles: List<Profile> = emptyList(),
    /** The chat's profile by name, the launch profile's name included. */
    val profile: String? = null,
    /** The saved gateways. */
    val gateways: List<SavedGateway> = emptyList(),
    /** The chat's gateway, by [SavedGateway.url]. */
    val gateway: String? = null,
    /** Moves the chat to a project's folder; null: no project. */
    val onProject: (String?) -> Unit = {},
    val onProfile: (Profile) -> Unit = {},
    val onGateway: (SavedGateway) -> Unit = {},
) {
    /** Some project to pick besides Home; without one the greeting asks as it always did. */
    val hasProjects: Boolean get() = projects.any { it.startsIn != null }

    /** Another profile or gateway to start in, so the line under the question opens a menu. */
    val hasPlaces: Boolean get() = profiles.size > 1 || gateways.size > 1
}

/** The folder a new chat in this project starts in; none for Home, or a project made without a folder. */
private val Project.startsIn: String? get() = path?.takeIf { !isNoProject }

/**
 * What the greeting calls where a new chat in [cwd] runs: its project's name, else the folder's own name, else Home
 * (the list's name for it when it has one, else [home]).
 */
internal fun greetingProjectLabel(projects: List<Project>, cwd: String?, home: String): String {
    if (cwd == null) return projects.firstOrNull { it.isNoProject }?.label ?: home
    return projects.firstOrNull { it.startsIn?.let { path -> samePath(path, cwd) } == true }?.label
        ?: cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { cwd }
}

/** One folder, however its end is written. */
internal fun samePath(a: String, b: String): Boolean = a.trimEnd('/', '\\') == b.trimEnd('/', '\\')

/**
 * The greeting's question with the project in it, as a pill that opens the projects:
 * "What are we building in (herald ⌄)?". The words keep each language's order around it.
 */
@Composable
internal fun ProjectQuestion(places: GreetingPlaces, cwd: String?) {
    val style = Theme[typography][display].copy(color = Theme[colors][textColor])
    // The sentence's own type, so the name reads as one of its words.
    val nameStyle = style.copy(color = Theme[colors][accentText])
    val label = greetingProjectLabel(places.projects, cwd, stringResource(Res.string.chat_greeting_home))
    val parts = stringResource(Res.string.chat_greeting_question_in, PROJECT_MARK).split(PROJECT_MARK)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chevron = with(density) { nameStyle.fontSize.toDp() } * CHEVRON_SCALE
        // The text size setting and the desktop's zoom change the measure, so they count too.
        val nameWidth = remember(label, nameStyle, measurer, density) { measurer.measure(label, nameStyle, maxLines = 1).size.width }
        // The words on the button's own line: "in " before it, "?" after (the language's, on either side).
        val (lead, tail) = remember(parts, style, measurer, density) {
            fun width(text: String) = if (text.isEmpty()) 0 else measurer.measure(text, style, maxLines = 1).size.width
            width(parts.first().substringAfterLast('\n')) to width(parts.drop(1).joinToString("").substringBefore('\n'))
        }
        // As wide as the pill around the name, but no wider than what's left beside those words, so the line doesn't
        // break before the pill and leave "in" alone: a long name is cut short instead. Words that leave too little
        // room (a long lead in a narrow window) let it take a line of its own with its "?".
        val beside = maxWidth - with(density) { (lead + tail).toDp() } - LINE_SLACK
        val room = if (beside >= maxWidth * MIN_LINE_SHARE) beside else maxWidth - with(density) { tail.toDp() } - LINE_SLACK
        val pill = with(density) { nameWidth.toDp() } + PILL_PADDING * 2 + CHEVRON_GAP + chevron + LINE_SLACK
        val width = min(pill + PILL_MARGIN, room)
        val height = style.lineHeight.takeIf { it.isSpecified } ?: style.fontSize
        val inline = mapOf(
            PROJECT_BUTTON to InlineTextContent(
                Placeholder(with(density) { width.toSp() }, height, PlaceholderVerticalAlign.TextCenter),
            ) { ProjectButton(places, cwd, label, nameStyle, chevron) },
        )
        BasicText(
            remember(parts, label) {
                buildAnnotatedString {
                    append(parts.first())
                    appendInlineContent(PROJECT_BUTTON, label)
                    append(parts.drop(1).joinToString(""))
                }
            },
            style = style,
            inlineContent = inline,
        )
    }
}

/** The project's name and a chevron in a pill; a tap opens Home and the projects to move the new chat to. */
@Composable
private fun ProjectButton(places: GreetingPlaces, cwd: String?, label: String, style: TextStyle, chevron: Dp) {
    var open by remember { mutableStateOf(false) }
    val choose = stringResource(Res.string.chat_greeting_choose_project)
    val menuState = stringResource(if (open) Res.string.sessions_expanded else Res.string.sessions_collapsed)
    val home = places.projects.firstOrNull { it.isNoProject }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        alignment = AnchorAlignment.Start,
        maxHeight = PROJECT_MENU_MAX_HEIGHT,
        items = {
            MenuChoice(
                home?.label ?: stringResource(Res.string.chat_greeting_home),
                Lucide.House,
                selected = cwd == null,
                onClick = { places.onProject(null) },
                count = home?.sessionCount,
            )
            places.projects.forEach { project ->
                val folder = project.startsIn ?: return@forEach
                MenuChoice(
                    project.label,
                    projectIcon(project),
                    selected = cwd != null && samePath(folder, cwd),
                    onClick = { places.onProject(folder) },
                    count = project.sessionCount,
                )
            }
        },
    ) {
        // The placeholder is the line's height, with a soft accent highlight behind the name and a gap before the "?"
        // after it. Centred in the placeholder, the name sits a little below the sentence's baseline; lifted by
        // NAME_LIFT it shares it, and sits in the middle of its highlight too.
        val lift = with(LocalDensity.current) { style.fontSize.toDp() } * NAME_LIFT
        Box(Modifier.fillMaxSize().padding(end = PILL_MARGIN), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .clip(PillShape)
                    .background(Theme[colors][accentSoft], PillShape)
                    .handCursor()
                    .clickable(role = Role.DropdownList, onClickLabel = choose) { open = !open }
                    .semantics { stateDescription = menuState }
                    .padding(horizontal = PILL_PADDING),
                horizontalArrangement = Arrangement.spacedBy(CHEVRON_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    label,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).offset(y = -lift),
                )
                UnstyledIcon(
                    if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    tint = style.color,
                    // Left where it is, it sits on the middle of the lowercase letters.
                    modifier = Modifier.size(chevron),
                )
            }
        }
    }
}

/**
 * Where Hermes runs, under the question ("Hermes on homelab · work profile"). With another profile or gateway to
 * go to, it's a button that opens them, and picking one starts a new chat there; else it's only said.
 */
@Composable
internal fun PlaceLine(places: GreetingPlaces?, text: String) {
    if (places == null || !places.hasPlaces) {
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        return
    }
    var open by remember { mutableStateOf(false) }
    val choose = stringResource(Res.string.chat_greeting_choose_place)
    val menuState = stringResource(if (open) Res.string.sessions_expanded else Res.string.sessions_collapsed)
    val tint = Theme[colors][textSecondary]
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        // Under the profile and its chevron at the line's end, where the tap was.
        alignment = AnchorAlignment.End,
        maxHeight = PROJECT_MENU_MAX_HEIGHT,
        items = {
            if (places.profiles.size > 1) places.profiles.forEach { profile ->
                val picked = profile.name == places.profile
                MenuChoice(profile.label, Lucide.CircleUser, selected = picked, onClick = { if (!picked) places.onProfile(profile) })
            }
            if (places.gateways.size > 1) places.gateways.forEach { gateway ->
                val picked = gateway.url == places.gateway
                MenuChoice(gateway.label, Lucide.Server, selected = picked, onClick = { if (!picked) places.onGateway(gateway) })
            }
        },
    ) {
        Row(
            Modifier
                .heightIn(min = MinTouchTarget)
                .handCursor()
                .clickable(role = Role.DropdownList, onClickLabel = choose) { open = !open }
                .semantics { stateDescription = menuState },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.Server, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                text,
                style = Theme[typography][bodySmall],
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            UnstyledIcon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

/** Stands in for the project while the question is split around it; never shown. */
private const val PROJECT_MARK = "\u0000"

/** The question's inline button, by its id in the text. */
private const val PROJECT_BUTTON = "project"

/** How far the name is lifted onto the sentence's baseline, as a share of its type size. */
private const val NAME_LIFT = 0.1f

/** The chevron beside the project's name, as a share of the name's type size. */
private const val CHEVRON_SCALE = 0.5f

private val CHEVRON_GAP = 4.dp

/** Room inside the highlight either side of the name and chevron. */
private val PILL_PADDING = 10.dp

/** A highlight on the words rather than a button beside them. */
private val PillShape = RoundedCornerShape(12.dp)

/** Space between the pill and the "?" after it. */
private val PILL_MARGIN = 2.dp

/** Below this share of the line beside its words, the project's button takes a line of its own instead. */
private const val MIN_LINE_SHARE = 0.4f

/** Kept free on the button's line, so rounding in the measures doesn't push it onto the next one. */
private val LINE_SLACK = 2.dp

