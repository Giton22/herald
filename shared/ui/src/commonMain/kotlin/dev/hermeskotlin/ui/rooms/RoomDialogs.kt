package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import com.composeunstyled.DropdownMenuPanelScope
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.TextField

/** A room's actions, the same wherever its menu opens: its row in the sidebar or its own top bar. */
@Composable
internal fun DropdownMenuPanelScope.RoomMenuActions(onRename: () -> Unit, onDelete: () -> Unit) {
    MenuAction("Rename", Lucide.Pencil, onRename)
    MenuAction("Delete room", Lucide.Trash2, onDelete)
}

/** What a room's menu has open: nothing, its rename, or the delete to confirm. */
internal sealed interface RoomAsk {
    data class Rename(val room: Room) : RoomAsk
    data class Delete(val room: Room) : RoomAsk
}

/** The dialog for [ask], if any: renaming a room, or confirming it's to be deleted. */
@Composable
internal fun RoomAskDialogs(ask: RoomAsk?, onDismiss: () -> Unit, onRename: (Room, String) -> Unit, onDelete: (Room) -> Unit) {
    // Kept while the dialog fades out, so its text doesn't vanish first.
    var shown by remember { mutableStateOf(ask) }
    if (ask != null) shown = ask
    when (val current = shown) {
        is RoomAsk.Rename -> RenameRoomDialog(current.room, visible = ask is RoomAsk.Rename, onDismiss = onDismiss, onRename = onRename)
        is RoomAsk.Delete -> DeleteRoomDialog(current.room, visible = ask is RoomAsk.Delete, onDismiss = onDismiss, onDelete = onDelete)
        null -> Unit
    }
}

@Composable
private fun RenameRoomDialog(room: Room, visible: Boolean, onDismiss: () -> Unit, onRename: (Room, String) -> Unit) {
    val name = rememberTextFieldState(room.name)
    val newName = name.text.toString().trim()
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = "Rename room",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                "Rename",
                onClick = { onDismiss(); onRename(room, newName) },
                variant = ButtonVariant.Primary,
                size = ButtonSize.Small,
                enabled = newName.isNotEmpty() && newName != room.name,
            )
        },
    ) {
        TextField(state = name, label = "Room name")
    }
}

@Composable
private fun DeleteRoomDialog(room: Room, visible: Boolean, onDismiss: () -> Unit, onDelete: (Room) -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = "Delete “${room.name}”?",
        message = "The room stops and is deleted from the gateway with its conversation, for every app. " +
            "The bots in it stay. This can't be undone.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Delete", onClick = { onDismiss(); onDelete(room) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}
