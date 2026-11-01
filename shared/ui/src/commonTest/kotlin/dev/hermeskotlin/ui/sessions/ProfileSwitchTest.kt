package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.profiles.ProfileRoster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the sidebar's profile switch shows, and which profile it names. */
class ProfileSwitchTest {

    private val main = Profile("default", isDefault = true)
    private val work = Profile("work", displayName = "Work assistant")
    private val home = Profile("home")
    private val roster = ProfileRoster(listOf(main, work, home), launch = "default")

    @Test
    fun aGatewayWithOneProfileShowsNoSwitch() {
        assertNull(profileSwitch(ProfileRoster(listOf(main), launch = "default"), picked = null))
    }

    @Test
    fun aGatewayThatListsNoProfilesShowsNoSwitch() {
        assertNull(profileSwitch(ProfileRoster(emptyList(), launch = "default", launchKnown = false), picked = null))
    }

    @Test
    fun nothingShowsWhileTheListIsOnItsWayForTheGatewaysOwnProfile() {
        // An older gateway never answers with a list: nothing comes and goes for it.
        assertNull(profileSwitch(roster = null, picked = null))
    }

    @Test
    fun aPickedProfileIsNamedBeforeTheListComes() {
        assertEquals(ProfileSwitch("work"), profileSwitch(roster = null, picked = "work"))
    }

    @Test
    fun theLaunchProfileIsNamedByItsLabel() {
        assertEquals(ProfileSwitch("default"), profileSwitch(roster, picked = null))
        val launchedAsWork = roster.copy(launch = "work")
        assertEquals(ProfileSwitch("Work assistant"), profileSwitch(launchedAsWork, picked = null))
    }

    @Test
    fun aPickedProfileIsNamedByItsDisplayName() {
        assertEquals(ProfileSwitch("Work assistant"), profileSwitch(roster, picked = "work"))
        assertEquals(ProfileSwitch("home"), profileSwitch(roster, picked = "home"))
    }

    @Test
    fun anUnsaidLaunchProfileIsntGuessed() {
        // The gateway didn't say which profile it runs as: "default" is only a guess, so it isn't named.
        val guessed = roster.copy(launchKnown = false)
        assertEquals(ProfileSwitch(UNKNOWN_LAUNCH_LABEL), profileSwitch(guessed, picked = null))
        // A picked one is known whatever the gateway runs as.
        assertEquals(ProfileSwitch("Work assistant"), profileSwitch(guessed, picked = "work"))
    }

    @Test
    fun aPickedProfileTheGatewayNoLongerListsStillShowsTheWayBack() {
        // Deleted on the host, leaving the gateway with one profile: the switch stays, so the user can leave it.
        val alone = ProfileRoster(listOf(main), launch = "default")
        assertEquals(ProfileSwitch("gone"), profileSwitch(alone, picked = "gone"))
    }

    @Test
    fun theAccountSheetNamesTheProfileAsTheSwitchDoes() {
        val guessed = roster.copy(launchKnown = false)
        assertEquals(UNKNOWN_LAUNCH_LABEL, profileLabel(guessed, picked = null))
        assertEquals("default", profileLabel(roster, picked = null))
        assertEquals("Work assistant", profileLabel(guessed, picked = "work"))
        assertEquals("gone", profileLabel(roster, picked = "gone"))
        assertEquals("work", profileLabel(roster = null, picked = "work"))
        assertNull(profileLabel(roster = null, picked = null))
        // Wherever the switch shows, it says the same name.
        for (r in listOf(roster, guessed)) for (p in listOf(null, "work", "home", "gone")) {
            assertEquals(profileLabel(r, p), profileSwitch(r, p)?.label)
        }
    }

    @Test
    fun anEmojiInitialIsKeptWhole() {
        assertEquals("W", profileInitial("work"))
        assertEquals("\uD83E\uDD16", profileInitial("\uD83E\uDD16 Robot"))
        assertEquals("É", profileInitial("élan"))
        assertEquals("", profileInitial(""))
    }

    @Test
    fun theUnsaidLaunchProfileIsNamedInTheWordsGiven() {
        val guessed = roster.copy(launchKnown = false)
        assertEquals(ProfileSwitch("Standard des Gateways"), profileSwitch(guessed, picked = null, unknownLaunch = "Standard des Gateways"))
    }
}
