package dev.hermeskotlin.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopActionsTest {
    @Test fun canonicalAssistantActionOpensTheExistingWindowOncePerRequest() {
        var opens = 0
        assertTrue(handleQuickEntryLink("herald://quick-entry") { opens++ })
        assertEquals(1, opens)
        assertTrue(handleQuickEntryLink("HERALD://QUICK-ENTRY") { opens++ })
        assertEquals(2, opens)
    }

    @Test fun otherLinksAndUnexpectedPayloadsDoNotOpenTheAssistant() {
        for (link in listOf("hermes://quick-entry", "https://quick-entry", "herald://new-chat",
            "herald://quick-entry/child", "herald://quick-entry?prompt=send", "herald://quick-entry#fragment",
            "herald://quick-entry\n", "herald://quick-entry\r", "herald://user@quick-entry", "herald://quıck-entry", "herald://quİck-entry", "herald://quicK-entry", "")) {
            var opened = false
            assertFalse(handleQuickEntryLink(link) { opened = true }, link)
            assertFalse(opened, link)
        }
    }

    @Test fun assistantRequestsForwardToTheExistingRouteWithoutAnotherOwner() {
        val directory = Files.createTempDirectory("kyllene launcher café ").toFile()
        val java = File(System.getProperty("java.home"), if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java").path
        val classpath = listOf(SingleInstance::class.java, LauncherInstanceProbe::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val processes = ArrayList<Process>()
        fun launch(vararg args: String): Process = ProcessBuilder(java, "-cp", classpath,
            LauncherInstanceProbe::class.java.name, directory.path, *args).redirectErrorStream(true).start().also(processes::add)
        try {
            val first = launch("listen")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!File(directory, "ready").exists() && first.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(File(directory, "ready").exists(), "The owned instance must be listening")
            for (link in listOf("herald://quick-entry", "HERALD://QUICK-ENTRY")) {
                val sender = launch(link)
                assertTrue(sender.waitFor(5, TimeUnit.SECONDS), "The forwarding launch must exit")
                assertEquals(0, sender.exitValue(), sender.inputStream.bufferedReader().readText())
            }
            assertTrue(first.waitFor(5, TimeUnit.SECONDS), "Both messages must reach the original instance")
            assertEquals(0, first.exitValue(), first.inputStream.bufferedReader().readText())
            assertEquals(listOf("true:herald://quick-entry", "true:HERALD://QUICK-ENTRY"), File(directory, "received").readLines())
        } finally {
            processes.forEach { if (it.isAlive) it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            directory.deleteRecursively()
        }
    }
}

/** A bounded owned JVM lets the process-lifetime lock and listener retire with their process. */
object LauncherInstanceProbe {
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0])
        if (args[1] != "listen") exitProcess(if (SingleInstance.claim(directory, args[1]) == null) 0 else 1)
        val instance = checkNotNull(SingleInstance.claim(directory))
        val received = AtomicInteger()
        val opened = AtomicInteger()
        val done = CountDownLatch(1)
        instance.listen { link ->
            val handled = handleQuickEntryLink(link.orEmpty()) { opened.incrementAndGet() }
            File(directory, "received").appendText("$handled:$link\n")
            if (received.incrementAndGet() == 2) done.countDown()
        }
        File(directory, "ready").writeText("ready")
        exitProcess(if (done.await(10, TimeUnit.SECONDS) && opened.get() == 2) 0 else 2)
    }
}
