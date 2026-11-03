package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.phone.requiresPhoneToolPermission
import dev.hermeskotlin.core.settings.AppSettings

/** The parent grants tool access; its indented companion controls whether each use must ask first. */
@Composable
internal fun PhoneToolPermissionRow(tool: PhoneTool, settings: AppSettings, onPermission: (PhoneTool, Boolean) -> Unit) {
    Box(Modifier.padding(start = 46.dp)) {
        SwitchRow(
            title = tool.permissionTitle,
            detail = null,
            checked = settings.requiresPhoneToolPermission(tool),
            enabled = tool in settings.enabledPhoneTools(),
            onCheckedChange = { onPermission(tool, it) },
        )
    }
}

internal val PhoneTool.permissionTitle: String
    get() = when (this) {
        PhoneTool.Battery -> "Require permission to read battery"
        PhoneTool.Network -> "Require permission to read network"
        PhoneTool.Sound -> "Require permission to use sound controls"
        PhoneTool.Alarms -> "Require permission to set alarms"
        PhoneTool.ScreenTime -> "Require permission to read screen time"
        PhoneTool.Open -> "Require permission to open links, maps and apps"
        PhoneTool.Clipboard -> "Require permission to copy text"
        PhoneTool.Location -> "Require permission to read location"
        PhoneTool.Geofences -> "Require permission to manage watched places"
        PhoneTool.Calendar -> "Require permission to use calendar"
        PhoneTool.Contacts -> "Require permission to read contacts"
        PhoneTool.Health -> "Require permission to read health"
        PhoneTool.ShowUi -> "Require permission to show screens"
        PhoneTool.CallMe -> "Require permission to call me"
    }
