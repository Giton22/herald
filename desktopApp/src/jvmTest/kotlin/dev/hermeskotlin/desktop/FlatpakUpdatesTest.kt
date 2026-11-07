package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.update.ManagedUpdatePhase
import dev.hermeskotlin.ui.update.ManagedUpdateState
import kotlin.test.Test
import kotlin.test.assertEquals

class FlatpakUpdatesTest {
    @Test
    fun remoteUpdateDoesNotRequireRestartBeforeInstalling() {
        assertEquals(
            ManagedUpdateState(ManagedUpdatePhase.Available),
            FlatpakUpdateInfo("A", "A", "B").offer(),
        )
    }

    @Test
    fun externallyInstalledUpdateOffersRestart() {
        assertEquals(
            ManagedUpdateState(ManagedUpdatePhase.Ready, canRestart = true),
            FlatpakUpdateInfo("A", "B", "B").offer(),
        )
    }

    @Test
    fun newerRemoteDoesNotHideAnAlreadyInstalledUpdate() {
        assertEquals(
            ManagedUpdateState(ManagedUpdatePhase.Available, canRestart = true),
            FlatpakUpdateInfo("A", "B", "C").offer(),
        )
    }

    @Test
    fun matchingCommitsMakeNoOffer() {
        assertEquals(ManagedUpdateState(), FlatpakUpdateInfo("A", "A", "A").offer())
    }

    @Test
    fun missingInstalledCommitCannotProveEitherAction() {
        assertEquals(ManagedUpdateState(), FlatpakUpdateInfo("A", null, "B").offer())
    }

    @Test
    fun restartDoesNotRequireRemoteInformation() {
        assertEquals(
            ManagedUpdateState(ManagedUpdatePhase.Ready, canRestart = true),
            FlatpakUpdateInfo("A", "B", null).offer(),
        )
    }

    @Test
    fun updateDoesNotRequireRunningCommitInformation() {
        assertEquals(
            ManagedUpdateState(ManagedUpdatePhase.Available),
            FlatpakUpdateInfo(null, "A", "B").offer(),
        )
    }

    @Test
    fun blankCommitsCannotProveAnUpdateOrRestart() {
        for (blank in listOf("", " ", "\r\n")) {
            assertEquals(ManagedUpdateState(), FlatpakUpdateInfo("A", blank, "B").offer())
            assertEquals(ManagedUpdateState(), FlatpakUpdateInfo(blank, "A", blank).offer())
        }
    }
}
