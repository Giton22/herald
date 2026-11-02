package dev.hermeskotlin.core.storage

import dev.hermeskotlin.core.cache.KeystoreSealer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LinuxStorageFailureTest {
    @Test fun concurrentCallsRetrieveOnceClearRawMaterialAndRemoveOnlyTheSelectedKey() = fixture { file ->
        var requests = 0
        val raw = ByteArray(32) { 7 }
        val store = LinuxKeyValueStore(file, ID) { requests++; delay(10); PortalSecret(raw, null) }
        coroutineScope { (1..12).map { index -> async { store.put("session-$index", "value-$index") } }.awaitAll() }
        assertEquals(1, requests)
        assertContentEquals(ByteArray(32), raw)
        store.remove("session-2")
        val reopened = store(file)
        assertEquals(null, reopened.get("session-2"))
        for (index in (1..12).filter { it != 2 }) assertEquals("value-$index", reopened.get("session-$index"))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun existingOfflineCacheRowsOpenAfterMigrationAndReopen() = fixture { file ->
        val original = FileKeyValueStore(legacy(file))
        val row = KeystoreSealer(original).seal("synthetic cached row")
        assertEquals("synthetic cached row", KeystoreSealer(store(file)).open(row))
        assertEquals("synthetic cached row", KeystoreSealer(store(file)).open(row))
        assertFalse(legacy(file).exists())
    }

    @Test fun cancellationAndTimeoutLeaveLegacyUnchangedAndRetryHasNoCachedReplacementKey() = fixture { file ->
        FileKeyValueStore(legacy(file)).put("session", "synthetic-cookie")
        val before = legacy(file).readBytes()
        var requests = 0
        val store = LinuxKeyValueStore(file, ID) {
            when (++requests) {
                1 -> throw CancellationException("synthetic cancellation")
                2 -> withTimeout(1) { delay(1000); error("unreachable") }
                else -> PortalSecret(ByteArray(32) { 7 }, null)
            }
        }
        assertFailsWith<CancellationException> { store.prepare() }
        assertEquals(SecureStorageException.Reason.Unavailable, assertFailsWith<SecureStorageException> { store.prepare() }.reason)
        assertFalse(file.exists())
        assertContentEquals(before, legacy(file).readBytes())
        assertEquals("synthetic-cookie", store.get("session"))
        assertEquals(3, requests)
    }

    @Test fun enclosingTimeoutEscapesInsteadOfBecomingARetryablePortalFailure() = fixture { file ->
        val store = LinuxKeyValueStore(file, ID) { delay(1000); error("unreachable") }
        assertFailsWith<TimeoutCancellationException> { withTimeout(10) { store.prepare() } }
        assertFalse(file.exists())
    }

    @Test fun unsupportedVersionsAndAlteredTokensCannotReplaceEncryptedDataOrDeleteLegacy() = fixture { file ->
        store(file).put("session", "synthetic-cookie")
        val original = Json.parseToJsonElement(file.readText()) as JsonObject
        legacy(file).writeText("{\"session\":\"unrelated-copy\"}")
        val old = legacy(file).readBytes()
        for (change in listOf("version" to JsonPrimitive(999), "token" to JsonPrimitive("altered-token"))) {
            file.writeText(JsonObject(original + change).toString())
            val changed = file.readBytes()
            assertFailsWith<SecureStorageException> { store(file).prepare() }
            assertContentEquals(changed, file.readBytes())
            assertContentEquals(old, legacy(file).readBytes())
        }
    }

    @Test fun nonStringLegacyValuesAreRejectedBeforeRequestingAnyKey() = fixture { file ->
        for (value in listOf("null", "17", "{}", "[]")) {
            legacy(file).writeText("{\"session\":$value}")
            var requests = 0
            val store = LinuxKeyValueStore(file, ID) { requests++; PortalSecret(ByteArray(32), null) }
            assertFailsWith<SecureStorageException> { store.prepare() }
            assertEquals(0, requests)
            assertFalse(file.exists())
        }
    }

    @Test fun symlinksAndDirectoriesCannotBecomeStorageInputs() = fixture { file ->
        val target = File(file.parentFile, "target").apply { writeText("{\"session\":\"preserve\"}") }
        for (path in listOf(legacy(file), file)) {
            Files.createSymbolicLink(path.toPath(), target.toPath())
            assertFailsWith<SecureStorageException> { store(file).prepare() }
            Files.delete(path.toPath())
            path.mkdir()
            assertFailsWith<SecureStorageException> { store(file).prepare() }
            Files.delete(path.toPath())
        }
        assertEquals("{\"session\":\"preserve\"}", target.readText())
    }

    @Test fun twoStoreInstancesCannotOverwriteEachOthersCheckedVersion() = fixture { file ->
        store(file).put("session", "original")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disk = AtomicSettingsFile(file) { from, to ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val first = LinuxKeyValueStore(file, ID, disk = disk) { PortalSecret(ByteArray(32) { 7 }, null) }
        val second = store(file)
        first.prepare(); second.prepare()
        coroutineScope {
            val writer = async(Dispatchers.IO) { first.put("first", "retained") }
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                assertEquals(SecureStorageException.Reason.Changed,
                    assertFailsWith<SecureStorageException> { second.put("second", "must-not-overwrite") }.reason)
            } finally { release.countDown() }
            writer.await()
        }
        assertEquals("retained", store(file).get("first"))
        assertEquals(null, store(file).get("second"))
    }

    @Test fun cancellationAfterRenameKeepsTheCommittedCacheAndDigestCoherent() = fixture { file ->
        store(file).put("session", "old")
        var cancelWriter: () -> Unit = { }
        val disk = AtomicSettingsFile(file) { from, to ->
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
            cancelWriter()
        }
        val store = LinuxKeyValueStore(file, ID, disk = disk) { PortalSecret(ByteArray(32) { 7 }, null) }
        store.prepare()
        coroutineScope {
            val writer = async(start = CoroutineStart.LAZY) { store.put("session", "committed") }
            cancelWriter = { writer.cancel() }
            writer.start()
            assertFailsWith<CancellationException> { writer.await() }
        }
        cancelWriter = { }
        assertEquals("committed", store.get("session"))
        store.put("next", "saved")
        assertEquals("committed", store(file).get("session"))
        assertEquals("saved", store(file).get("next"))
    }

    @Test fun interruptedPostUnlinkSyncCanRestartWithoutImportingOrOverwriting() = fixture { file ->
        FileKeyValueStore(legacy(file)).put("session", "synthetic-cookie")
        var syncs = 0
        val normal = AtomicSettingsFile(file)
        val disk = AtomicSettingsFile(file, syncParent = {
            if (++syncs == 3) throw IOException("synthetic sync failure after unlink")
            normal.syncDirectory()
        })
        val interrupted = LinuxKeyValueStore(file, ID, disk = disk) { PortalSecret(ByteArray(32) { 7 }, null) }
        assertFailsWith<SecureStorageException> { interrupted.prepare() }
        assertFalse(legacy(file).exists())
        val committed = file.readBytes()
        assertEquals("synthetic-cookie", store(file).get("session"))
        assertContentEquals(committed, file.readBytes())
    }

    private fun legacy(file: File) = File(file.parentFile, "settings.json")
    private fun store(file: File) = LinuxKeyValueStore(file, ID) { PortalSecret(ByteArray(32) { 7 }, null) }
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        assumeTrue("Linux secure-storage filesystem tests require Linux", System.getProperty("os.name") == "Linux")
        val directory = Files.createTempDirectory("kyllene-storage-failure-").toFile()
        try { block(File(directory, "settings.secure")) } finally { directory.deleteRecursively() }
    }
    private companion object { const val ID = "io.example.StorageFixture" }
}
