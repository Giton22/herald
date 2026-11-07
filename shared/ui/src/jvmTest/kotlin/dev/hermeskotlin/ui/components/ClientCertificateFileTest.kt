package dev.hermeskotlin.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClientCertificateFileTest {
    @Test fun regularContentIsReadExactlyAndOversizedInputIsBounded() {
        val file = kotlin.io.path.createTempFile("synthetic-cert", ".p12").toFile()
        try {
            file.writeBytes(byteArrayOf(4, 5, 6))
            assertContentEquals(byteArrayOf(4, 5, 6), readClientCertificate(file))
            file.writeBytes(ByteArray(1_048_600))
            assertEquals(1_048_577, readClientCertificate(file).size)
        } finally { file.delete() }
    }

    @Test fun aSelectedNamedPipeIsRejectedWithoutWaitingForAWriter(): Unit = runBlocking {
        assumeTrue("Named-pipe import rejection requires Linux and mkfifo (coreutils)", System.getProperty("os.name").startsWith("Linux"))
        val directory = kotlin.io.path.createTempDirectory("synthetic-cert-pipe").toFile()
        val file = directory.resolve("identity.p12")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            check(ProcessBuilder("mkfifo", file.absolutePath).start().waitFor() == 0)
            val read = scope.async { runInterruptible { readClientCertificate(file) } }
            try {
                val failure = withTimeout(1000) { runCatching { read.await() }.exceptionOrNull() }
                assertIs<ClientCertificateFileException>(failure)
            } finally {
                // The pre-fix regression may be blocked in open(): release only this fixture's reader.
                if (!read.isCompleted) {
                    val writer = ProcessBuilder("sh", "-c", "printf x > \"\$1\"", "fixture", file.absolutePath).start()
                    if (!writer.waitFor(2, TimeUnit.SECONDS)) { writer.destroyForcibly(); writer.waitFor() }
                }
                withTimeout(2000) { read.join() }
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
