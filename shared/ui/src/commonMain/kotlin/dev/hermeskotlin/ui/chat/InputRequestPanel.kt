package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
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
import kotlinx.serialization.json.JsonObject

/**
 * Takes the composer's place while the agent waits on a person: the oldest open request first, the
 * rest queued behind it. Answering sends one response; another client answering first makes the
 * gateway withdraw the request, and the panel moves on.
 */
@Composable
internal fun InputRequestPanel(
    requests: List<InputRequest>,
    connected: Boolean,
    onAnswer: (InputRequest, JsonObject) -> Unit,
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
                is InputRequest.Approval -> ApprovalContent(request, more, connected) { onAnswer(request, it) }
                is InputRequest.Clarify -> ClarifyContent(request, more, connected) { onAnswer(request, it) }
                is InputRequest.Secret -> SecretContent(request, more, connected) { onAnswer(request, it) }
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, tint: Color, title: String, trailing: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, style = Theme[typography][heading], color = Theme[colors][textColor], modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Theme[typography][caption], color = Theme[colors][textTertiary])
    }
}

@Composable
private fun CommandBlock(command: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Text(
        command,
        style = Theme[typography][code],
        color = Theme[colors][textColor],
        maxLines = 10,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][background], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ApprovalContent(request: InputRequest.Approval, more: String?, connected: Boolean, answer: (JsonObject) -> Unit) {
    var confirmAlways by remember(request.id) { mutableStateOf(false) }
    val choose = { choice: ApprovalChoice -> answer(InputAnswers.approval(choice)) }

    Header(Lucide.ShieldAlert, Theme[colors][warning], "Allow this command?", more)
    if (request.description.isNotBlank()) {
        Text(request.description.replaceFirstChar { it.uppercase() }, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
    if (request.command.isNotBlank()) CommandBlock(request.command)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("Deny", onClick = { choose(ApprovalChoice.Deny) }, variant = ButtonVariant.Outline, enabled = connected, modifier = Modifier.weight(1f))
        Button("Allow once", onClick = { choose(ApprovalChoice.Once) }, enabled = connected, modifier = Modifier.weight(1f))
    }
    val wider = request.choices.filter { it == ApprovalChoice.Session || it == ApprovalChoice.Always }
    if (wider.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wider.forEach { choice ->
                Button(
                    if (choice == ApprovalChoice.Session) "Allow for this chat" else "Always allow",
                    onClick = { if (choice == ApprovalChoice.Always) confirmAlways = true else choose(choice) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                    enabled = connected,
                    modifier = Modifier.weight(1f),
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
private fun ClarifyContent(request: InputRequest.Clarify, more: String?, connected: Boolean, answer: (JsonObject) -> Unit) {
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
    Header(Lucide.MessageCircleQuestion, Theme[colors][accent], "Hermes asks", counter)
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
private fun SecretContent(request: InputRequest.Secret, more: String?, connected: Boolean, answer: (JsonObject) -> Unit) {
    val value = remember(request.id) { TextFieldState() }
    val sudo = request.kind == InputRequest.Secret.Kind.Sudo
    val submit = { if (value.text.isNotEmpty()) answer(InputAnswers.value(value.text.toString())) }

    Header(Lucide.KeyRound, Theme[colors][warning], if (sudo) "Sudo password needed" else "Secret needed", more)
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
