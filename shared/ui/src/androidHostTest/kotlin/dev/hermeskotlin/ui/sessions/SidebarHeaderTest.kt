package dev.hermeskotlin.ui.sessions

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.designsystem.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidebarHeaderTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library has no manifest declaring the activity that hosts the menu.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private var picked: Profile? = null
    private var accountOpened = 0
    private var menuOpened = 0

    private fun show(profiles: List<Profile>, active: String? = profiles.firstOrNull()?.name, gateway: String = "homelab", scheme: ColorScheme = ColorScheme.Light) {
        composeRule.setContent {
            HermesTheme(scheme) {
                MainHeader(
                    gatewayLabel = gateway,
                    connection = null,
                    onAccount = { accountOpened++ },
                    onSettings = {},
                    onSearch = {},
                    profiles = profiles,
                    activeProfile = active,
                    onProfile = { picked = it },
                    onOpenMenu = { menuOpened++ },
                )
            }
        }
        settle()
    }

    private val three = listOf(Profile("default", isDefault = true), Profile("research", displayName = "Research"), Profile("writing", displayName = "Writing"))

    @Test
    fun withProfilesTheHeaderOpensThemAndAPickSwitches() {
        show(three)
        composeRule.onNodeWithText("· default").assertIsDisplayed()
        composeRule.onNodeWithText("Kyllene").performClick()
        settle()
        assertEquals(1, menuOpened)
        assertEquals(0, accountOpened)
        composeRule.onNodeWithText("Writing").assertIsDisplayed().performClick()
        settle()
        assertEquals("writing", picked?.name)
        // The pick closes the menu.
        composeRule.onNodeWithText("Research").assertDoesNotExist()
    }

    @Test
    fun theActiveProfileIsNotPickedAgain() {
        show(three, active = "research", scheme = ColorScheme.Dark)
        composeRule.onNodeWithText("Kyllene").performClick()
        settle()
        composeRule.onAllNodesWithText("Research").filterToOne(hasClickAction()).performClick()
        settle()
        assertNull(picked)
    }

    @Test
    fun theMenuReachesTheAccountSheet() {
        show(three)
        composeRule.onNodeWithText("Kyllene").performClick()
        settle()
        composeRule.onNodeWithText("Account and gateways").performClick()
        assertEquals(1, accountOpened)
    }

    @Test
    fun withOneProfileTheHeaderOpensTheAccountSheet() {
        show(listOf(Profile("default", isDefault = true)))
        composeRule.onNodeWithText("· default").assertDoesNotExist()
        composeRule.onNodeWithText("Kyllene").performClick()
        settle()
        assertEquals(1, accountOpened)
        assertEquals(0, menuOpened)
    }

    @Test
    fun aLongProfileNameLeavesTheGatewayInSight() {
        show(listOf(Profile("default", isDefault = true), Profile("long", displayName = "A research assistant for the whole household")), active = "long")
        composeRule.onNodeWithText("homelab").assertIsDisplayed()
        composeRule.onNodeWithText("· A research assistant for the whole household").assertIsDisplayed()
    }

    @Test
    fun withoutTheSwitchTheBotsSideKeepsNoRoomForSearch() {
        var keepRoom by mutableStateOf(true)
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.testTag("header")) {
                    MainHeader(gatewayLabel = "homelab", connection = null, onAccount = {}, onSettings = {}, onSearch = null, keepSearchRoom = keepRoom)
                }
            }
        }
        settle()
        val kept = composeRule.onNodeWithTag("header").getUnclippedBoundsInRoot().height
        keepRoom = false
        settle()
        val without = composeRule.onNodeWithTag("header").getUnclippedBoundsInRoot().height
        // The pill's room goes: at least a touch target shorter.
        assertTrue(kept - without >= 48.dp, "kept $kept, without $without")
    }

    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.waitForIdle()
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }
}
