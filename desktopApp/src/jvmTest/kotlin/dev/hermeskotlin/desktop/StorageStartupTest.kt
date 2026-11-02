package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.SecureStorageException
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageStartupTest {
    @Test fun everyFailurePromptFitsA360PixelDisplay() {
        SwingUtilities.invokeAndWait {
            for (reason in SecureStorageException.Reason.entries) {
                val pane = storageUnlockPane(reason, storageGuidance(reason, "/home/user/.var/app/io.example.App/data/herald"))
                assertTrue(pane.preferredSize.width <= 360, "${reason.name}: ${pane.preferredSize.width}px")
            }
        }
    }

    @Test fun successfulUnlockNeedsNoPrompt() {
        var calls = 0
        assertTrue(unlockDesktopStorage(prepare = { calls++ }, ask = { _, _ -> error("No prompt is needed") }))
        assertEquals(1, calls)
    }

    @Test fun retryStartsOnlyAfterTheFailedRequestHasClosed() {
        var calls = 0
        var active = false
        var prompts = 0
        assertTrue(unlockDesktopStorage(prepare = {
            active = true
            try {
                if (calls++ == 0) throw SecureStorageException(SecureStorageException.Reason.Unavailable)
            } finally { active = false }
        }, ask = { _, guidance ->
            assertFalse(active)
            assertContains(guidance, "keyring")
            prompts++
            StorageChoice.Retry
        }))
        assertEquals(2, calls)
        assertEquals(1, prompts)
    }

    @Test fun quitDoesNotStartAnotherUnlockAttempt() {
        var calls = 0
        assertFalse(unlockDesktopStorage(prepare = {
            calls++
            throw SecureStorageException(SecureStorageException.Reason.Cancelled)
        }, ask = { _, _ -> StorageChoice.Quit }))
        assertEquals(1, calls)
    }

    @Test fun unreadableSettingsCanBeSetAsideToStartFresh() {
        var aside = false
        var shown: String? = null
        assertTrue(unlockDesktopStorage(prepare = {
            if (!aside) throw SecureStorageException(SecureStorageException.Reason.Corrupt)
        }, startFresh = { aside = true }, folder = "/fixture/herald", ask = { reason, guidance ->
            assertEquals(SecureStorageException.Reason.Corrupt, reason)
            shown = guidance
            StorageChoice.StartFresh
        }))
        assertTrue(aside)
        assertContains(shown.orEmpty(), "Start fresh")
        assertContains(shown.orEmpty(), "/fixture/herald")
    }

    @Test fun retryIsOfferedOnlyWhereItCanSucceed() {
        for (reason in listOf(SecureStorageException.Reason.Changed, SecureStorageException.Reason.Unsupported,
            SecureStorageException.Reason.Version)) {
            assertEquals(listOf(StorageChoice.Quit), storageChoices(reason), reason.name)
        }
        assertEquals(listOf(StorageChoice.Retry, StorageChoice.Quit), storageChoices(SecureStorageException.Reason.Unavailable))
        assertTrue(StorageChoice.StartFresh in storageChoices(SecureStorageException.Reason.Corrupt))
    }

    @Test fun unlockNeverRunsOnTheAwtEventThread() {
        var calls = 0
        SwingUtilities.invokeAndWait {
            val failure = assertFailsWith<IllegalStateException> {
                unlockDesktopStorage(prepare = { calls++ }, ask = { _, _ -> error("No prompt") })
            }
            assertContains(failure.message.orEmpty(), "event thread")
        }
        assertEquals(0, calls)
    }

    @Test fun cancellationDoesNotBecomeARetryPrompt() {
        assertFailsWith<CancellationException> {
            unlockDesktopStorage(prepare = { throw CancellationException("synthetic cancellation") },
                ask = { _, _ -> error("Cancellation must escape") })
        }
    }
}
