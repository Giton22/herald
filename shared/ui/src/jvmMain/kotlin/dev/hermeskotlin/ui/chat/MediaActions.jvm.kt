package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files

/** Save is the system's Save dialog; with no share sheet, Share opens the file in its default app. */
@Composable
actual fun rememberMediaActions(): MediaActions = remember {
    object : MediaActions {
        override suspend fun save(bytes: ByteArray, name: String): String {
            val file = fromDialog { saveFile("Save", name) }.getOrElse { return "Couldn't open the Save dialog." } ?: return "Not saved."
            return withContext(Dispatchers.IO) {
                runCatching { file.writeBytes(bytes) }.fold({ "Saved to ${file.path}" }, { "Couldn't save: ${it.message}" })
            }
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
