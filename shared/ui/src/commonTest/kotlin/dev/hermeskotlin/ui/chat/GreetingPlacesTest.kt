package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.projects.ProjectsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GreetingPlacesTest {

    private val home = Project(ProjectsApi.NO_PROJECT_ID, "Home", null, sessionCount = 4, isNoProject = true)
    private val site = Project("p1", "Website", "/srv/site", sessionCount = 3)
    private val notes = Project("p2", "Notes", null, sessionCount = 0)

    @Test
    fun theQuestionNamesTheProjectTheChatStartsIn() {
        val projects = listOf(site, home)
        assertEquals("Website", greetingProjectLabel(projects, "/srv/site", "Home"))
        // However the folder's end is written.
        assertEquals("Website", greetingProjectLabel(projects, "/srv/site/", "Home"))
        // A folder the list doesn't know (yet) goes by its own name.
        assertEquals("scratch", greetingProjectLabel(projects, "/srv/scratch", "Home"))
        assertEquals("work", greetingProjectLabel(projects, "C:\\Users\\me\\work", "Home"))
        // No project: Home, as the list calls it, or the app's own word when the list has no Home.
        assertEquals("Home", greetingProjectLabel(projects, null, "Start"))
        assertEquals("Start", greetingProjectLabel(listOf(site), null, "Start"))
    }

    @Test
    fun onlyProjectsWithAFolderAreWorthAPicker() {
        assertFalse(GreetingPlaces(projects = listOf(home)).hasProjects)
        assertFalse(GreetingPlaces(projects = listOf(home, notes)).hasProjects)
        assertTrue(GreetingPlaces(projects = listOf(site, home)).hasProjects)
    }

    @Test
    fun thePlaceIsAPickerOnlyWithSomewhereElseToGo() {
        val one = Profile("default", isDefault = true)
        val gateway = SavedGateway("https://hermes.example")
        assertFalse(GreetingPlaces(profiles = listOf(one), gateways = listOf(gateway)).hasPlaces)
        assertTrue(GreetingPlaces(profiles = listOf(one, Profile("work")), gateways = listOf(gateway)).hasPlaces)
        assertTrue(GreetingPlaces(profiles = listOf(one), gateways = listOf(gateway, SavedGateway("http://100.64.0.2:9119"))).hasPlaces)
    }
}
