package dev.hermeskotlin.ui.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.settings.AppSettings
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
import kotlin.test.assertTrue

/**
 * Settings › Connected devices on a 360 dp phone, every prompt filled with a long one: each keeps its Remove button
 * at full size, and nothing runs off the screen's edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectedDevicesFitTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun longPromptsKeepTheirRemoveButtonsOnANarrowScreen() {
        val prompts = List(6) { "Check every deploy pipeline and tell me which ones failed overnight, number $it" }
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                ConnectedDevicesView(AppSettings(pebbleAnswers = true, pebblePrompts = prompts), onUpdate = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()
        val width = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val touch = with(composeRule.density) { 48.dp.toPx() }
        val removes = composeRule.onAllNodes(hasContentDescription("Remove this prompt")).fetchSemanticsNodes()
        assertEquals(prompts.size, removes.size)
        for (remove in removes) {
            // Laid out, not as shown: the last ones are below the fold, where what shows of them is nothing.
            val right = remove.positionInRoot.x + remove.size.width
            assertTrue(remove.size.width >= touch - 1, "Remove is ${remove.size.width}px wide, expected ${touch}px")
            assertTrue(right <= width + 1, "Remove ends at ${right}px, past the ${width}px screen")
        }
        // At the most it keeps, there's no room to add another.
        assertTrue(composeRule.onAllNodes(hasText("Add a prompt")).fetchSemanticsNodes().isEmpty())
    }

    /** Registers the Compose host activity Robolectric would otherwise fail to resolve. */
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
