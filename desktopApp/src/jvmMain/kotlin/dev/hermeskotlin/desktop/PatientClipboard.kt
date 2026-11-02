package dev.hermeskotlin.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The system clipboard, tried again for a moment while another app has it open: Windows lets one app at a time
 * in, and a clipboard manager often holds it. Compose's own copy (Ctrl+C on selected text) throws then, and the
 * window showed Java's error box for it. Past [TRIES] a copy is dropped and a paste finds nothing.
 */
internal class PatientClipboard(private val system: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = patiently { system.getClipEntry() }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        patiently { system.setClipEntry(clipEntry) }
    }

    /**
     * What a right-click on a text field asks whether there's text to paste. Not given, it threw Kotlin's "An
     * operation is not implemented", and the window showed Java's error box for it. Asking doesn't open the
     * clipboard, so another app holding it doesn't get in the way here.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any
        get() = system.nativeClipboard

    private suspend fun <T> patiently(block: suspend () -> T): T? {
        repeat(TRIES) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                // A cancellation is an IllegalStateException too, and must go on up.
                throw e
            } catch (_: IllegalStateException) {
                if (attempt < TRIES - 1) delay(WAIT_MS)
            }
        }
        return null
    }

    private companion object {
        const val TRIES = 5
        const val WAIT_MS = 40L
    }
}

/** The window's clipboard as a [PatientClipboard], to provide as its [LocalClipboard]. */
@Composable
internal fun rememberPatientClipboard(): Clipboard {
    val system = LocalClipboard.current
    return remember(system) { PatientClipboard(system) }
}
