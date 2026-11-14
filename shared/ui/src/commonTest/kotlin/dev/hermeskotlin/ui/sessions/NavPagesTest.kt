package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.withSidebarPage
import dev.hermeskotlin.ui.SidebarPage
import dev.hermeskotlin.ui.showInMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavPagesTest {

    private val phonePages = listOf(
        SidebarPage.Inbox,
        SidebarPage.Scheduled,
        SidebarPage.Insights,
        SidebarPage.Kanban,
        SidebarPage.Capabilities,
        SidebarPage.Plugins,
        SidebarPage.Config,
        SidebarPage.Archived,
    )

    @Test
    fun byDefaultEveryPageHasItsButtonAsBefore() {
        assertEquals(phonePages, shownNavPages(AppSettings(), desktop = false))
        // Before the settings are read, the sidebar doesn't flash without them.
        assertEquals(phonePages, shownNavPages(null, desktop = false))
    }

    @Test
    fun kanbanIsAMainViewEvenWhereOtherPagesStayInTheDrawer() {
        assertTrue(showInMain(desktopPages = false, pageKey = "kanban"))
        assertFalse(showInMain(desktopPages = false, pageKey = null))
        assertFalse(showInMain(desktopPages = false, pageKey = "inbox"))
        assertTrue(showInMain(desktopPages = true, pageKey = null))
    }

    @Test
    fun aDesktopHasNoInbox() {
        assertEquals(phonePages - SidebarPage.Inbox, shownNavPages(AppSettings(), desktop = true))
        assertEquals(phonePages - SidebarPage.Inbox, navPages(desktop = true))
    }

    @Test
    fun theWholeSectionOffLeavesNoButtons() {
        assertEquals(emptyList(), shownNavPages(AppSettings(sidebarNav = false), desktop = false))
        assertEquals(emptyList(), shownNavPages(AppSettings(sidebarNav = false), desktop = true))
    }

    @Test
    fun hiddenPagesGoAndTheRestKeepTheirOrder() {
        val settings = AppSettings()
            .withSidebarPage(SidebarPage.Inbox.name, shown = false)
            .withSidebarPage(SidebarPage.Config.name, shown = false)
        assertEquals(
            listOf(SidebarPage.Scheduled, SidebarPage.Insights, SidebarPage.Kanban, SidebarPage.Capabilities, SidebarPage.Plugins, SidebarPage.Archived),
            shownNavPages(settings, desktop = false),
        )
        // Back on, the section has them as they were left.
        assertEquals(shownNavPages(settings, desktop = false), shownNavPages(settings.copy(sidebarNav = false).copy(sidebarNav = true), desktop = false))
    }

    @Test
    fun aNameThisVersionDoesNotKnowChangesNothing() {
        // A page an older or newer version hid, under a name this one has no page for.
        assertEquals(phonePages, shownNavPages(AppSettings(sidebarNavHidden = listOf("Gallery")), desktop = false))
    }
}
