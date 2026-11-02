package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.ui.i18n.Strings

/**
 * A phone tool asking before it changes something, while Herald is open: the same question as its notification,
 * answered here instead. [allow] runs from the tap, as Android wants for starting an alarm, a link or the share sheet.
 */
class PhoneQuestion(val key: String, val title: String, val what: String, val allow: () -> Unit, val deny: () -> Unit)

/** [question] as a dialog; Back or a tap outside is Deny, like swiping the notification away. */
@Composable
fun PhoneQuestionDialog(question: PhoneQuestion?) {
    // Kept while the dialog fades out, so its text doesn't go blank first.
    var shown by remember { mutableStateOf(question) }
    if (question != null) shown = question
    val q = shown ?: return
    Dialog(
        visible = question != null,
        onDismissRequest = q.deny,
        title = Strings.t(q.title),
        message = Strings.t(q.what),
        actions = {
            Button(Strings.t("Deny"), onClick = q.deny, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(Strings.t("Allow"), onClick = q.allow, size = ButtonSize.Small)
        },
    )
}
