package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.profiles.ProfileRoster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileToStoreTest {
    private val roster = ProfileRoster(listOf(Profile("default", isDefault = true), Profile("work")), launch = "default")

    @Test
    fun theLaunchProfileIsStoredAsNonePicked() {
        assertNull(profileToStore(Profile("default", isDefault = true), roster))
    }

    @Test
    fun anotherProfileIsStoredByName() {
        assertEquals("work", profileToStore(Profile("work"), roster))
    }

    @Test
    fun aGatewayLaunchedAsAnotherProfileStoresItsDefaultByName() {
        val launchedAsWork = roster.copy(launch = "work")
        assertEquals("default", profileToStore(Profile("default", isDefault = true), launchedAsWork))
        assertNull(profileToStore(Profile("work"), launchedAsWork))
    }

    @Test
    fun withNoRosterYetEveryProfileIsStoredByName() {
        assertEquals("default", profileToStore(Profile("default", isDefault = true), null))
    }

    @Test
    fun noStoredProfileMeansTheLaunchOneIsActive() {
        assertEquals("default", roster.active(null)?.name)
        assertEquals("work", roster.copy(launch = "work").active(null)?.name)
    }

    @Test
    fun aStoredProfileIsActive() {
        assertEquals("work", roster.active("work")?.name)
    }

    @Test
    fun aStoredProfileTheGatewayNoLongerHasIsNoneOfThem() {
        assertNull(roster.active("gone"))
    }

    @Test
    fun aPickedProfileStoredAndReadBackIsTheOneActive() {
        roster.profiles.forEach { picked -> assertEquals(picked, roster.active(profileToStore(picked, roster))) }
    }
}
