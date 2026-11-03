package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarDocksTest {

    @Test
    fun aPhoneKeepsTheDrawer() {
        assertFalse(sidebarDocks(360.dp, 780.dp, 0.dp))
        assertFalse(sidebarDocks(412.dp, 915.dp, 0.dp))
    }

    @Test
    fun aPhoneOnItsSideKeepsTheDrawer() {
        // Wide enough for the chat beside the sidebar, but too short for a sidebar that stays open.
        assertFalse(sidebarDocks(780.dp, 360.dp, 0.dp))
        assertFalse(sidebarDocks(800.dp, 360.dp, 24.dp))
        assertFalse(sidebarDocks(839.dp, 400.dp, 0.dp))
    }

    @Test
    fun itDocksFromATabletsHeight() {
        assertFalse(sidebarDocks(700.dp, 479.dp, 0.dp))
        assertTrue(sidebarDocks(700.dp, 480.dp, 0.dp))
    }

    @Test
    fun anUnfoldedFoldableDocks() {
        // A Galaxy Z Fold 7's inner screen at 420 dpi, held either way.
        assertTrue(sidebarDocks(750.dp, 832.dp, 0.dp))
        assertTrue(sidebarDocks(832.dp, 750.dp, 0.dp))
    }

    @Test
    fun itDocksOnceTheChatGetsAPhonesWidth() {
        // The sidebar and its 1 dp divider take 305 dp; the chat beside them keeps at least 360 dp.
        assertFalse(sidebarDocks(664.dp, 900.dp, 0.dp))
        assertTrue(sidebarDocks(665.dp, 900.dp, 0.dp))
    }

    @Test
    fun sideInsetsComeOffTheChatsWidth() {
        // A side navigation bar the chat pads itself by.
        assertFalse(sidebarDocks(665.dp, 900.dp, 48.dp))
        assertFalse(sidebarDocks(712.dp, 900.dp, 48.dp))
        assertTrue(sidebarDocks(713.dp, 900.dp, 48.dp))
    }

    @Test
    fun aWiderSidebarNeedsAWiderWindow() {
        assertTrue(sidebarDocks(700.dp, 900.dp, 0.dp))
        assertFalse(sidebarDocks(700.dp, 900.dp, 0.dp, sidebarWidth = 400.dp))
        assertTrue(sidebarDocks(761.dp, 900.dp, 0.dp, sidebarWidth = 400.dp))
    }

    @Test
    fun aSmallTabletUprightKeepsTheDrawer() {
        assertFalse(sidebarDocks(600.dp, 960.dp, 0.dp))
    }

    @Test
    fun whatDockedBeforeStillDocks() {
        // The old rule docked from 840 dp, whatever the height: a wide phone on its side, a tablet.
        assertTrue(sidebarDocks(840.dp, 360.dp, 0.dp))
        assertTrue(sidebarDocks(915.dp, 412.dp, 48.dp))
        assertTrue(sidebarDocks(1280.dp, 800.dp, 0.dp))
    }
}
