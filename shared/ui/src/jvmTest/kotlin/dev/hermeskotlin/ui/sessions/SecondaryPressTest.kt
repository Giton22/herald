package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** A row's right-click menu opens for the row as it is now, not as it was first drawn. */
@OptIn(ExperimentalTestApi::class)
class SecondaryPressTest {

    @Test
    fun rightClickActsOnTheRowAsItIsNow() = runComposeUiTest {
        var row by mutableStateOf("Old name")
        var opened: String? = null
        setContent {
            val current = row
            Box(Modifier.size(200.dp, 40.dp).testTag("row").onSecondaryPress { opened = current })
        }
        onNodeWithTag("row").performMouseInput { rightClick(center) }
        waitForIdle()
        assertEquals("Old name", opened)

        // Renamed elsewhere: the next right-click opens the menu for the new name.
        row = "New name"
        waitForIdle()
        onNodeWithTag("row").performMouseInput { rightClick(center) }
        waitForIdle()
        assertEquals("New name", opened)
    }
}
