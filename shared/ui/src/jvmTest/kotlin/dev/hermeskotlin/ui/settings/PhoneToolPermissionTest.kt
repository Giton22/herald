package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolsStatus
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PhoneToolPermissionTest {
    @Test fun permissionsAreSeparateFromToolEnablement() = runDesktopComposeUiTest(width = 360, height = 800) {
        setContent { page(AppSettings(phoneTools = true)) }
        PhoneTool.entries.forEach {
            onNodeWithText(it.permissionTitle).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun eachToolHasExactlyOneCompanion() = runDesktopComposeUiTest(width = 360, height = 800) {
        setContent { page(AppSettings(phoneTools = true, phoneToolsOn = PhoneTool.entries.map { it.id })) }
        PhoneTool.entries.forEach {
            onNodeWithText(it.title).assertExists()
            onAllNodesWithText(it.permissionTitle).assertCountEquals(1)
            onNodeWithText(it.permissionTitle).performScrollTo().assertIsEnabled()
        }
    }

    @Test fun turningOffAParentDisablesButRetainsItsPermissionPreference() = runDesktopComposeUiTest(width = 360, height = 640) {
        var settings by mutableStateOf(AppSettings(phoneTools = true, phoneToolsOn = listOf(PhoneTool.Alarms.id)))
        var changes = 0
        setContent {
            page(settings, onTool = { tool, on ->
                settings = settings.copy(phoneToolsOn = if (on) settings.phoneToolsOn + tool.id else settings.phoneToolsOn - tool.id)
            }, onPermission = { tool, on ->
                changes++
                settings = settings.copy(phoneToolsAsk = settings.phoneToolsAsk + (tool.id to on))
            })
        }
        val permission = onNodeWithText("Require permission to set alarms")
        permission.performScrollTo().assertIsOn().performClick().assertIsOff()
        onNodeWithText(PhoneTool.Alarms.title).performScrollTo().performClick()
        permission.performScrollTo().assertIsNotEnabled().assertIsOff().performMouseInput { click() }
        assertEquals(1, changes)
        onNodeWithText(PhoneTool.Alarms.title).performScrollTo().performClick()
        permission.performScrollTo().assertIsEnabled().assertIsOff()
    }

    @Test fun permissionsCanBeConfiguredInLightThemeAt360dp() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent { page(AppSettings(phoneTools = true, phoneToolsOn = listOf(PhoneTool.Open.id)), scheme = ColorScheme.Light) }
        onNodeWithText("Require permission to open links, maps and apps").performScrollTo().assertIsDisplayed().assertIsOn()
        onNodeWithText("Require permission to call me").performScrollTo().assertIsNotEnabled()
    }

    @Test fun masterOffDoesNotOfferAnyToolOrPermissionSwitch() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent { page(AppSettings()) }
        PhoneTool.entries.forEach { onNodeWithText(it.permissionTitle).assertDoesNotExist() }
    }

    @androidx.compose.runtime.Composable
    private fun page(
        settings: AppSettings,
        scheme: ColorScheme = ColorScheme.Dark,
        onTool: (PhoneTool, Boolean) -> Unit = { _, _ -> },
        onPermission: (PhoneTool, Boolean) -> Unit = { _, _ -> },
    ) {
        HermesTheme(scheme) {
            CompositionLocalProvider(LocalDesktopLayout provides false) {
                PhoneToolsView(
                    settings = settings, status = PhoneToolsStatus(PhoneToolsStatus.State.On), recent = emptyList(),
                    onBack = {}, onAll = {}, onTool = onTool, onSetUp = {}, onPermission = onPermission,
                )
            }
        }
    }
}
