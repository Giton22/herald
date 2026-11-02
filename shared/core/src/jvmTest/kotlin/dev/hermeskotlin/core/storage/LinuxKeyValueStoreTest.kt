package dev.hermeskotlin.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class LinuxKeyValueStoreTest {
    @Test fun migratesRealLegacyValuesAndPreservesTheOfflineCacheKey() = fixture { file ->
        val legacy = FileKeyValueStore(legacy(file))
        val cacheKey = Base64.getEncoder().encodeToString(ByteArray(32) { 19 })
        legacy.put("http.cookies.v1", "synthetic-cookie")
        legacy.put("offline_cache_key", cacheKey)
        legacy.put("settings.v1", "{\"theme\":\"Dark\"}")
        val store = store(file)
        assertEquals("synthetic-cookie", store.get("http.cookies.v1"))
        assertEquals(cacheKey, store.get("offline_cache_key"))
        assertEquals("{\"theme\":\"Dark\"}", store(file).get("settings.v1"))
        assertFalse(file.readText().contains("synthetic-cookie"))
        assertFalse(file.readText().contains(cacheKey))
    }

    @Test fun persistsOpaqueTextAndUsesFreshCiphertextForRepeatedWrites() = fixture { file ->
        val value = "secret\r\n```\n秘密🔒\n" + "long".repeat(40)
        val store = store(file)
        store.put("session", value)
        val first = file.readBytes()
        store.put("session", value)
        assertNotEquals(first.toList(), file.readBytes().toList())
        assertEquals(value, store(file).get("session"))
        assertFalse(file.readText().contains("secret"))
        store.put("empty", "")
        assertEquals("", store(file).get("empty"))
    }

    @Test fun authenticatedTamperingFailsWithoutReplacingTheFile() = fixture { file ->
        store(file).put("session", "synthetic-cookie")
        val envelope = Json.parseToJsonElement(file.readText()) as JsonObject
        val cipher = Base64.getDecoder().decode(envelope.getValue("ciphertext").jsonPrimitive.content)
        cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        file.writeText(JsonObject(envelope + ("ciphertext" to JsonPrimitive(Base64.getEncoder().encodeToString(cipher)))).toString())
        val tampered = file.readBytes()
        assertFailsWith<IOException> { store(file).get("session") }
        assertContentEquals(tampered, file.readBytes())
    }

    @Test fun aDifferentKeyOrApplicationCannotReplaceEncryptedValues() = fixture { file ->
        store(file).put("session", "synthetic-cookie")
        val before = file.readBytes()
        val wrongKey = LinuxKeyValueStore(file, "io.example.StorageFixture") { PortalSecret(ByteArray(32) { 8 }, null) }
        val wrongApp = LinuxKeyValueStore(file, "io.example.OtherIdentity") { PortalSecret(ByteArray(32) { 7 }, null) }
        assertFailsWith<IOException> { wrongKey.get("session") }
        assertFailsWith<IOException> { wrongApp.get("session") }
        assertContentEquals(before, file.readBytes())
    }

    @Test fun settingsAWrongKeyCantOpenAreSetAsideNotDeleted() = fixture { file ->
        store(file).put("session", "synthetic-cookie")
        val before = file.readBytes()
        val resetKeyring = LinuxKeyValueStore(file, "io.example.StorageFixture") { PortalSecret(ByteArray(32) { 8 }, null) }
        assertEquals(SecureStorageException.Reason.Corrupt,
            assertFailsWith<SecureStorageException> { resetKeyring.prepare() }.reason)
        resetKeyring.setAsideUnreadable()
        resetKeyring.prepare()
        assertEquals(null, resetKeyring.get("session"))
        resetKeyring.put("session", "new-cookie")
        val aside = file.parentFile.listFiles().orEmpty().single { it.name.startsWith("settings.secure.unreadable-") }
        assertContentEquals(before, aside.readBytes())
        assertEquals("synthetic-cookie", LinuxKeyValueStore(aside, "io.example.StorageFixture") {
            PortalSecret(ByteArray(32) { 7 }, null)
        }.get("session"))
    }

    @Test fun aSaveTheOldStoreNeverFinishedDoesntBlockMigration() = fixture { file ->
        legacy(file).writeText("{\"session\":\"saved-cookie\"}")
        val part = File(file.parentFile, "settings.json.part")
        part.writeText("{\"session\":\"unsaved")
        assertEquals("saved-cookie", store(file).get("session"))
        assertFalse(part.exists())
        assertFalse(legacy(file).exists())
        // Left behind after migration too, it's just as stale.
        part.writeText("{}")
        assertEquals("saved-cookie", store(file).get("session"))
        assertFalse(part.exists())
    }

    @Test fun unreadableOldSettingsAreReportedAsTheOldFile() = fixture { file ->
        legacy(file).writeText("{\"session\":")
        assertEquals(SecureStorageException.Reason.Legacy,
            assertFailsWith<SecureStorageException> { store(file).prepare() }.reason)
    }

    @Test fun aWriteKilledBeforeItsRenameLeavesNoTempFileForGood() = fixture { file ->
        val store = store(file)
        store.put("session", "first")
        val leftover = File(file.parentFile, "settings.secure.123456.encrypted.part")
        leftover.writeText("killed mid-write")
        store.put("session", "second")
        assertFalse(leftover.exists())
        assertEquals("second", store(file).get("session"))
    }

    @Test fun unavailableKeyPreservesTheOnlyLegacyCopy() = fixture { file ->
        FileKeyValueStore(legacy(file)).put("session", "synthetic-cookie")
        val before = legacy(file).readBytes()
        val locked = LinuxKeyValueStore(file, "io.example.StorageFixture") {
            throw SecretPortalFailure(SecretPortalFailure.Reason.Unavailable)
        }
        assertEquals(SecureStorageException.Reason.Unavailable,
            assertFailsWith<SecureStorageException> { locked.get("session") }.reason)
        assertContentEquals(before, legacy(file).readBytes())
        assertFalse(file.exists())
    }

    @Test fun malformedLegacyDataNeverBecomesAnEmptyStore() = fixture { file ->
        legacy(file).writeText("{\"session\":")
        val before = legacy(file).readBytes()
        var calls = 0
        val invalid = LinuxKeyValueStore(file, "io.example.StorageFixture") { calls++; PortalSecret(ByteArray(32), null) }
        assertFailsWith<IOException> { invalid.put("new", "value") }
        assertEquals(0, calls)
        assertContentEquals(before, legacy(file).readBytes())
    }

    @Test fun failedRotationKeepsTokenAndCiphertextTogetherForAnExplicitRetry() = fixture { file ->
        val original = LinuxKeyValueStore(file, "io.example.StorageFixture") { PortalSecret(ByteArray(32) { 7 }, "old-token") }
        original.put("session", "synthetic-cookie")
        val before = file.readBytes()
        var allowCommit = false
        val seen = mutableListOf<String?>()
        val atomic = AtomicSettingsFile(file) { from, to ->
            if (!allowCommit) throw IOException("synthetic write failure")
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val rotating = LinuxKeyValueStore(file, "io.example.StorageFixture", disk = atomic) { token ->
            seen += token
            PortalSecret(ByteArray(32) { 7 }, "new-token")
        }
        assertFailsWith<IOException> { rotating.get("session") }
        assertContentEquals(before, file.readBytes())
        allowCommit = true
        assertEquals("synthetic-cookie", rotating.get("session"))
        assertEquals(listOf<String?>("old-token"), seen)
        val reopened = LinuxKeyValueStore(file, "io.example.StorageFixture") { token ->
            assertEquals("new-token", token)
            PortalSecret(ByteArray(32) { 7 }, null)
        }
        assertEquals("synthetic-cookie", reopened.get("session"))
    }

    @Test fun failedMutationDoesNotChangeTheCachedOrPersistedValue() = fixture { file ->
        var fail = false
        val atomic = AtomicSettingsFile(file) { from, to ->
            if (fail) throw IOException("synthetic write failure")
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val store = LinuxKeyValueStore(file, "io.example.StorageFixture", disk = atomic) { PortalSecret(ByteArray(32) { 7 }, null) }
        store.put("session", "old-cookie")
        val before = file.readBytes()
        fail = true
        assertFailsWith<IOException> { store.put("session", "new-cookie") }
        assertEquals("old-cookie", store.get("session"))
        assertEquals("old-cookie", store(file).get("session"))
        assertContentEquals(before, file.readBytes())
    }

    @Test fun aLostCommitAcknowledgementReconcilesTheVerifiedTargetWithoutReplaying() = fixture { file ->
        var failAfterMove = false
        var moves = 0
        val atomic = AtomicSettingsFile(file) { from, to ->
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
            moves++
            if (failAfterMove) throw IOException("synthetic acknowledgement loss")
        }
        val store = LinuxKeyValueStore(file, "io.example.StorageFixture", disk = atomic) { PortalSecret(ByteArray(32) { 7 }, null) }
        store.put("session", "old-cookie")
        failAfterMove = true
        val beforeMoves = moves
        store.put("session", "new-cookie")
        assertEquals(beforeMoves + 1, moves)
        assertEquals("new-cookie", store.get("session"))
        assertEquals("new-cookie", store(file).get("session"))
    }

    @Test fun anUnreadableCommitOutcomeBlocksFurtherWritesUntilReopen() = fixture { file ->
        val permissionProbe = File(file.parentFile, "permission-probe")
        permissionProbe.writeText("probe")
        permissionProbe.setReadable(false, false)
        val canDenyRead = !Files.isReadable(permissionProbe.toPath())
        permissionProbe.setReadable(true, true)
        permissionProbe.delete()
        assumeTrue("This filesystem/user bypasses read permissions", canDenyRead)
        var failAfterMove = false
        val atomic = AtomicSettingsFile(file) { from, to ->
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
            if (failAfterMove) {
                Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("---------"))
                throw IOException("synthetic inaccessible acknowledgement")
            }
        }
        val store = LinuxKeyValueStore(file, "io.example.StorageFixture", disk = atomic) { PortalSecret(ByteArray(32) { 7 }, null) }
        store.put("session", "old-cookie")
        failAfterMove = true
        try {
            assertFailsWith<IOException> { store.put("session", "new-cookie") }
            // Reads keep the last confirmed value: the rest of the app can't act on a storage error.
            assertEquals("old-cookie", store.get("session"))
        } finally { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        assertFailsWith<IOException> { store.put("session", "third-cookie") }
        assertEquals("new-cookie", store(file).get("session"))
    }

    @Test fun replacingProtectedStorageWithLegacyLookingJsonCannotDowngradeIt() = fixture { file ->
        store(file).put("session", "synthetic-cookie")
        file.writeText("{\"session\":\"forged-legacy-cookie\"}")
        val tampered = file.readBytes()
        assertFailsWith<IOException> { store(file).get("session") }
        assertContentEquals(tampered, file.readBytes())
    }

    private fun legacy(file: File) = File(file.parentFile, "settings.json")

    private fun store(file: File): LinuxKeyValueStore = LinuxKeyValueStore(file, "io.example.StorageFixture") {
        PortalSecret(ByteArray(32) { 7 }, null)
    }

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        assumeTrue("Linux secure-storage filesystem tests require Linux", System.getProperty("os.name") == "Linux")
        val directory = Files.createTempDirectory("kyllene-secure-store-").toFile()
        try { block(File(directory, "settings.secure")) } finally { directory.deleteRecursively() }
    }
}
