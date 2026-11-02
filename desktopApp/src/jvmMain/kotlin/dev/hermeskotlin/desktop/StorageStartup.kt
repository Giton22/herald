package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.SecureStorageException
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal enum class StorageChoice(val label: String) { Retry("Retry"), StartFresh("Start fresh"), Quit("Quit") }

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

/** The prompt's text: the reason's guidance and what Start fresh does when offered. */
internal fun storageGuidance(reason: SecureStorageException.Reason): String = buildString {
    append(reason.guidance)
    if (StorageChoice.StartFresh in storageChoices(reason)) {
        append(" Start fresh keeps that file under another name and opens Kyllene signed out.")
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
            when (ask(failure.reason, storageGuidance(failure.reason))) {
                StorageChoice.Retry -> Unit
                // If setting the file aside fails, the next attempt fails the same way and asks again.
                StorageChoice.StartFresh -> try { runBlocking(Dispatchers.IO) { startFresh() } } catch (_: SecureStorageException) {}
                StorageChoice.Quit -> return false
            }
        }
    }
}

/** The [folder] goes in a read-only field of fixed width, so a long path scrolls instead of widening the prompt, and copies. */
internal fun storageUnlockPane(reason: SecureStorageException.Reason, guidance: String, folder: String? = null): JOptionPane {
    val text = guidance.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val message = listOfNotNull<Any>(
        "<html><body style='width:200px'>$text</body></html>",
        folder?.let { "Settings folder:" },
        folder?.let { JTextField(it, 18).apply { isEditable = false; caretPosition = 0 } },
    ).toTypedArray()
    val choices = storageChoices(reason).map { it.label }.toTypedArray()
    return JOptionPane(message, JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null, choices, choices.first())
}

private fun askStorage(reason: SecureStorageException.Reason, guidance: String, folder: String?): StorageChoice {
    var choice = StorageChoice.Quit
    SwingUtilities.invokeAndWait {
        val pane = storageUnlockPane(reason, guidance, folder)
        val dialog = pane.createDialog(null, "Kyllene storage")
        try {
            dialog.isVisible = true
            choice = StorageChoice.entries.firstOrNull { it.label == pane.value } ?: StorageChoice.Quit
        } finally { dialog.dispose() }
    }
    return choice
}
