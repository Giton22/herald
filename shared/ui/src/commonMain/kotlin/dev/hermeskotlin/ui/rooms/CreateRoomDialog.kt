package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.ui.bots.BotAvatar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * Names a new room and picks its bots: 2 to 6, named by their handles so members can cite each other.
 * Everything the room needs is the gateway's; nothing of it lives in the app.
 */
@Composable
fun CreateRoomDialog(
    visible: Boolean,
    bots: List<Bot>,
    busy: String?,
    /** Why the last try didn't make the room, shown until the next try. */
    error: String? = null,
    onDismiss: () -> Unit,
    onCreate: (name: String, members: List<Bot>) -> Unit,
) {
    if (!visible) return
    val name = rememberTextFieldState()
    val selected = remember { mutableListOf<String>().toMutableStateList() }
    val nameText = name.text.toString().trim()
    val canCreate = nameText.isNotEmpty() && selected.size in MIN_MEMBERS..MAX_MEMBERS && busy == null
    Dialog(
        visible = visible,
        onDismissRequest = { if (busy == null) onDismiss() },
        title = "New room",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small, enabled = busy == null)
            Button(
                "Create",
                onClick = { onCreate(nameText, bots.filter { it.name in selected }) },
                variant = ButtonVariant.Primary,
                size = ButtonSize.Small,
                enabled = canCreate,
                loading = busy != null,
            )
        },
    ) {
        TextField(state = name, label = "Room name", placeholder = "Release room", enabled = busy == null)
        Text(
            "The bots you pick talk things over in the room. It runs on the gateway, so it keeps going " +
                "with no app open, and the whole conversation is there when you come back.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textSecondary],
        )
        SectionLabel("Bots · pick $MIN_MEMBERS to $MAX_MEMBERS")
        Column(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
        ) {
            bots.forEach { bot ->
                val picked = bot.name in selected
                val full = !picked && selected.size >= MAX_MEMBERS
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                        .background(if (picked) Theme[colors][accentSoft] else Color.Transparent)
                        .toggleable(value = picked, enabled = busy == null && !full, role = Role.Checkbox) {
                            if (picked) selected.remove(bot.name) else selected.add(bot.name)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Each bot by its own face, as the roster and the room draw it.
                    BotAvatar(bot, null, size = 30.dp)
                    Text(
                        bot.label,
                        style = Theme[typography][body].copy(fontWeight = FontWeight.Medium),
                        color = Theme[colors][if (full) textTertiary else text],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PickMark(picked)
                }
            }
        }
        Text(
            "${selected.size} picked",
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
        )
        if (error != null && busy == null) {
            Text(error, style = Theme[typography][bodySmall], color = Theme[colors][danger])
        }
    }
}

/** A picked bot's mark: a filled check; an unpicked one stays an empty ring. */
@Composable
private fun PickMark(picked: Boolean) {
    if (picked) {
        Box(Modifier.size(18.dp).background(Theme[colors][accent], CircleShape), contentAlignment = Alignment.Center) {
            // The row says picked as a checkbox; the mark itself stays silent.
            UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][onAccent], modifier = Modifier.size(12.dp))
        }
    } else {
        Box(Modifier.size(18.dp).border(1.dp, Theme[colors][stroke], CircleShape))
    }
}

private const val MIN_MEMBERS = 2
private const val MAX_MEMBERS = 6
