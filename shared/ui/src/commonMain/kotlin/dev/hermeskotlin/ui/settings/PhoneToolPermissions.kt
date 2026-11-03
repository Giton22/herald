package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.phone.requiresPhoneToolPermission
import dev.hermeskotlin.core.settings.AppSettings

/** The parent grants tool access; its companion controls whether existing write actions must ask first. */
@Composable
internal fun PhoneToolPermissionRow(tool: PhoneTool, settings: AppSettings, onPermission: (PhoneTool, Boolean) -> Unit) {
    val title = tool.permissionTitle ?: return
    Box(Modifier.padding(start = 46.dp)) {
        SwitchRow(
            title = title,
            titleColor = Theme[colors][textSecondary],
            detail = null,
            checked = settings.requiresPhoneToolPermission(tool),
            enabled = tool in settings.enabledPhoneTools(),
            onCheckedChange = { onPermission(tool, it) },
        )
    }
}

internal val PhoneTool.permissionTitle: String?
    get() = when (this) {
        PhoneTool.Sound -> "Require approval to change sound controls"
        PhoneTool.Alarms -> "Require approval to set alarms"
        PhoneTool.Open -> "Require approval to open links, maps and apps"
        PhoneTool.Clipboard -> "Require approval to copy text"
        PhoneTool.Geofences -> "Require approval to change watched places"
        PhoneTool.Calendar -> "Require approval to change calendar events"
        else -> null
    }
