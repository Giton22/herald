package dev.hermeskotlin.designsystem.components

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
import kotlin.test.assertEquals

/**
 * The content beside the sidebar when the window crosses the docking point (a foldable unfolded, a tablet turned, a
 * desktop window resized): it keeps what it remembers, as a tool group the reader opened, and docked it sits beside
 * the sidebar and its hairline as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp")
class SidebarKeepsContentTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private val state = SidebarState()
    private var width by mutableStateOf(NARROW)
    private lateinit var scope: CoroutineScope

    private fun show(
        direction: LayoutDirection = LayoutDirection.Ltr,
        sidebar: @androidx.compose.runtime.Composable () -> Unit = {},
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeRule.setContent {
            scope = rememberCoroutineScope()
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.width(width)) {
                        SidebarLayout(state, sidebar = sidebar, dockedBreakpoint = BREAKPOINT, content = content)
                    }
                }
            }
        }
    }

    private fun resize(to: Dp) {
        width = to
        composeRule.waitForIdle()
    }

    @Test
    fun theContentKeepsWhatItRemembersAsTheSidebarDocksAndUndocks() {
        show {
            // Kept across the activity being made again (a fold), and only while it isn't (a window resized).
            var saved by rememberSaveable { mutableIntStateOf(0) }
            var kept by remember { mutableIntStateOf(0) }
            Column {
                BasicText("$saved", Modifier.testTag(SAVED).clickable { saved++ })
                BasicText("$kept", Modifier.testTag(KEPT).clickable { kept++ })
            }
        }
        composeRule.onNodeWithTag(SAVED).performClick()
        composeRule.onNodeWithTag(KEPT).performClick()

        // Unfolded: the sidebar docks.
        resize(WIDE)
        composeRule.onNodeWithTag(SAVED).assertTextEquals("1")
        composeRule.onNodeWithTag(KEPT).assertTextEquals("1")

        // Folded again: a drawer once more.
        resize(NARROW)
        composeRule.onNodeWithTag(SAVED).assertTextEquals("1")
        composeRule.onNodeWithTag(KEPT).assertTextEquals("1")
    }

    @Test
    fun theSidebarKeepsWhatItRemembersAsItDocksAndUndocks() {
        show(sidebar = {
            var kept by remember { mutableIntStateOf(0) }
            BasicText("$kept", Modifier.testTag(KEPT).clickable { kept++ })
        }) {
            Box(Modifier.fillMaxSize().testTag(CONTENT).clickable { error("the chat took a tap meant for the drawer") })
        }
        // Open as a drawer, over the chat: a tap there reaches the sidebar, not the chat under it.
        composeRule.runOnIdle { scope.launch { state.open() } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(KEPT).performClick()
        composeRule.onNodeWithTag(KEPT).assertTextEquals("1")

        resize(WIDE)
        composeRule.onNodeWithTag(KEPT).assertTextEquals("1")

        resize(NARROW)
        composeRule.onNodeWithTag(KEPT).assertTextEquals("1")
    }

    @Test
    fun theShutDrawerIsSkippedByTheKeyboard() {
        lateinit var focus: FocusManager
        show(sidebar = {
            Box(Modifier.testTag(KEPT).focusable())
        }) {
            focus = LocalFocusManager.current
            Box(Modifier.testTag(CONTENT).focusable())
        }
        composeRule.waitForIdle()

        // The sidebar comes first, but shut it's skipped: the first step lands in the chat.
        composeRule.runOnIdle { focus.moveFocus(FocusDirection.Next) }
        composeRule.onNodeWithTag(CONTENT).assertIsFocused()
    }

    @Test
    fun savedStateComesBackWhenTheActivityIsMadeAgainWithTheSidebarDocked() {
        // A foldable unfolded: Android makes the activity again, now wide, and it has to find the chat's saved state
        // where the narrow one left it.
        val restoration = StateRestorationTester(composeRule)
        var window = NARROW
        restoration.setContent {
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.width(window)) {
                    SidebarLayout(rememberSidebarState(), sidebar = {}, dockedBreakpoint = BREAKPOINT) {
                        var saved by rememberSaveable { mutableIntStateOf(0) }
                        BasicText("$saved", Modifier.testTag(SAVED).clickable { saved++ })
                    }
                }
            }
        }
        composeRule.onNodeWithTag(SAVED).performClick()

        window = WIDE
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(SAVED).assertTextEquals("1")
    }

    @Test
    fun dockedTheContentSitsBesideTheSidebarAndItsHairlineAndTakesTheWholeWidthWhenItsClosed() {
        width = WIDE
        show { Box(Modifier.fillMaxSize().testTag(CONTENT)) }
        composeRule.waitForIdle()
        assertContentBetween(DOCKED_WIDTH + HAIRLINE, WIDE)

        composeRule.runOnIdle { scope.launch { state.close() } }
        composeRule.waitForIdle()
        assertContentBetween(0.dp, WIDE)

        composeRule.runOnIdle { scope.launch { state.open() } }
        composeRule.waitForIdle()
        assertContentBetween(DOCKED_WIDTH + HAIRLINE, WIDE)
    }

    @Test
    fun rightToLeftTheDockedSidebarIsOnTheRight() {
        width = WIDE
        show(LayoutDirection.Rtl) { Box(Modifier.fillMaxSize().testTag(CONTENT)) }
        composeRule.waitForIdle()
        assertContentBetween(0.dp, WIDE - DOCKED_WIDTH - HAIRLINE)
    }

    private fun assertContentBetween(left: Dp, right: Dp) {
        val bounds = composeRule.onNodeWithTag(CONTENT).getUnclippedBoundsInRoot()
        assertEquals(left, bounds.left)
        assertEquals(right, bounds.right)
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

    private companion object {
        const val CONTENT = "content"
        const val SAVED = "saved"
        const val KEPT = "kept"
        val BREAKPOINT = 600.dp
        val NARROW = 400.dp
        val WIDE = 900.dp
        val HAIRLINE = 1.dp
    }
}
