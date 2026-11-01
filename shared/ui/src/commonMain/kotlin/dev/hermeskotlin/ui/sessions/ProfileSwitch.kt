package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.profiles.ProfileRoster
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.sidebar as sidebarColor
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.bots.BotAvatar
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.sessions_profile_on_gateway
import dev.hermeskotlin.ui.resources.sessions_switch_profile
import org.jetbrains.compose.resources.stringResource

/** What the sidebar's profile switch shows: the profile whose chats are listed, by the name the picker gives it. */
data class ProfileSwitch(val label: String)

/**
 * Said for the launch profile when the gateway didn't say which one it runs as (older gateways). The screens pass
 * the catalogue's words for it; this is the English, for tests.
 */
internal const val UNKNOWN_LAUNCH_LABEL = "Gateway default"

/**
 * The sidebar's profile switch for [roster] with [picked] the chosen profile (null: the gateway's launch profile),
 * or null to leave it out. It shows where there is a profile to switch to: the gateway lists two or more, or
 * the chats are another profile's than the gateway's own (one since deleted, say), so there's a way back.
 * A gateway with one profile, or one that doesn't list them, shows nothing new. While the list is still on its
 * way it shows only for a picked profile, which needs no list to name.
 */
internal fun profileSwitch(roster: ProfileRoster?, picked: String?, unknownLaunch: String = UNKNOWN_LAUNCH_LABEL): ProfileSwitch? {
    if (roster == null) return picked?.let(::ProfileSwitch)
    val elsewhere = picked != null && picked != roster.launch
    if (roster.profiles.size < 2 && !elsewhere) return null
    return profileLabel(roster, picked, unknownLaunch)?.let(::ProfileSwitch)
}

/**
 * The name of the profile whose chats are listed, as the switch and the account sheet both say it: [picked], by
 * its display name when [roster] lists it, else the gateway's launch profile, which is named only when the gateway
 * said which it runs as. Null while there's no list and nothing picked.
 */
internal fun profileLabel(roster: ProfileRoster?, picked: String?, unknownLaunch: String = UNKNOWN_LAUNCH_LABEL): String? {
    if (roster == null) return picked
    val name = picked ?: roster.launch
    val profile = roster.profiles.find { it.name == name }
    return when {
        profile != null && (picked != null || roster.launchKnown) -> profile.label
        picked != null -> picked
        else -> unknownLaunch
    }
}

/** The first character of [label] as a profile's initial, upper-cased; an emoji or other pair is kept whole. */
internal fun profileInitial(label: String): String {
    val first = label.trimStart()
    if (first.isEmpty()) return ""
    val end = if (first.length > 1 && first[0].isHighSurrogate() && first[1].isLowSurrogate()) 2 else 1
    return first.substring(0, end).uppercase()
}


/**
 * The active profile in the sidebar's header, where the app's name is when there's no profile to switch: its
 * face as the Bots side draws it ([bot], with its [picture]; its initial on a disc until the roster has come),
 * the connection's dot as a badge, its name (cut short when long) and a chevron, over the gateway in use.
 * A tap opens the profile picker.
 */
@Composable
internal fun ProfileSwitchHeader(
    switch: ProfileSwitch,
    gatewayLabel: String,
    /** Null in previews, which have no connection to report. */
    connection: ConnectionState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The profile as a bot, from the roster; null while it's on its way, or on a gateway without Bot Mode. */
    bot: Bot? = null,
    picture: ByteArray? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    val spoken = stringResource(Res.string.sessions_profile_on_gateway, switch.label, gatewayLabel)
    val connectionText = stringResource(connectionWord(connection))
    val switchProfile = stringResource(Res.string.sessions_switch_profile)
    Row(
        modifier
            .heightIn(min = MinTouchTarget)
            .clip(shape)
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = switchProfile, onClick = onClick)
            .indication(interaction, if (isDesktop) rememberHoverIndication(Theme[colors][text], shape) else LocalIndication.current)
            .clearAndSetSemantics {
                contentDescription = spoken
                stateDescription = connectionText
                role = Role.Button
                onClick(label = switchProfile) { onClick(); true }
            }
            .padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (bot != null) BotAvatar(bot, picture, size = 30.dp) else ProfileInitial(switch.label, checked = true, size = 30.dp)
            // The connection's dot, ringed in the sidebar's colour so it reads apart from the disc.
            Box(
                Modifier
                    .size(12.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .background(Theme[colors][sidebarColor], CircleShape)
                    .padding(2.dp)
                    .background(connectionDot(connection), CircleShape),
            )
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    switch.label,
                    style = Theme[typography][heading].copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
            Text(
                gatewayLabel,
                style = Theme[typography][caption].copy(fontSize = 12.sp),
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


/** A profile's initial on a disc, as the profile picker draws each profile: the accent fill for the active one. */
@Composable
internal fun ProfileInitial(label: String, checked: Boolean, size: Dp = 28.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Theme[colors][if (checked) accent else surface]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profileInitial(label),
            style = Theme[typography][caption].copy(fontWeight = FontWeight.SemiBold),
            color = Theme[colors][if (checked) onAccent else textSecondary],
        )
    }
}
