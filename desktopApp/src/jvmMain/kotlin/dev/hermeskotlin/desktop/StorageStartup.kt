package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.SecureStorageException
import dev.hermeskotlin.desktop.resources.*
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal enum class StorageChoice(val label: StringResource) {
    Retry(Res.string.storage_retry),
    StartFresh(Res.string.storage_start_fresh),
    Quit(Res.string.storage_quit),
}

/**
 * What the prompt offers. Retry only where it can succeed: a store that saw another window's change, or a format or
 * identity this build can't use, fails the same way until the app restarts. Settings that won't open with this key
 * can be set aside to start signed out.
 */
internal fun storageChoices(reason: SecureStorageException.Reason): List<StorageChoice> = when (reason) {
    SecureStorageException.Reason.Corrupt -> listOf(StorageChoice.Retry, StorageChoice.StartFresh, StorageChoice.Quit)
    SecureStorageException.Reason.Changed, SecureStorageException.Reason.Unsupported,
    SecureStorageException.Reason.Version -> listOf(StorageChoice.Quit)
    else -> listOf(StorageChoice.Retry, StorageChoice.Quit)
}

/** The reason's guidance in the system's language; [SecureStorageException.Reason.guidance] is its English. */
internal fun storageReasonText(reason: SecureStorageException.Reason): StringResource = when (reason) {
    SecureStorageException.Reason.Unavailable -> Res.string.storage_unavailable
    SecureStorageException.Reason.Cancelled -> Res.string.storage_cancelled
    SecureStorageException.Reason.Corrupt -> Res.string.storage_corrupt
    SecureStorageException.Reason.Version -> Res.string.storage_version
    SecureStorageException.Reason.Read -> Res.string.storage_read
    SecureStorageException.Reason.Write -> Res.string.storage_write
    SecureStorageException.Reason.Uncertain -> Res.string.storage_uncertain
    SecureStorageException.Reason.Changed -> Res.string.storage_changed
    SecureStorageException.Reason.Legacy -> Res.string.storage_legacy
    SecureStorageException.Reason.Unsupported -> Res.string.storage_unsupported
}

/** The prompt's text: the reason's guidance and what Start fresh does when offered. */
internal suspend fun storageGuidance(reason: SecureStorageException.Reason): String {
    val guidance = getString(storageReasonText(reason))
    return if (StorageChoice.StartFresh in storageChoices(reason)) getString(Res.string.storage_with_start_fresh, guidance) else guidance
}

/** The prompt's own words, read before it opens on the event thread. */
internal class StoragePromptWords(val title: String, val folder: String, val choices: Map<StorageChoice, String>) {
    companion object {
        suspend fun load() = StoragePromptWords(
            title = getString(Res.string.storage_title),
            folder = getString(Res.string.storage_folder),
            choices = StorageChoice.entries.associateWith { getString(it.label) },
        )
    }
}

/** The same startup attempt waits for an explicit retry; eager consumers have not been created yet. */
internal fun unlockDesktopStorage(
    prepare: suspend () -> Unit,
    startFresh: suspend () -> Unit = {},
    folder: String? = null,
    ask: (SecureStorageException.Reason, String) -> StorageChoice = { reason, guidance -> askStorage(reason, guidance, folder) },
): Boolean {
    check(!SwingUtilities.isEventDispatchThread()) { "Storage unlock must not run on the AWT event thread." }
    while (true) {
        try {
            runBlocking(Dispatchers.IO) { prepare() }
            return true
        } catch (failure: SecureStorageException) {
            when (ask(failure.reason, runBlocking { storageGuidance(failure.reason) })) {
                StorageChoice.Retry -> Unit
                // If setting the file aside fails, the next attempt fails the same way and asks again.
                StorageChoice.StartFresh -> try { runBlocking(Dispatchers.IO) { startFresh() } } catch (_: SecureStorageException) {}
                StorageChoice.Quit -> return false
            }
        }
    }
}

/** The [folder] goes in a read-only field of fixed width, so a long path scrolls instead of widening the prompt, and copies. */
internal fun storageUnlockPane(
    reason: SecureStorageException.Reason,
    guidance: String,
    words: StoragePromptWords,
    folder: String? = null,
): JOptionPane {
    val text = guidance.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val message = listOfNotNull<Any>(
        "<html><body style='width:200px'>$text</body></html>",
        folder?.let { words.folder },
        folder?.let { JTextField(it, 18).apply { isEditable = false; caretPosition = 0 } },
    ).toTypedArray()
    val choices = storageChoices(reason).map { words.choices.getValue(it) }.toTypedArray()
    return JOptionPane(message, JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null, choices, choices.first())
}

/** The choice whose button was pressed; Quit when the prompt was closed instead. */
internal fun storageChoiceOf(reason: SecureStorageException.Reason, pressed: Any?, words: StoragePromptWords): StorageChoice =
    storageChoices(reason).firstOrNull { words.choices[it] == pressed } ?: StorageChoice.Quit

private fun askStorage(reason: SecureStorageException.Reason, guidance: String, folder: String?): StorageChoice {
    val words = runBlocking { StoragePromptWords.load() }
    var choice = StorageChoice.Quit
    SwingUtilities.invokeAndWait {
        val pane = storageUnlockPane(reason, guidance, words, folder)
        val dialog = pane.createDialog(null, words.title)
        try {
            dialog.isVisible = true
            choice = storageChoiceOf(reason, pane.value, words)
        } finally { dialog.dispose() }
    }
    return choice
}
