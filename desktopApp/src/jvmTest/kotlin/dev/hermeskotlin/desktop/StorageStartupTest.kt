package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.SecureStorageException
import java.util.Locale
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageStartupTest {
    @Test fun everyFailurePromptFitsA360PixelDisplayInEveryLanguage() {
        val folder = "/home/a-much-longer-user-name/.var/app/app.kyllene.Kyllene.Devel/data/herald"
        for (language in LANGUAGES) withLocale(language) {
            val words = runBlocking { StoragePromptWords.load() }
            for (reason in SecureStorageException.Reason.entries) {
                val guidance = runBlocking { storageGuidance(reason) }
                SwingUtilities.invokeAndWait {
                    val pane = storageUnlockPane(reason, guidance, words, folder)
                    assertTrue(pane.preferredSize.width <= 360, "$language ${reason.name}: ${pane.preferredSize.width}px")
                }
            }
        }
    }

    @Test fun theStartFreshNoteNamesItsButtonInEveryLanguage() {
        for (language in LANGUAGES) withLocale(language) {
            val words = runBlocking { StoragePromptWords.load() }
            val guidance = runBlocking { storageGuidance(SecureStorageException.Reason.Corrupt) }
            assertContains(guidance, words.choices.getValue(StorageChoice.StartFresh), message = language)
        }
    }

    @Test fun thePressedButtonIsItsChoice() {
        val words = StoragePromptWords("title", "folder", mapOf(StorageChoice.Retry to "r", StorageChoice.StartFresh to "s", StorageChoice.Quit to "q"))
        val corrupt = SecureStorageException.Reason.Corrupt
        assertEquals(StorageChoice.StartFresh, storageChoiceOf(corrupt, "s", words))
        assertEquals(StorageChoice.Retry, storageChoiceOf(corrupt, "r", words))
        // Closed with the window's own button: nothing pressed.
        assertEquals(StorageChoice.Quit, storageChoiceOf(corrupt, JOptionPane.UNINITIALIZED_VALUE, words))
        assertEquals(StorageChoice.Quit, storageChoiceOf(corrupt, null, words))
    }

    @Test fun theSystemLanguageChoosesTheWords() {
        withLocale("de") { assertEquals("Wiederholen", runBlocking { StoragePromptWords.load() }.choices[StorageChoice.Retry]) }
        withLocale("en") { assertEquals("Retry", runBlocking { StoragePromptWords.load() }.choices[StorageChoice.Retry]) }
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
            assertEquals(runBlocking { storageGuidance(SecureStorageException.Reason.Unavailable) }, guidance)
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
        }, startFresh = { aside = true }, ask = { reason, guidance ->
            assertEquals(SecureStorageException.Reason.Corrupt, reason)
            shown = guidance
            StorageChoice.StartFresh
        }))
        assertTrue(aside)
        val words = runBlocking { StoragePromptWords.load() }
        assertContains(shown.orEmpty(), words.choices.getValue(StorageChoice.StartFresh))
    }

    @Test fun thePromptNamesTheSettingsFolder() {
        val words = StoragePromptWords("title", "folder", StorageChoice.entries.associateWith { it.name })
        SwingUtilities.invokeAndWait {
            val pane = storageUnlockPane(SecureStorageException.Reason.Legacy, "guidance", words, "/fixture/herald")
            val field = (pane.message as Array<*>).filterIsInstance<javax.swing.JTextField>().single()
            assertEquals("/fixture/herald", field.text)
            assertFalse(field.isEditable)
        }
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

/** Every language the desktop app's catalogue has, as system locales. */
internal val LANGUAGES = listOf("en", "de", "fr", "es", "pt-BR", "zh-CN", "hu")

/** Runs [block] with the system language set to [tag], as a user's would be, and puts the old one back. */
internal fun withLocale(tag: String, block: () -> Unit) {
    val old = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag(tag))
    try { block() } finally { Locale.setDefault(old) }
}
