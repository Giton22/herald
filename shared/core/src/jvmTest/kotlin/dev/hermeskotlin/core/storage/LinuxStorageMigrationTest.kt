package dev.hermeskotlin.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxStorageMigrationTest {
    @Test fun failedInitialRenamePreservesLegacyAndRetriesTheSameKey() = fixture { encrypted, legacy ->
        FileKeyValueStore(legacy).put("session", "synthetic-cookie")
        val before = legacy.readBytes()
        var allow = false
        var requests = 0
        val disk = AtomicSettingsFile(encrypted) { from, to ->
            if (!allow) throw IOException("synthetic rename failure")
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val store = LinuxKeyValueStore(encrypted, "io.example.StorageFixture", disk = disk) {
            requests++; PortalSecret(ByteArray(32) { 7 }, "fixture-token")
        }
        assertFailsWith<IOException> { store.get("session") }
        assertContentEquals(before, legacy.readBytes())
        assertFalse(encrypted.exists())
        allow = true
        store.prepare()
        assertEquals("synthetic-cookie", store.get("session"))
        assertFalse(legacy.exists())
        assertEquals(1, requests)
    }

    @Test fun visibleRenameWithoutDirectorySyncCannotRemoveLegacy() = fixture { encrypted, legacy ->
        FileKeyValueStore(legacy).put("session", "synthetic-cookie")
        val before = legacy.readBytes()
        var allowSync = false
        var moves = 0
        var requests = 0
        val normal = AtomicSettingsFile(encrypted)
        val disk = AtomicSettingsFile(encrypted, syncParent = {
            if (!allowSync) throw IOException("synthetic directory sync failure")
            normal.syncDirectory()
        }, replace = { from, to -> Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING); moves++ })
        val store = LinuxKeyValueStore(encrypted, "io.example.StorageFixture", disk = disk) {
            requests++; PortalSecret(ByteArray(32) { 7 }, "rotated-token")
        }
        assertFailsWith<IOException> { store.get("session") }
        assertTrue(encrypted.exists())
        assertContentEquals(before, legacy.readBytes())
        allowSync = true
        store.prepare()
        assertEquals("synthetic-cookie", store.get("session"))
        assertFalse(legacy.exists())
        assertEquals(1, requests)
        assertEquals(1, moves)
    }

    @Test fun interruptedLegacyCleanupResumesFromTheAuthenticatedEncryptedTarget() = fixture { encrypted, legacy ->
        FileKeyValueStore(legacy).put("session", "synthetic-cookie")
        val before = legacy.readBytes()
        val interrupted = LinuxKeyValueStore(encrypted, "io.example.StorageFixture",
            deleteLegacy = { throw IOException("synthetic interrupted cleanup") }) { PortalSecret(ByteArray(32) { 7 }, null) }
        assertFailsWith<IOException> { interrupted.get("session") }
        assertTrue(encrypted.exists())
        assertContentEquals(before, legacy.readBytes())
        val reopened = LinuxKeyValueStore(encrypted, "io.example.StorageFixture") { PortalSecret(ByteArray(32) { 7 }, null) }
        assertEquals("synthetic-cookie", reopened.get("session"))
        assertFalse(legacy.exists())
    }

    @Test fun lostUnlinkAcknowledgementIsReconciledWithoutDeletingAgain() = fixture { encrypted, legacy ->
        FileKeyValueStore(legacy).put("session", "synthetic-cookie")
        var deletions = 0
        val store = LinuxKeyValueStore(encrypted, "io.example.StorageFixture", deleteLegacy = { path ->
            Files.delete(path); deletions++
            throw IOException("synthetic unlink acknowledgement loss")
        }) { PortalSecret(ByteArray(32) { 7 }, null) }
        assertEquals("synthetic-cookie", store.get("session"))
        assertFalse(legacy.exists())
        assertEquals(1, deletions)
    }

    @Test fun changedLegacySourceIsPreservedAfterEncryptedCommit() = fixture { encrypted, legacy ->
        FileKeyValueStore(legacy).put("session", "original-cookie")
        var changed = false
        val normal = AtomicSettingsFile(encrypted)
        val disk = AtomicSettingsFile(encrypted, syncParent = {
            if (!changed) { changed = true; legacy.writeText("{\"session\":\"newer-cookie\"}") }
            normal.syncDirectory()
        })
        val store = LinuxKeyValueStore(encrypted, "io.example.StorageFixture", disk = disk) { PortalSecret(ByteArray(32) { 7 }, null) }
        assertFailsWith<IOException> { store.get("session") }
        assertEquals("{\"session\":\"newer-cookie\"}", legacy.readText())
        assertTrue(encrypted.exists())
    }

    @Test fun unfinishedLegacyPartIsNotDiscardedOrImported() = fixture { encrypted, legacy ->
        val partial = File(legacy.parentFile, legacy.name + ".part")
        partial.writeText("{\"session\":\"possible-only-copy\"}")
        val before = partial.readBytes()
        var requests = 0
        val store = LinuxKeyValueStore(encrypted, "io.example.StorageFixture") { requests++; PortalSecret(ByteArray(32), null) }
        assertFailsWith<IOException> { store.get("session") }
        assertContentEquals(before, partial.readBytes())
        assertFalse(encrypted.exists())
        assertEquals(0, requests)
    }

    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        assumeTrue("Linux secure-storage migration requires Linux", System.getProperty("os.name") == "Linux")
        val directory = Files.createTempDirectory("kyllene-migration-").toFile()
        try { block(File(directory, "settings.secure"), File(directory, "settings.json")) }
        finally { directory.deleteRecursively() }
    }
}
