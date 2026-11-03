package dev.hermeskotlin.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderPathTest {

    @Test
    fun browsingStartsAtHomeOrWhatTheFieldHolds() {
        assertEquals("~/", FolderPath.dirOf("  "))
        assertEquals("/srv/herald/", FolderPath.dirOf("/srv/herald"))
        assertEquals("C:/Users/you/", FolderPath.dirOf("C:\\Users\\you\\"))
    }

    @Test
    fun aNewFolderIsOneNameNotAPath() {
        assertTrue(FolderPath.isName("versal"))
        assertTrue(FolderPath.isName(".config"))
        for (bad in listOf("", "  ", ".", "..", "a/b", "a\\b")) assertFalse(FolderPath.isName(bad), bad)
    }

    @Test
    fun onlyAFolderFromARootOrHomeCanHaveOneMadeInIt() {
        for (dir in listOf("~/", "~/src/", "/", "/srv/", "C:/", "c:/Users/")) assertTrue(FolderPath.isAbsolute(dir), dir)
        for (dir in listOf("projects/", "./", "~x/", "C:")) assertFalse(FolderPath.isAbsolute(dir), dir)
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
