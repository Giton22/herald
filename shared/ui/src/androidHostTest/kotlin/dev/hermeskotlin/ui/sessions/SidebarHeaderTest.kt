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
import com.composeunstyled.Text
import dev.hermeskotlin.ui.bots.BotsRoster
import dev.hermeskotlin.ui.bots.BotsUiState

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
    fun theRosterDrawsItsTopAboveTheBots() {
        // The sidebar's page buttons when it has no Chats / Bots switch, so the Bots side still reaches the pages.
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                BotsRoster(
                    state = BotsUiState(loading = false),
                    avatars = emptyMap(),
                    selectedId = null,
                    selectedRunning = false,
                    nowSeconds = 0.0,
                    actions = PreviewBotActions,
                    onRetry = {},
                    onDismissNotice = {},
                    top = { Text("Page buttons") },
                )
            }
        }
        settle()
        val top = composeRule.onNodeWithText("Page buttons").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bots = composeRule.onNodeWithText("No bots on this gateway yet.").getUnclippedBoundsInRoot()
        assertTrue(top.bottom <= bots.top, "top ${top.bottom}, bots ${bots.top}")
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
