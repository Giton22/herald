package dev.hermeskotlin.ui.sessions

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileSheetTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library has no manifest declaring the activity that hosts the real sheet.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private var picked: Profile? = null
    private var visible by mutableStateOf(true)

    private fun show(count: Int, scheme: ColorScheme = ColorScheme.Light, fontScale: Float = 1f, selected: String = "profile-1") {
        composeRule.setContent {
            HermesTheme(scheme) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    ProfileSheet(
                        visible = visible,
                        profiles = (1..count).map { Profile(name = "profile-$it") },
                        selected = selected,
                        onDismiss = { visible = false },
                        onSelect = { picked = it; visible = false },
                    )
                }
            }
        }
        settle()
    }

    @Test
    fun aShortListShowsAndSelectsItsLastProfile() {
        show(3)
        composeRule.onNodeWithText("profile-3").assertIsDisplayed().performClick()
        assertEquals("profile-3", picked?.name)
        settle()
        composeRule.onNodeWithText("Profiles").assertDoesNotExist()
    }

    @Test
    fun aLongListCanScrollToAndSelectItsLastProfile() = selectLastProfile()

    @Test
    fun aLongListOpensWithTheActiveProfileInSight() {
        show(30, selected = "profile-25")
        composeRule.onNodeWithText("profile-25").assertIsDisplayed()
        composeRule.onNodeWithText("profile-1").assertIsNotDisplayed()
        // Only the list moved: the sheet's header is still where it was.
        composeRule.onNodeWithText("Profiles").assertIsDisplayed()
    }

    @Test
    fun aLongListScrollsInDarkTheme() = selectLastProfile(scheme = ColorScheme.Dark)

    @Test
    @Config(qualifiers = "w640dp-h360dp-xhdpi")
    fun aLongListScrollsInLandscape() = selectLastProfile()

    @Test
    fun aLongListScrollsWithLargeText() = selectLastProfile(fontScale = 1.5f)

    private fun selectLastProfile(scheme: ColorScheme = ColorScheme.Light, fontScale: Float = 1f) {
        show(30, scheme, fontScale)
        composeRule.onNodeWithText("profile-30").assertIsNotDisplayed()
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 400) }
        composeRule.onNodeWithText("profile-1").assertIsNotDisplayed()
        composeRule.onNodeWithText("profile-30").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("profile-30", picked?.name)
        settle()
        composeRule.onNodeWithText("Profiles").assertDoesNotExist()
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
