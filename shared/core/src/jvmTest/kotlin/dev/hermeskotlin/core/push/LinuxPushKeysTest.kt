package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.storage.AtomicSettingsFile
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.LinuxKeyValueStore
import dev.hermeskotlin.core.storage.PortalSecret
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinuxPushKeysTest {
    @Test
    fun identityIsUniquePerInstallAndStableAcrossReopen() {
        val store = InMemoryKeyValueStore()
        val first = LinuxPushKeys(store).registration("First name")
        assertTrue(first.deviceId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals(first.deviceId, UUID.fromString(first.deviceId).toString())
        assertTrue(first.pushTopic.matches(Regex("hp-[0-9a-f]{32}")))
        assertTrue(first.replyTopic.matches(Regex("hp-[0-9a-f]{32}")))
        assertNotEquals(first.pushTopic, first.replyTopic)
        assertNotEquals(first.encPub, first.sigPub)
        val reopened = LinuxPushKeys(store).registration("Changed name")
        assertEquals(first.copy(name = "Changed name"), reopened)
        assertNotEquals(first.deviceId, LinuxPushKeys(InMemoryKeyValueStore()).deviceId)
    }

    @Test
    fun pinRequiresValidVersionOneKeysAndNeverTrustsAnotherGateway() {
        val keys = LinuxPushKeys(InMemoryKeyValueStore())
        val valid = gateway()
        assertTrue(keys.accepts(valid))
        for (invalid in listOf(valid.copy(version = 2), valid.copy(encPub = "bad"), valid.copy(sigPub = "bad"))) {
            assertFalse(keys.accepts(invalid))
            assertFailsWith<IllegalArgumentException> { keys.pin("https://one.invalid", invalid) }
            assertNull(keys.pinnedGatewayUrl())
        }
        keys.registration("Fixture")
        keys.pin("https://one.invalid", valid)
        assertEquals(valid, keys.pinned("https://one.invalid"))
        assertNull(keys.pinned("https://two.invalid"))
        assertFailsWith<IllegalStateException> { keys.pin("https://two.invalid", gateway()) }
        assertEquals("https://one.invalid", keys.pinnedGatewayUrl())
    }

    @Test
    fun retirementRotatesAllIdentityMaterialAndSurvivesReopenUntilUnregistered() {
        val store = InMemoryKeyValueStore()
        val keys = LinuxPushKeys(store)
        val old = keys.registration("Fixture")
        keys.pin("https://one.invalid", gateway())
        keys.retire("https://other.invalid")
        assertEquals(old, keys.registration("Fixture"))
        keys.retire("https://one.invalid")
        val reopened = LinuxPushKeys(store)
        assertNull(reopened.pinnedGatewayUrl())
        assertEquals(setOf(old.deviceId), reopened.retired("https://one.invalid"))
        assertTrue(reopened.retired("https://other.invalid").isEmpty())
        val fresh = reopened.registration("Fixture")
        assertNotEquals(old.deviceId, fresh.deviceId)
        assertNotEquals(old.encPub, fresh.encPub)
        assertNotEquals(old.sigPub, fresh.sigPub)
        assertNotEquals(old.pushTopic, fresh.pushTopic)
        assertNotEquals(old.replyTopic, fresh.replyTopic)
        reopened.forgetRetired("https://other.invalid", old.deviceId)
        assertEquals(setOf(old.deviceId), reopened.retired("https://one.invalid"))
        reopened.forgetRetired("https://one.invalid", old.deviceId)
        assertTrue(LinuxPushKeys(store).retired("https://one.invalid").isEmpty())
        assertEquals(fresh, LinuxPushKeys(store).registration("Fixture"))
    }

    @Test
    fun failedReadOrCorruptStateDoesNotReplaceAnExistingIdentity() {
        val store = FailingStore()
        val keys = LinuxPushKeys(store)
        val identity = keys.registration("Fixture")
        val saved = store.values.toMap()
        store.readFails = true
        assertFailsWith<IOException> { keys.registration("Fixture") }
        store.readFails = false
        assertEquals(saved, store.values)
        assertEquals(identity, keys.registration("Fixture"))
        val key = store.values.keys.single()
        // Keys that no longer pair up, written outside this adapter after it last checked the identity.
        val device = Json.parseToJsonElement(saved.getValue(key)).jsonObject.getValue("device").jsonObject
        val swapped = saved.getValue(key)
            .replace(device.getValue("encPublic").jsonPrimitive.content, "ENC")
            .replace(device.getValue("sigPublic").jsonPrimitive.content, device.getValue("encPublic").jsonPrimitive.content)
            .replace("ENC", device.getValue("sigPublic").jsonPrimitive.content)
        for (corrupt in listOf(swapped, "{}", """{"version":1,"retired":{}}""")) {
            store.values[key] = corrupt
            assertFailsWith<IllegalStateException> { keys.registration("Fixture") }
            assertEquals(corrupt, store.values[key])
        }
    }

    @Test
    fun aFailedRetirementKeepsOldKeysAndAnUncertainCommittedRetirementDoesNotReviveThem() {
        val store = FailingStore()
        val keys = LinuxPushKeys(store)
        val old = keys.registration("Fixture")
        keys.pin("https://one.invalid", gateway())
        store.writeFails = true
        assertFailsWith<IOException> { keys.retire("https://one.invalid") }
        store.writeFails = false
        assertEquals(old, keys.registration("Fixture"))
        assertEquals("https://one.invalid", keys.pinnedGatewayUrl())
        store.failAfterCommit = true
        assertFailsWith<IOException> { keys.retire("https://one.invalid") }
        store.failAfterCommit = false
        val reopened = LinuxPushKeys(store)
        assertNull(reopened.pinnedGatewayUrl())
        assertEquals(setOf(old.deviceId), reopened.retired("https://one.invalid"))
        val fresh = reopened.registration("Fixture")
        assertNotEquals(old.deviceId, fresh.deviceId)
        assertEquals(fresh, keys.registration("Fixture"))
    }

    @Test fun uncertainPinCannotExposeAnUnboundIdentity() = uncertainIdentityRead(retire = false)

    @Test fun uncertainRetirementCannotExposeARevokedIdentity() = uncertainIdentityRead(retire = true)

    private fun uncertainIdentityRead(retire: Boolean) = runBlocking {
        assumeTrue("Secure identity commit integration requires Linux.", System.getProperty("os.name").startsWith("Linux"))
        val dir = Files.createTempDirectory("push-uncertain-test").toFile()
        try {
            val permissionProbe = dir.resolve("permission-probe").apply { writeText("fixture") }
            permissionProbe.setReadable(false, false)
            val canDenyRead = !Files.isReadable(permissionProbe.toPath())
            permissionProbe.setReadable(true, true)
            assumeTrue("This filesystem/user bypasses read permissions", canDenyRead)
            val file = dir.resolve("settings.secure")
            var failAfterMove = false
            val disk = AtomicSettingsFile(file) { from, to ->
                Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
                if (failAfterMove) {
                    Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("---------"))
                    throw IOException("synthetic inaccessible acknowledgement")
                }
            }
            fun reopen() = LinuxKeyValueStore(file, "io.example.PushUncertain") { PortalSecret(ByteArray(32) { 7 }, null) }
            val store = LinuxKeyValueStore(file, "io.example.PushUncertain", disk = disk) { PortalSecret(ByteArray(32) { 7 }, null) }
            store.put("fixture.preference", "keep")
            val keys = assertNotNull(linuxPushKeys(store))
            val original = keys.registration("Fixture")
            if (retire) keys.pin("https://one.invalid", gateway())
            failAfterMove = true
            try {
                assertFailsWith<IOException> {
                    if (retire) keys.retire("https://one.invalid") else keys.pin("https://one.invalid", gateway())
                }
            } finally { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
            assertEquals("keep", store.get("fixture.preference"))
            assertFailsWith<IOException> { keys.registration("Fixture") }
            assertFailsWith<IOException> { keys.pinnedGatewayUrl() }
            assertFailsWith<IOException> { keys.pinned("https://one.invalid") }
            assertFailsWith<IOException> { keys.retired("https://one.invalid") }
            val reopened = assertNotNull(linuxPushKeys(reopen()))
            if (retire) {
                assertNull(reopened.pinnedGatewayUrl())
                assertEquals(setOf(original.deviceId), reopened.retired("https://one.invalid"))
                assertNotEquals(original.deviceId, reopened.registration("Fixture").deviceId)
            } else {
                assertEquals("https://one.invalid", reopened.pinnedGatewayUrl())
                assertEquals(original.deviceId, reopened.registration("Fixture").deviceId)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun factoryRefusesPlainStoresAndEncryptedIdentityReopensWithoutPlaintextSecrets() {
        assumeTrue("This encrypted Flatpak storage integration requires Linux.", System.getProperty("os.name").startsWith("Linux"))
        assertNull(linuxPushKeys(InMemoryKeyValueStore()))
        val dir = Files.createTempDirectory("push-identity-test").toFile()
        try {
            val file = dir.resolve("settings.secure")
            fun store() = LinuxKeyValueStore(file, "io.github.example.PushFixture") { PortalSecret(ByteArray(32) { 7 }, null) }
            val keys = assertNotNull(linuxPushKeys(store()))
            val identity = keys.registration("Fixture")
            keys.pin("https://one.invalid", gateway())
            val disk = file.readText()
            for (private in listOf(identity.deviceId, identity.pushTopic, identity.replyTopic, identity.encPub, identity.sigPub)) {
                assertFalse(private in disk)
            }
            val reopened = assertNotNull(linuxPushKeys(store()))
            assertEquals(identity, reopened.registration("Fixture"))
            assertEquals(keys.pinned("https://one.invalid"), reopened.pinned("https://one.invalid"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun failedInitialCommitReturnsNoIdentityAndRetryReusesTheCommittedOne() {
        val store = FailingStore().apply { writeFails = true }
        val keys = LinuxPushKeys(store)
        assertFailsWith<IOException> { keys.registration("Fixture") }
        assertTrue(store.values.isEmpty())
        store.writeFails = false
        val committed = keys.registration("Fixture")
        assertEquals(committed, LinuxPushKeys(store).registration("Fixture"))
    }

    @Test
    fun concurrentAdaptersSharingAStorePublishOnlyOneIdentity() {
        val store = InMemoryKeyValueStore()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val calls = List(8) { pool.submit<String> {
                check(start.await(5, TimeUnit.SECONDS))
                LinuxPushKeys(store).deviceId
            } }
            start.countDown()
            val ids = calls.map { it.get(10, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, ids.size)
            assertEquals(ids.single(), LinuxPushKeys(store).deviceId)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun gateway() = PushGateway("gateway-fixture", PushCrypto.encodePublic(PushCrypto.generateKeyPair().public),
        PushCrypto.encodePublic(PushCrypto.generateKeyPair().public), 1)

    private class FailingStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        var readFails = false
        var writeFails = false
        var failAfterCommit = false
        override suspend fun get(key: String): String? {
            if (readFails) throw IOException("Synthetic read failure")
            return values[key]
        }
        override suspend fun put(key: String, value: String) {
            if (writeFails) throw IOException("Synthetic write failure")
            values[key] = value
            if (failAfterCommit) throw IOException("Synthetic commit acknowledgement failure")
        }
        override suspend fun remove(key: String) { error("Identity state must commit atomically") }
    }
}
