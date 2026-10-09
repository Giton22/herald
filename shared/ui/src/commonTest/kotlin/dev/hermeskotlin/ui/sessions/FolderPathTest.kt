package dev.hermeskotlin.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderPathTest {

    @Test
    fun browsingStartsAtHomeOrWhatTheFieldHolds() {
        assertEquals("~/", FolderPath.dirOf("  "))
        assertEquals("/srv/herald/", FolderPath.dirOf("/srv/herald"))
        assertEquals("C:/Users/you/", FolderPath.dirOf("C:\\Users\\you\\"))
    }

    @Test
    fun upGoesOneFolderAndStopsAtARoot() {
        assertEquals("~/", FolderPath.parent("~/src/"))
        assertEquals("/", FolderPath.parent("~/"))
        assertEquals("/", FolderPath.parent("/srv/"))
        assertEquals("C:/", FolderPath.parent("C:/Users/"))
        assertNull(FolderPath.parent("/"))
        assertNull(FolderPath.parent("C:/"))
    }

    @Test
    fun aPickedFolderFillsTheFieldAndNamesTheProject() {
        assertEquals("~/src/herald", FolderPath.picked(FolderPath.child("~/src/", "herald")))
        assertEquals("herald", FolderPath.name("~/src/herald/"))
        assertEquals("/", FolderPath.picked("/"))
        assertEquals("C:/", FolderPath.picked("C:/"))
        assertEquals("projects", FolderPath.picked("projects/"))
        assertNull(FolderPath.name("~/"))
        assertNull(FolderPath.name("/"))
        assertNull(FolderPath.name("C:/"))
    }
}
