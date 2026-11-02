package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.hermeskotlin.ui.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.io.File
import java.nio.file.Files

/** Save is the system's Save dialog; with no share sheet, Share opens the file in its default app. */
@Composable
actual fun rememberMediaActions(): MediaActions = remember {
    object : MediaActions {
        override suspend fun save(bytes: ByteArray, name: String): String {
            val title = getString(Res.string.desktop_save_title)
            val file = fromDialog { saveFile(title, name) }.getOrElse { return getString(Res.string.desktop_save_dialog_failed) }
                ?: return getString(Res.string.desktop_save_cancelled)
            val written = withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) } }
            return written.fold(
                { getString(Res.string.desktop_saved_to, file.path) },
                { getString(Res.string.desktop_save_failed, it.message.toString()) },
            )
        }

        override fun share(bytes: ByteArray, name: String) {
            runCatching {
                val dir = Files.createTempDirectory("herald").toFile().apply { deleteOnExit() }
                val file = File(dir, name).apply { writeBytes(bytes); deleteOnExit() }
                Desktop.getDesktop().open(file)
            }
        }
    }
}
