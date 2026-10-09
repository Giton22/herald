package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.FolderUp
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.projects.FolderListing
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

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
 * last try didn't go through, so the form stays open to fix it. [listFolders] lists a folder's subfolders on the
 * gateway's machine, so the folder can be picked instead of typed.
 */
@Composable
internal fun NewProjectDialog(
    draft: ProjectDraft?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, folder: String) -> Unit,
    listFolders: suspend (dir: String, prefix: String) -> FolderListing,
) {
    var shown by remember { mutableStateOf(draft) }
    if (draft != null) shown = draft
    val d = shown ?: return
    val name = rememberTextFieldState(d.name)
    val folder = rememberTextFieldState(d.folder)
    // The folder being looked through, while the picker shows instead of the form.
    var browsing by remember { mutableStateOf<String?>(null) }
    // On every open, not only a different draft: a blank one equals the last, whose text would stay.
    LaunchedEffect(draft != null, d) {
        if (draft == null) return@LaunchedEffect
        name.edit { replace(0, length, d.name) }
        folder.edit { replace(0, length, d.folder) }
        browsing = null
    }
    val nameText = name.text.toString().trim()
    val submit = { if (nameText.isNotEmpty() && !busy) onCreate(nameText, folder.text.toString().trim()) }
    val dir = browsing
    // One dialog for both, so going to the picker and back doesn't play the dialog's entrance again.
    Dialog(
        visible = draft != null,
        onDismissRequest = { if (dir != null) browsing = null else if (!busy) onDismiss() },
        title = if (dir != null) "Choose a folder" else "New project",
        message = if (dir != null) null else "Chats in the folder group under the project, and a new chat started in it runs there.",
        actions = {
            if (dir != null) {
                Button("Back", onClick = { browsing = null }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                Button(
                    "Use this folder",
                    onClick = {
                        folder.edit { replace(0, length, FolderPath.picked(dir)) }
                        if (nameText.isEmpty()) FolderPath.name(dir)?.let { n -> name.edit { replace(0, length, n) } }
                        browsing = null
                    },
                    size = ButtonSize.Small,
                )
            } else {
                Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small, enabled = !busy)
                Button("Create", onClick = submit, size = ButtonSize.Small, enabled = nameText.isNotEmpty(), loading = busy)
            }
        },
    ) {
        if (dir != null) {
            FolderPicker(dir, onOpen = { browsing = it }, listFolders = listFolders)
            return@Dialog
        }
        TextField(state = name, label = "Name", placeholder = "Herald", enabled = !busy)
        TextField(
            state = folder,
            label = "Folder on the gateway",
            placeholder = "/home/you/projects/herald",
            supportingText = "Optional. A folder on the machine Hermes runs on.",
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
        Button(
            "Browse folders",
            onClick = { browsing = FolderPath.dirOf(folder.text.toString()) },
            variant = ButtonVariant.Outline,
            size = ButtonSize.Small,
            enabled = !busy,
            leadingIcon = Lucide.FolderOpen,
        )
        error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
    }
}

/**
 * The subfolders of [dir] on the gateway's machine: tap one to go in, Up and Home to move about, and a filter
 * that narrows by name (the gateway lists only a page of entries per folder). Dot-folders show only when the
 * filter starts with a dot.
 */
@Composable
private fun FolderPicker(
    dir: String,
    onOpen: (String) -> Unit,
    listFolders: suspend (dir: String, prefix: String) -> FolderListing,
) {
    val filter = rememberTextFieldState()
    LaunchedEffect(dir) { filter.edit { replace(0, length, "") } }
    val prefix = filter.text.toString()
    var listing by remember(dir) { mutableStateOf<FolderListing?>(null) }
    var failed by remember(dir) { mutableStateOf<String?>(null) }
    LaunchedEffect(dir, prefix) {
        if (prefix.isNotEmpty()) delay(250)
        try {
            listing = listFolders(dir, prefix)
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't list this folder."
        }
    }
    val secondary = Theme[colors][textSecondary]
    Text(dir, style = Theme[typography][bodySmall], color = secondary)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val up = FolderPath.parent(dir)
        IconButton(Lucide.FolderUp, contentDescription = "Up a folder", onClick = { up?.let(onOpen) }, enabled = up != null)
        IconButton(
            Lucide.House,
            contentDescription = "Home folder",
            onClick = { onOpen(FolderPath.HOME) },
            enabled = dir != FolderPath.HOME,
        )
        TextField(state = filter, modifier = Modifier.weight(1f), placeholder = "Filter", leadingIcon = Lucide.Search)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        // Narrowed here too, so the last answer matches the filter while the gateway is asked again.
        val folders = listing?.folders.orEmpty()
            .filter { it.startsWith(prefix, ignoreCase = true) && (!it.startsWith(".") || prefix.startsWith(".")) }
        when {
            failed != null -> Text(failed.orEmpty(), style = Theme[typography][bodySmall], color = Theme[colors][danger])
            listing == null -> Spinner(Modifier.padding(12.dp).size(20.dp))
            folders.isEmpty() -> Text("No folders here.", style = Theme[typography][bodySmall], color = secondary)
            else -> folders.forEach { SheetAction(it, Lucide.Folder, onClick = { onOpen(FolderPath.child(dir, it)) }) }
        }
        if (failed == null && listing?.more == true) {
            Text(
                "Some folders may not show here. Type the start of a name to find one.",
                style = Theme[typography][bodySmall],
                color = secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Folder paths as the gateway's `complete.path` takes them: `~/` or absolute, `/`-separated, ending in `/`. */
internal object FolderPath {
    const val HOME = "~/"

    /** The folder to start browsing at for what the field holds: home when it's blank. */
    fun dirOf(text: String): String {
        val path = text.trim().replace('\\', '/')
        return when {
            path.isEmpty() -> HOME
            path.endsWith("/") -> path
            else -> "$path/"
        }
    }

    fun child(dir: String, name: String): String = "$dir$name/"

    /** The folder above [dir], or null at a root (`/`, `C:/`). Above home is `/`. */
    fun parent(dir: String): String? {
        if (dir == HOME) return "/"
        val path = dir.removeSuffix("/")
        val cut = path.lastIndexOf('/')
        return if (cut < 0) null else path.substring(0, cut + 1)
    }

    /** What the folder field gets for [dir]: no trailing `/`, except on a root. */
    fun picked(dir: String): String = if (dir == "/" || ROOT.matches(dir)) dir else dir.removeSuffix("/")

    /** [dir]'s own name, to name a project after; null for home or a root. */
    fun name(dir: String): String? =
        picked(dir).substringAfterLast('/').takeUnless { it.isEmpty() || it == "~" || it.endsWith(":") }

    /** A Windows drive's root, like `C:/`. */
    private val ROOT = Regex("[A-Za-z]:/")
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
