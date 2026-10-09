package dev.hermeskotlin.ui.gateways

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.warning
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.GatewayChoices

/**
 * The saved gateways, primary first, the one in use checked. Tapping one switches to it (its sign-in when it
 * holds no session); each row's menu makes it primary, renames or removes it.
 */
@Composable
fun GatewaysSheet(
    visible: Boolean,
    choices: GatewayChoices,
    /** The gateway on screen, which may not be saved yet (signing in to a new one). */
    activeUrl: String?,
    onDismiss: () -> Unit,
    onSwitch: (SavedGateway) -> Unit,
    onAdd: () -> Unit,
    onSetPrimary: (SavedGateway) -> Unit,
    onRename: (SavedGateway, String) -> Unit,
    onRemove: (SavedGateway) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<SavedGateway?>(null) }
    var removeTarget by remember { mutableStateOf<SavedGateway?>(null) }
    val list = choices.list

    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader("Gateways", "Each gateway is its own Hermes, with its own profiles and chats.")
        // A card each; the sheet scrolls only when there are more than fit.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            list.ordered.forEach { gateway ->
                GatewayRow(
                    gateway = gateway,
                    primary = gateway.url == list.primary?.url,
                    // With one gateway saved, "Primary" says nothing.
                    showPrimary = list.gateways.size > 1,
                    active = gateway.url == activeUrl,
                    signedIn = gateway.url in choices.signedIn,
                    onClick = { onDismiss(); onSwitch(gateway) },
                    onSetPrimary = { onSetPrimary(gateway) },
                    onRename = { renameTarget = gateway },
                    onRemove = { removeTarget = gateway },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SheetAction("Add a gateway", Lucide.Plus, onClick = { onDismiss(); onAdd() })
    }

    RenameGatewayDialog(renameTarget, onDismiss = { renameTarget = null }, onRename = onRename)
    RemoveGatewayDialog(removeTarget, onDismiss = { removeTarget = null }, onRemove = { onDismiss(); onRemove(it) })
}

@Composable
private fun GatewayRow(
    gateway: SavedGateway,
    primary: Boolean,
    showPrimary: Boolean,
    active: Boolean,
    signedIn: Boolean,
    onClick: () -> Unit,
    onSetPrimary: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(Theme[colors][if (active) accentSoft else surface2])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClick = onClick)
            .semantics { selected = active }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(Theme[colors][if (active) accent else surface3], RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val initial = gateway.label.firstOrNull()?.takeIf { it.isLetter() }
            // A gateway known only by its address gets a server, not the first digit of its IP.
            if (initial == null) {
                UnstyledIcon(Lucide.Server, contentDescription = null, tint = Theme[colors][if (active) onAccent else textSecondary], modifier = Modifier.size(17.dp))
            } else {
                Text(
                    initial.uppercase(),
                    style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][if (active) onAccent else textSecondary],
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    gateway.label,
                    style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (primary && showPrimary) {
                    Text(
                        "Primary",
                        style = Theme[typography][caption].copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                        color = Theme[colors][textSecondary],
                        maxLines = 1,
                        modifier = Modifier.background(Theme[colors][surface3], CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            val address = gateway.url.substringAfter("://").takeIf { gateway.name != null && it != gateway.label }
            if (address != null || !signedIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (address != null) {
                        Text(
                            address,
                            style = Theme[typography][code].copy(fontSize = 11.5.sp),
                            color = Theme[colors][textTertiary],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (!signedIn) {
                        Text(
                            if (address != null) "· Signed out" else "Signed out",
                            style = Theme[typography][bodySmall].copy(fontSize = 12.sp),
                            color = Theme[colors][warning],
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // The row says it's the one in use as selected; the check stays silent.
        if (active) UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(20.dp))
        DropdownMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            // The sheet sits at the bottom of the screen; below the row there's no room.
            above = true,
            items = {
                if (!primary) MenuAction("Make primary", Lucide.Star, onClick = { menuOpen = false; onSetPrimary() })
                MenuAction("Rename", Lucide.Pencil, onClick = { menuOpen = false; onRename() })
                MenuAction("Remove", Lucide.Trash2, onClick = { menuOpen = false; onRemove() })
            },
        ) {
            IconButton(Lucide.EllipsisVertical, contentDescription = "More for ${gateway.label}", onClick = { menuOpen = true })
        }
    }
}

@Composable
private fun RenameGatewayDialog(gateway: SavedGateway?, onDismiss: () -> Unit, onRename: (SavedGateway, String) -> Unit) {
    var shown by remember { mutableStateOf(gateway) }
    if (gateway != null) shown = gateway
    val g = shown ?: return
    val name = rememberTextFieldState(g.name.orEmpty())
    LaunchedEffect(g.url) { name.edit { replace(0, length, g.name.orEmpty()) } }
    val submit = {
        onDismiss()
        onRename(g, name.text.toString())
    }
    Dialog(
        visible = gateway != null,
        onDismissRequest = onDismiss,
        title = "Rename gateway",
        message = "Leave empty to show its address.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Save", onClick = submit, size = ButtonSize.Small)
        },
    ) {
        TextField(
            state = name,
            placeholder = g.url.substringAfter("://"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
    }
}

@Composable
private fun RemoveGatewayDialog(gateway: SavedGateway?, onDismiss: () -> Unit, onRemove: (SavedGateway) -> Unit) {
    var shown by remember { mutableStateOf(gateway) }
    if (gateway != null) shown = gateway
    val g = shown ?: return
    Dialog(
        visible = gateway != null,
        onDismissRequest = onDismiss,
        title = "Remove gateway?",
        message = "Herald signs out of “${g.label}” and forgets it. Its chats stay on the gateway.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Remove", onClick = { onDismiss(); onRemove(g) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}
