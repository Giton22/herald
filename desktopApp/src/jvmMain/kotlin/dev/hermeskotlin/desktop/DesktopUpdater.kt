package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.desktop.web.Jcef
import dev.hermeskotlin.ui.update.UpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Installs a release from the update banner. The installer is downloaded first, then:
 * - Windows: the MSI installs over this copy with only its progress bar showing, and Herald opens again after it.
 *   Herald quits first, as the installer can't replace the files of a running app.
 * - macOS and Linux: the DMG or DEB opens in the system's own installer (Finder, the software centre), which asks
 *   the user to finish there.
 *
 * Only an installed Herald does this; one run from a build opens the download in the browser instead.
 */
internal object DesktopUpdater : UpdateInstaller {

    /** Where jpackage's launcher put this copy of Herald; null when it wasn't installed. */
    private val launcher: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    override suspend fun install(update: AppUpdate): Boolean {
        val url = update.downloadUrl ?: return false
        if (launcher == null) return false
        val file = try {
            download(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return if (windows) {
            runMsi(file, launcher)
        } else {
            runCatching { Desktop.getDesktop().open(file) }.isSuccess
        }
    }

    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "herald-update" }
        val dir = File(System.getProperty("java.io.tmpdir"), "herald-update").apply { mkdirs() }
        val part = File(dir, "$name.part")
        val response = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
        if (response.statusCode() !in 200..299) {
            part.delete()
            error("HTTP ${response.statusCode()}")
        }
        File(dir, name).also { Files.move(part.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    /**
     * Hands the MSI to a hidden PowerShell that waits for Herald to quit, installs it (a per-user install, so no
     * admin prompt) and opens Herald again; then quits.
     */
    private fun runMsi(msi: File, launcher: String): Boolean {
        fun quoted(path: String) = "'" + path.replace("'", "''") + "'"
        val script = "Start-Sleep -Seconds 2; " +
            "Start-Process msiexec.exe -ArgumentList '/i', ${quoted("\"${msi.absolutePath}\"")}, '/passive', '/norestart' -Wait; " +
            "Start-Process ${quoted(launcher)}"
        val started = runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script).start()
        }.isSuccess
        if (started) {
            // Chromium's helpers hold its files, which the installer replaces.
            Jcef.shutdown()
            exitProcess(0)
        }
        return false
    }

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
}
