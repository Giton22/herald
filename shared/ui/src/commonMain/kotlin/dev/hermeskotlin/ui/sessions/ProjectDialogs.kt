package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.typography

/** What a new project starts with: blank, or a folder the gateway grouped chats by, to keep as a project. */
internal data class ProjectDraft(val name: String = "", val folder: String = "")

/**
 * A project's actions: one the user made can be renamed or deleted; a folder the gateway only grouped chats by
 * can be saved as a project, which keeps it (and its name) even when its chats go.
 */
@Composable
internal fun ProjectActionsSheet(
    project: Project?,
    onDismiss: () -> Unit,
    onRename: (Project) -> Unit,
    onDelete: (Project) -> Unit,
    onSave: (Project) -> Unit,
) {
    var shown by remember { mutableStateOf(project) }
    if (project != null) shown = project
    val p = shown ?: return
    BottomSheet(visible = project != null, onDismiss = onDismiss) {
        SheetHeader(p.label, subtitle = p.path)
        val act = { action: (Project) -> Unit -> { onDismiss(); action(p) } }
        if (p.isUserMade) {
            SheetAction("Rename", Lucide.Pencil, act(onRename))
            SheetAction("Delete project", Lucide.Trash2, act(onDelete), destructive = true)
        } else if (p.path != null) {
            SheetAction("Save as project", Lucide.FolderPlus, act(onSave))
        }
    }
}

/**
 * Makes a project. [draft] opens it (null closes it); [busy] while the gateway is asked, and [error] is why the
 * last try didn't go through, so the form stays open to fix it.
 */
@Composable
internal fun NewProjectDialog(
    draft: ProjectDraft?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, folder: String) -> Unit,
) {
    var shown by remember { mutableStateOf(draft) }
    if (draft != null) shown = draft
    val d = shown ?: return
    val name = rememberTextFieldState(d.name)
    val folder = rememberTextFieldState(d.folder)
    // On every open, not only a different draft: a blank one equals the last, whose text would stay.
    LaunchedEffect(draft != null, d) {
        if (draft == null) return@LaunchedEffect
        name.edit { replace(0, length, d.name) }
        folder.edit { replace(0, length, d.folder) }
    }
    val nameText = name.text.toString().trim()
    val submit = { if (nameText.isNotEmpty() && !busy) onCreate(nameText, folder.text.toString().trim()) }
    Dialog(
        visible = draft != null,
        onDismissRequest = { if (!busy) onDismiss() },
        title = "New project",
        message = "Chats in the folder group under the project, and a new chat started in it runs there.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small, enabled = !busy)
            Button("Create", onClick = submit, size = ButtonSize.Small, enabled = nameText.isNotEmpty(), loading = busy)
        },
    ) {
        TextField(state = name, label = "Name", placeholder = "Herald", enabled = !busy)
        TextField(
            state = folder,
            label = "Folder on the gateway",
            placeholder = "/home/you/projects/herald",
            supportingText = "Optional. The full path on the machine Hermes runs on.",
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
        error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
    }
}

@Composable
internal fun RenameProjectDialog(project: Project?, onDismiss: () -> Unit, onRename: (Project, String) -> Unit) {
    var shown by remember { mutableStateOf(project) }
    if (project != null) shown = project
    val p = shown ?: return
    val name = rememberTextFieldState(p.label)
    // On every open, so a cancelled edit doesn't come back.
    LaunchedEffect(project != null, p.id) { if (project != null) name.edit { replace(0, length, p.label) } }
    val nameText = name.text.toString().trim()
    val submit = {
        if (nameText.isNotEmpty()) {
            onDismiss()
            if (nameText != p.label) onRename(p, nameText)
        }
    }
    Dialog(
        visible = project != null,
        onDismissRequest = onDismiss,
        title = "Rename project",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Save", onClick = submit, size = ButtonSize.Small, enabled = nameText.isNotEmpty())
        },
    ) {
        TextField(
            state = name,
            placeholder = p.label,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
    }
}

@Composable
internal fun DeleteProjectDialog(project: Project?, onDismiss: () -> Unit, onDelete: (Project) -> Unit) {
    var shown by remember { mutableStateOf(project) }
    if (project != null) shown = project
    val p = shown ?: return
    Dialog(
        visible = project != null,
        onDismissRequest = onDismiss,
        title = "Delete project?",
        message = "“${p.label}” is removed from the gateway. Its chats stay, and are grouped by their folder again.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Delete", onClick = { onDismiss(); onDelete(p) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}
