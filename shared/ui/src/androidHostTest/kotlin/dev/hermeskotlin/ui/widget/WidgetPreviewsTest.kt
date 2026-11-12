package dev.hermeskotlin.ui.widget

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.preview.PageSamples
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * T15's render half (`docs/WIDGETS-SPEC.md` §11): every sample snapshot renders through the preview
 * composables without throwing, the locked variant of all four included. These are the same
 * composables the preview gallery draws, so a drift that breaks the gallery fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewsTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun everySampleRendersIncludingTheLockedVariants() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                NowWidgetPreview(PageSamples.nowNeedsYou(), NowPreviewState.NeedsYou)
                NowWidgetPreview(PageSamples.nowRunning(), NowPreviewState.Running)
                NowWidgetPreview(PageSamples.nowQuiet(), NowPreviewState.Quiet)
                NowWidgetPreview(PageSamples.nowNeedsYou(), locked = true)
                ChatsWidgetPreview(PageSamples.chatsWidget())
                ChatsWidgetPreview(PageSamples.chatsWidget(), locked = true)
                KanbanWidgetPreview(PageSamples.kanbanWidget())
                KanbanWidgetPreview(PageSamples.kanbanWidget(), locked = true)
                ScheduledWidgetPreview(PageSamples.scheduledWidget())
                ScheduledWidgetPreview(PageSamples.scheduledWidget(), locked = true)
            }
        }
        composeRule.waitForIdle()
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
