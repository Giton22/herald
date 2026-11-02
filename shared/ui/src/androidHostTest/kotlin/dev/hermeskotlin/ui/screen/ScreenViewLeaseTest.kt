package dev.hermeskotlin.ui.screen

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.ScreenStage
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

/**
 * What the user may do to the bot's screen follows the lease, not the page.
 *
 * The app knows who holds the screen; the page is only the one that sends the user's taps to it. So the
 * pane tells the viewer what the user may do, whenever the lease changes — and again when the screen is
 * showing, because a page that was not up yet could not hear it the first time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenViewLeaseTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library has no manifest declaring the activity that hosts the pane.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

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

    /** A viewer that only remembers what it was told. */
    private class FakeViewer : ScreenViewerHandle {
        val told = mutableListOf<Boolean>()

        override fun viewOnly(value: Boolean) {
            told += value
        }

        override fun key(keysym: Int, down: Boolean) = Unit

        override fun setZoom(factor: Float) = Unit
    }

    private val status = DisplayStatus(profile = "scribe", supported = true, installed = true, running = true)

    private fun state(holding: Boolean) = ScreenUiState(
        supported = true,
        status = status,
        lease = DisplayLease(if (holding) DisplayLease.HUMAN else DisplayLease.AGENT),
        stage = ScreenStage.Connected,
        holding = holding,
        streamUrl = "wss://gateway.example/display",
    )

    @Test
    fun theViewerIsToldWhatTheUserMayDo() {
        val viewer = FakeViewer()
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                ScreenView(
                    state = state(holding = false),
                    title = "Scribe",
                    onBack = {},
                    onStart = {},
                    onReopen = {},
                    onTakeOver = {},
                    onHandBack = {},
                    screen = { onHandle, _ -> onHandle(viewer) },
                )
            }
        }
        composeRule.waitForIdle()

        // The bot is in control: the user's taps are the bot's business, not the user's.
        assertEquals(listOf(true), viewer.told)
    }

    @Test
    fun theViewerIsToldWhenTheLeaseChanges() {
        val viewer = FakeViewer()
        var holding by mutableStateOf(false)
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                ScreenView(
                    state = state(holding = holding),
                    title = "Scribe",
                    onBack = {},
                    onStart = {},
                    onReopen = {},
                    onTakeOver = {},
                    onHandBack = {},
                    screen = { onHandle, _ -> onHandle(viewer) },
                )
            }
        }
        composeRule.waitForIdle()

        // The user takes the screen over: the page may not keep the user out of it.
        holding = true
        composeRule.waitForIdle()

        assertEquals(listOf(true, false), viewer.told)
    }
}
