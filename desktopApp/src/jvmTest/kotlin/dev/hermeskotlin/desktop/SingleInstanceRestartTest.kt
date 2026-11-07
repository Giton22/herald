package dev.hermeskotlin.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceRestartTest {
    @Test
    fun restartWaitsForTeardownThenOwnsTheLock() = directory { dir ->
        val first = assertNotNull(SingleInstance.claim(dir))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val next = executor.submit<SingleInstance?> { started.countDown(); SingleInstance.claim(dir, waitForExitMillis = 1_500) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { next.get(50, TimeUnit.MILLISECONDS) }
            first.close()
            assertNotNull(next.get(2, TimeUnit.SECONDS)).use {
                assertNull(SingleInstance.claim(dir, waitForExitMillis = 30))
            }
        } finally { first.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test
    fun restartTimeoutNeverFallsThroughToAnUnlockedDuplicate() = directory { dir ->
        assertNotNull(SingleInstance.claim(dir)).use {
            File(dir, "instance.port").delete()
            assertNull(SingleInstance.claim(dir, waitForExitMillis = 40))
        }
    }

    @Test
    fun ordinaryLaunchStillShowsButRestartWaitNeverShowsTheOldApp() = directory { dir ->
        assertNotNull(SingleInstance.claim(dir)).use { first ->
            val shown = CountDownLatch(1)
            first.listen { shown.countDown() }
            assertNull(SingleInstance.claim(dir, waitForExitMillis = 40))
            assertFalse(shown.await(100, TimeUnit.MILLISECONDS))
            assertNull(SingleInstance.claim(dir))
            assertTrue(shown.await(2, TimeUnit.SECONDS))
        }
    }

    private fun directory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kyllene-restart-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
