package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The header's profile switch: one button, read as the profile and the link's state, that asks for the picker. */
@OptIn(ExperimentalTestApi::class)
class ProfileSwitchRowTest {

    private val longName = "Research assistant for the garden club newsletter and more"

    @Test
    fun aTapAsksForTheProfilePickerAndTheButtonIsReadAsTheProfile() = runComposeUiTest {
        var opened = 0
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                // As narrow as a 360dp phone's drawer, with a name far too long for it.
                Box(Modifier.width(300.dp)) {
                    ProfileSwitchHeader(
                        ProfileSwitch(longName),
                        gatewayLabel = "homelab",
                        connection = ConnectionState.Failed("lost"),
                        onClick = { opened++ },
                        modifier = Modifier.fillMaxWidth().testTag("switch"),
                    )
                }
            }
        }
        val node = onNodeWithTag("switch").fetchSemanticsNode()
        assertEquals(listOf("Profile: $longName, on homelab"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        // The dot on the disc is a colour; the link's state is said in words.
        assertEquals("Not connected", node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Switch profile", node.config.getOrNull(SemanticsActions.OnClick)?.label)

        onNodeWithTag("switch").performClick()
        waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun aLongNameIsCutShortInsideTheSidebarsWidth() = runComposeUiTest {
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                Box(Modifier.width(300.dp).testTag("box")) {
                    ProfileSwitchHeader(ProfileSwitch(longName), "homelab", connection = null, onClick = {}, modifier = Modifier.testTag("switch"))
                }
            }
        }
        val box = onNodeWithTag("box").fetchSemanticsNode().size.width
        val switch = onNodeWithTag("switch").fetchSemanticsNode().size.width
        assertTrue(switch <= box, "The switch ran past the sidebar: $switch > $box")
    }

    @Test
    fun theDiscsBadgeFollowsTheConnection() = runComposeUiTest {
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                Column {
                    for ((tag, connection) in listOf("up" to null, "down" to ConnectionState.Failed("lost"))) {
                        Box(Modifier.width(300.dp).testTag(tag)) {
                            ProfileSwitchHeader(ProfileSwitch("Work"), "homelab", connection, onClick = {})
                        }
                    }
                }
            }
        }
        val up = onNodeWithTag("up").captureToImage().toPixelMap()
        val down = onNodeWithTag("down").captureToImage().toPixelMap()
        // The badge sits at the disc's lower right: 30dp disc, nudged 2dp out, from the switch's start.
        val px = density.density
        val xs = (24 * px).toInt() until (32 * px).toInt()
        val ys = (up.height / 2 + 9 * px).toInt() until (up.height / 2 + 16 * px).toInt()
        val differing = xs.sumOf { x -> ys.count { y -> up[x, y] != down[x, y] } }
        assertNotEquals(0, differing, "The badge looked the same connected and not")
    }
}
