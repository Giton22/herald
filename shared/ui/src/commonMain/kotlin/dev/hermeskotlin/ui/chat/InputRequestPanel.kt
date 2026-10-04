package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareCheck
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.input
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Takes the composer's place while the agent waits on a person: the oldest open request first, the
 * rest queued behind it. Answering sends one response; another client answering first makes the
 * gateway withdraw the request, and the panel moves on. [onStop] keeps the task's Stop within reach
 * while the composer is hidden.
 */
@Composable
internal fun InputRequestPanel(
    requests: List<InputRequest>,
    connected: Boolean,
    onAnswer: (InputRequest, JsonObject) -> Unit,
    onStop: (() -> Unit)? = null,
) {
    val request = requests.firstOrNull() ?: return
    Surface(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        elevated = true,
    ) {
        Column(
            Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val more = (requests.size - 1).takeIf { it > 0 }?.let { "+$it more" }
            when (request) {
                is InputRequest.Approval -> ApprovalContent(request, more, connected, onStop.takeIf { connected }) { onAnswer(request, it) }
                is InputRequest.Clarify -> ClarifyContent(request, more, connected, onStop.takeIf { connected }) { onAnswer(request, it) }
                is InputRequest.Secret -> SecretContent(request, more, connected, onStop.takeIf { connected }) { onAnswer(request, it) }
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, tint: Color, title: String, trailing: String?, onStop: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, style = Theme[typography][heading], color = Theme[colors][textColor], modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Theme[typography][caption], color = Theme[colors][textTertiary])
        if (onStop != null) {
            Button("Stop task", onClick = onStop, variant = ButtonVariant.Ghost, size = ButtonSize.Small, leadingIcon = Lucide.CircleStop)
        }
    }
}

/** A broader permission: what it allows and for how long, the whole row the button. */
@Composable
private fun WiderChoice(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = Theme[typography][bodySmall], color = if (enabled) Theme[colors][textColor] else Theme[colors][textTertiary])
        Text(detail, style = Theme[typography][caption], color = Theme[colors][textTertiary])
    }
}

/** A command that wraps past this many lines shows its start until "Show full command". */
private const val COMMAND_PREVIEW_LINES = 4

/** How long a new approval ignores taps, so one aimed at the composer it replaced doesn't answer it. */
private const val APPROVAL_ARM_DELAY_MS = 700L

/**
 * The exact command, wrapped so nothing hides off to the side. A long one shows its first lines until
 * "Show full command"; Copy takes all of it either way.
 */
@Composable
private fun CommandBlock(command: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    // Long means it wrapped past the preview, measured as laid out; once open it stays offered to close.
    var long by remember(command) { mutableStateOf(false) }
    var full by remember(command) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            command,
            style = Theme[typography][code].copy(color = Theme[colors][textColor]),
            maxLines = if (full) Int.MAX_VALUE else COMMAND_PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow) long = true },
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Theme[colors][background], shape)
                .border(1.dp, Theme[colors][stroke], shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (long) {
                Button(
                    if (full) "Show less" else "Show full command",
                    onClick = { full = !full },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                    leadingIcon = if (full) Lucide.ChevronUp else Lucide.ChevronDown,
                )
            }
            Spacer(Modifier.weight(1f))
            CommandCopy(command)
        }
    }
}

@Composable
private fun CommandCopy(command: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Button(
        if (copied) "Copied" else "Copy command",
        onClick = {
            scope.launch { clipboard.setClipEntry(plainTextClipEntry(command)) }
            copied = true
        },
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Small,
        leadingIcon = if (copied) Lucide.Check else Lucide.Copy,
    )
}

@Composable
private fun ApprovalContent(request: InputRequest.Approval, more: String?, connected: Boolean, onStop: (() -> Unit)?, answer: (JsonObject) -> Unit) {
    var confirmAlways by remember(request.id) { mutableStateOf(false) }
    val choose = { choice: ApprovalChoice -> answer(InputAnswers.approval(choice)) }
    // The panel lands where the composer was; a tap meant for the text field mustn't answer it.
    var armed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        delay(APPROVAL_ARM_DELAY_MS)
        armed = true
    }
    val ready = connected && armed

    Header(Lucide.ShieldAlert, Theme[colors][warning], "Allow this command?", more, onStop)
    val purpose = request.description.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    Text(
        listOfNotNull(request.toolName?.let { "Hermes wants to run this with $it." }, purpose).joinToString(" "),
        style = Theme[typography][bodySmall],
        color = Theme[colors][textSecondary],
    )
    if (request.command.isNotBlank()) CommandBlock(request.command)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("Deny", onClick = { choose(ApprovalChoice.Deny) }, variant = ButtonVariant.Outline, enabled = ready, modifier = Modifier.weight(1f))
        Button("Allow once", onClick = { choose(ApprovalChoice.Once) }, enabled = ready, modifier = Modifier.weight(1f))
    }
    Text("Allow once runs only this command, this time.", style = Theme[typography][caption], color = Theme[colors][textTertiary])
    val wider = request.choices.filter { it == ApprovalChoice.Session || it == ApprovalChoice.Always }
    if (wider.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Broader permissions", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            wider.forEach { choice ->
                val session = choice == ApprovalChoice.Session
                WiderChoice(
                    title = if (session) "Allow for this chat" else "Always allow",
                    detail = if (session) {
                        "Commands like this run without asking again, in this chat only, until it ends."
                    } else {
                        "Commands like this run without asking again, in every chat, until you remove the rule from the gateway's config."
                    },
                    enabled = ready,
                    onClick = { if (session) choose(choice) else confirmAlways = true },
                )
            }
        }
    }

    Dialog(
        visible = confirmAlways,
        onDismissRequest = { confirmAlways = false },
        title = "Always allow?",
        message = "Commands like this will run without asking again, in every chat. The rule is saved in the gateway's config; remove it there to undo.",
        actions = {
            Button("Cancel", onClick = { confirmAlways = false }, variant = ButtonVariant.Ghost)
            Button("Always allow", onClick = { confirmAlways = false; choose(ApprovalChoice.Always) })
        },
    )
}

@Composable
private fun ClarifyContent(request: InputRequest.Clarify, more: String?, connected: Boolean, onStop: (() -> Unit)?, answer: (JsonObject) -> Unit) {
    val questions = request.questions
    var index by remember(request.id) { mutableStateOf(0) }
    val picked = remember(request.id) { questions.map { mutableStateListOf<String>() } }
    val drafts = remember(request.id) { questions.map { TextFieldState() } }
    val question = questions[index]
    val last = index == questions.lastIndex

    fun answerOf(i: Int): List<String> {
        val typed = drafts[i].text.toString().trim()
        return if (questions[i].multiSelect) picked[i] + listOfNotNull(typed.takeIf { it.isNotEmpty() }) else {
            listOfNotNull(typed.takeIf { it.isNotEmpty() } ?: picked[i].firstOrNull())
        }
    }

    val counter = if (questions.size > 1) "${index + 1} of ${questions.size}" else more
    Header(Lucide.MessageCircleQuestion, Theme[colors][accent], "Hermes asks", counter, onStop)
    Text(question.question, style = Theme[typography][body], color = Theme[colors][textColor])
    if (question.choices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            question.choices.forEach { choice ->
                ChoiceRow(choice, question, selected = choice in picked[index]) {
                    val selection = picked[index]
                    when {
                        question.multiSelect && choice in selection -> selection.remove(choice)
                        question.multiSelect -> selection.add(choice)
                        else -> {
                            selection.clear()
                            selection.add(choice)
                            drafts[index].clearText()
                        }
                    }
                }
            }
        }
    }
    TextField(
        state = drafts[index],
        placeholder = if (question.choices.isEmpty()) "Your answer" else "Something else…",
        singleLine = false,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            if (index > 0) "Back" else "Skip",
            onClick = { if (index > 0) index-- else answer(InputAnswers.clarifyCancel.takeIf { request.batch } ?: InputAnswers.clarify(request, listOf(emptyList()))) },
            variant = ButtonVariant.Outline,
            enabled = connected || index > 0,
            modifier = Modifier.weight(1f),
        )
        Button(
            if (last) "Send" else "Next",
            onClick = { if (last) answer(InputAnswers.clarify(request, questions.indices.map(::answerOf))) else index++ },
            enabled = (connected || !last) && answerOf(index).isNotEmpty(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChoiceRow(choice: String, question: ClarifyQuestion, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val icon = when {
        question.multiSelect -> if (selected) Lucide.SquareCheck else Lucide.Square
        else -> if (selected) Lucide.CircleCheck else Lucide.Circle
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Theme[colors][accentSoft] else Theme[colors][input], shape)
            .clickable(role = if (question.multiSelect) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(
            icon,
            contentDescription = null,
            tint = if (selected) Theme[colors][accent] else Theme[colors][textTertiary],
            modifier = Modifier.size(18.dp),
        )
        Text(choice, style = Theme[typography][bodySmall], color = Theme[colors][textColor])
    }
}

@Composable
private fun SecretContent(request: InputRequest.Secret, more: String?, connected: Boolean, onStop: (() -> Unit)?, answer: (JsonObject) -> Unit) {
    val value = remember(request.id) { TextFieldState() }
    val sudo = request.kind == InputRequest.Secret.Kind.Sudo
    val submit = { if (value.text.isNotEmpty()) answer(InputAnswers.value(value.text.toString())) }

    Header(Lucide.KeyRound, Theme[colors][warning], if (sudo) "Sudo password needed" else "Secret needed", more, onStop)
    if (sudo) {
        Text("To run this command as root:", style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        request.command?.takeIf { it.isNotBlank() }?.let { CommandBlock(it) }
    } else {
        Text(request.prompt, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
    TextField(
        state = value,
        label = if (sudo) "Password" else request.envVar,
        password = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { submit() },
        supportingText = "Sent to your gateway only.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("Decline", onClick = { answer(InputAnswers.value("")) }, variant = ButtonVariant.Outline, enabled = connected, modifier = Modifier.weight(1f))
        Button("Submit", onClick = submit, enabled = connected && value.text.isNotEmpty(), modifier = Modifier.weight(1f))
    }
}
