package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.LinuxKeyValueStore
import dev.hermeskotlin.core.storage.PortalSecret
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxClientCertificatesTest {
    // Synthetic EC keys, made with keytool; never used outside these loopback tests.
    private val password get() = "test-only".toCharArray()

    internal fun fixture(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        checkNotNull(LinuxClientCertificatesTest::class.java.getResourceAsStream("/client-cert-fixture.p12.b64")).use {
            val bytes = java.util.Base64.getMimeDecoder().decode(it.readBytes())
            bytes.inputStream().use { input -> load(input, password) }
        }
    }

    internal fun bundle(alias: String = "client"): ByteArray {
        val source = fixture()
        val one = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(alias, source.getKey(alias, password), password, source.getCertificateChain(alias))
        }
        return ByteArrayOutputStream().use { out -> one.store(out, password); out.toByteArray() }
    }

    @Test fun anImportReopensForItsHostnameIncludingOtherPorts() = runTest {
        val store = InMemoryKeyValueStore()
        val imported = runCatching { LinuxClientCertificates(store).import("LOCALHOST", 9443, bundle(), password) }
        assertTrue(imported.isSuccess, "A valid PKCS#12 must import: ${imported.exceptionOrNull()?.javaClass?.simpleName}")
        val alias = imported.getOrThrow()
        val reopened = LinuxClientCertificates(store)
        val identity = assertNotNull(reopened.material(alias, "localhost", 9443))
        assertEquals(fixture().getCertificate("client"), identity.getCertificate("client"))
        assertNotNull(identity.getKey("client", charArrayOf()))
        assertNotNull(reopened.material(alias, "localhost", 9444))
        assertNull(reopened.material(alias, "elsewhere.example", 9443))
        assertNull(reopened.material("unknown", "localhost", 9443))
    }

    @Test fun productionStorageEncryptsTheImportedIdentityAndRefusesAWrongSecret() = runTest {
        assertNull(linuxClientCertificates(InMemoryKeyValueStore()))
        val directory = kotlin.io.path.createTempDirectory("synthetic-client-cert").toFile()
        try {
            val file = directory.resolve("settings.secure")
            fun secure(secret: Byte) = LinuxKeyValueStore(file, "io.example.ClientCertFixture") {
                PortalSecret(ByteArray(32) { secret }, null)
            }
            val alias = assertNotNull(linuxClientCertificates(secure(7))).import("localhost", 443, bundle(), password)
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(alias))
            val reopened = assertNotNull(linuxClientCertificates(secure(7)))
            assertNotNull(reopened.material(alias, "localhost", 443)?.getKey("client", charArrayOf()))
            assertFailsWith<java.io.IOException> { assertNotNull(linuxClientCertificates(secure(8))).material(alias, "localhost", 443) }
            assertContentEquals(ciphertext, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidIdentitiesNeverCommitAnImport() = runTest {
        val source = fixture()
        val certificatesOnly = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setCertificateEntry("client", source.getCertificate("client"))
        }
        val mismatched = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("client", source.getKey("server", password), password, source.getCertificateChain("client"))
        }
        fun bytes(store: KeyStore) = ByteArrayOutputStream().use { store.store(it, password); it.toByteArray() }
        val invalid = listOf(
            "not a keystore".toByteArray(), bundle("expired"), bundle("server"),
            bytes(certificatesOnly), bytes(source), bytes(mismatched), ByteArray(1_048_577),
        )
        for (input in invalid) {
            val store = RecordingStore()
            assertTrue(runCatching { LinuxClientCertificates(store).import("localhost", 443, input, password) }.isFailure)
            assertTrue(store.values.isEmpty(), "Invalid identity must not reach storage")
        }
    }

    @Test fun issuerConstraintsRejectANonCaAndAnExceededCaPathLength() = runTest {
        val source = fixture()
        val adapter = LinuxClientCertificates(InMemoryKeyValueStore())
        assertNotNull(adapter.material(adapter.import("localhost", 443, bundle("issued"), password), "localhost", 443))
        for (chain in listOf(listOf("bad-leaf", "not-ca"), listOf("bad-path-leaf", "bad-intermediate", "ca"))) {
            val malformed = KeyStore.getInstance("PKCS12").apply {
                load(null, password)
                setKeyEntry("client", source.getKey("issued", password), password, chain.map(source::getCertificate).toTypedArray())
            }
            val bytes = ByteArrayOutputStream().use { malformed.store(it, password); it.toByteArray() }
            val store = RecordingStore()
            assertTrue(runCatching { LinuxClientCertificates(store).import("localhost", 443, bytes, password) }.isFailure,
                "Reject ${chain.joinToString()}")
            assertTrue(store.values.isEmpty())
        }
    }

    @Test fun passwordAndOriginFailuresLeaveExistingIdentityAlone() = runTest {
        val store = RecordingStore()
        val adapter = LinuxClientCertificates(store)
        val alias = adapter.import("localhost", 9443, bundle(), password)
        val original = store.values.toMap()
        for ((host, port, pass) in listOf(Triple("localhost", 9443, "wrong"), Triple("", 9443, "test-only"), Triple("localhost", -1, "test-only"))) {
            assertTrue(runCatching { adapter.import(host, port, bundle(), pass.toCharArray()) }.isFailure)
            assertEquals(original, store.values)
        }
        val consumed = password
        adapter.import("localhost", 9443, bundle(), consumed)
        assertTrue(consumed.all { it == '\u0000' }, "The importer consumes and clears its password buffer")
        assertNotNull(adapter.material(alias, "localhost", 9443))
    }

    @Test fun failedStorageCannotReturnAnAliasOrDamageAnOlderImport() = runTest {
        val store = RecordingStore()
        val adapter = LinuxClientCertificates(store)
        val alias = adapter.import("localhost", 443, bundle(), password)
        val original = store.values.toMap()
        store.failWrites = true
        assertFailsWith<java.io.IOException> { adapter.import("localhost", 443, bundle(), password) }
        assertEquals(original, store.values)
        assertNotNull(adapter.material(alias, "localhost", 443))
    }

    @Test fun unreadableOrFutureRecordsArePreservedWithoutFallback() = runTest {
        val store = RecordingStore()
        val adapter = LinuxClientCertificates(store)
        val alias = adapter.import("localhost", 443, bundle(), password)
        val key = store.values.keys.single()
        val original = store.values.getValue(key)
        for (bad in listOf("{}", "private-corrupt-record", original.replace("\"version\":1", "\"version\":2"))) {
            store.values[key] = bad
            val result = runCatching { adapter.material(alias, "localhost", 443) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("private-corrupt-record") != true)
            assertEquals(bad, store.values[key])
        }
        store.values[key] = original
        assertNotNull(adapter.material(alias, "localhost", 443))
    }

    private class RecordingStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) {
            if (failWrites) throw java.io.IOException("Synthetic commit failure")
            values[key] = value
        }
        override suspend fun remove(key: String) { values.remove(key) }
    }
}
