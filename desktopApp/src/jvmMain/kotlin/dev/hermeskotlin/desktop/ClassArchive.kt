package dev.hermeskotlin.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps the class archive an installed Herald on Windows launches with (`-XX:SharedArchiveFile`, see the desktop
 * build) in step with the app. The JVM can make one as the app runs, but runs some ten times slower meanwhile, so a
 * first launch would take half a minute. Instead, a while after a launch that found no archive, or one made for other
 * jars (an update), a hidden Herald at low priority loads the app's classes, draws its screens off screen and writes
 * them out as it exits. The next launch reads them.
 */
internal object ClassArchive {
    /** Herald.exe; null when Herald wasn't installed (run from a build), which has no archive. */
    private val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** The run that makes the archive, rather than a Herald for the user. */
    val recording: Boolean get() = System.getenv(RECORD) != null

    /** In [recording]: loads what the app loads, then exits, which is when the JVM writes the archive. */
    fun record(): Nothing {
        runCatching { Kernel32.INSTANCE.SetPriorityClass(Kernel32.INSTANCE.GetCurrentProcess(), WinDef.DWORD(IDLE_PRIORITY_CLASS)) }
        runCatching { Warmup.runNow() }
        exitProcess(0)
    }

    /** After [delayMs], makes the archive again if it's missing or was made for other jars. */
    fun refreshLater(delayMs: Long = 60_000) {
        val launcher = launcher?.takeIf { windows } ?: return
        val dir = File(launcher.parentFile, "app")
        Thread({
            Thread.sleep(delayMs)
            val stamp = File(dir, "herald.jsa.stamp")
            val jars = jarsStamp(dir)
            if (File(dir, "herald.jsa").isFile && runCatching { stamp.readText() }.getOrNull() == jars) return@Thread
            runCatching {
                // An archive for other jars goes first: the JVM only makes one where there's none.
                File(dir, "herald.jsa").delete()
                val run = ProcessBuilder(launcher.path)
                    .apply {
                        environment()[RECORD] = "1"
                        environment()["JAVA_TOOL_OPTIONS"] = "-XX:+AutoCreateSharedArchive"
                    }
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (run.waitFor() == 0 && File(dir, "herald.jsa").isFile) stamp.writeText(jars)
            }
        }, "class-archive").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** The app's jars by name and size; an update changes it. */
    private fun jarsStamp(dir: File): String =
        dir.listFiles { file -> file.extension == "jar" }.orEmpty()
            .sortedBy { it.name }
            .joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()}" }

    private const val RECORD = "HERALD_RECORD_CLASSES"

    /** Windows' lowest process priority: the recording runs only when nothing else wants the processor. */
    private const val IDLE_PRIORITY_CLASS = 0x40L
}
