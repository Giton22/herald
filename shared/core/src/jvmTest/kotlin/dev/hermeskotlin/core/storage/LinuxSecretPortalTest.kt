package dev.hermeskotlin.core.storage

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxSecretPortalTest {
    @Test fun retrievesBytesAndOpaqueTokenFromAnEarlyResponse() = fixture { service, portal ->
        service.results = mapOf("token" to Variant("opaque-fixture-token"))
        val result = portal.retrieve("previous-fixture-token")
        assertContentEquals(service.bytes, result.bytes)
        assertEquals("previous-fixture-token", service.previousToken)
        assertEquals("opaque-fixture-token", result.token)
        assertFalse(result.toString().contains("opaque-fixture-token"))
        result.bytes.fill(0)
    }

    @Test fun cancellationClosesThePendingRequest() = fixture { service, portal ->
        service.pending = true
        val request = async(Dispatchers.IO) { portal.retrieve() }
        service.started.get(3, TimeUnit.SECONDS)
        request.cancelAndJoin()
        service.closed.get(3, TimeUnit.SECONDS)
        assertTrue(request.isCancelled)
    }

    @Test fun denialNeverReturnsASecret() = fixture { service, portal ->
        service.response = 1
        assertEquals(SecretPortalFailure.Reason.Cancelled, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun failedResponseNeverReturnsASecret() = fixture { service, portal ->
        service.response = 2
        assertEquals(SecretPortalFailure.Reason.Failed, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun emptySecretIsRejected() = fixture { service, portal ->
        service.bytes = byteArrayOf()
        assertEquals(SecretPortalFailure.Reason.InvalidSecret, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun oversizedSecretIsRejected() = fixture { service, portal ->
        service.bytes = ByteArray(4097)
        assertEquals(SecretPortalFailure.Reason.InvalidSecret, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun opaqueSecretNeedNotBeAnEncryptionKeyLength() = fixture { service, portal ->
        service.bytes = byteArrayOf(42)
        assertContentEquals(service.bytes, portal.retrieve().bytes)
    }

    @Test fun invalidResponseTokenIsRejected() = fixture { service, portal ->
        service.results = mapOf("token" to Variant(UInt32(7)))
        assertEquals(SecretPortalFailure.Reason.Failed, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun differentReturnedHandleIsRejectedAndThatRequestClosed() = fixture { service, portal ->
        service.wrongHandle = true
        service.pending = true
        assertEquals(SecretPortalFailure.Reason.Failed, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
        // The fixture serves the request only at the path it returned, as an older portal would.
        service.closed.get(3, TimeUnit.SECONDS)
    }

    @Test fun timeoutClosesThePendingRequest() = fixture { service, portal ->
        service.pending = true
        assertEquals(SecretPortalFailure.Reason.Failed, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
        service.closed.get(3, TimeUnit.SECONDS)
    }

    @Test fun aSlowUnlockPromptOutlastsTheStepTimeout() = fixture(
        portal = { LinuxSecretPortal(it, timeoutMillis = 500, responseTimeoutMillis = 10_000) },
    ) { service, portal ->
        service.respondAfterMillis = 1_500
        val result = portal.retrieve()
        assertContentEquals(service.bytes, result.bytes)
        assertFalse(service.closed.isDone)
        result.bytes.fill(0)
    }

    @Test fun cleanupFailureDoesNotReplaceTheOriginalRefusal() = fixture { service, portal ->
        service.wrongHandle = true
        service.failClose = true
        assertEquals(SecretPortalFailure.Reason.Failed, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun missingPortalServiceIsExplicitlyUnavailable() = fixture(registerName = false) { _, portal ->
        assertEquals(SecretPortalFailure.Reason.Unavailable, assertFailsWith<SecretPortalFailure> { portal.retrieve() }.reason)
    }

    @Test fun anotherSenderCannotDenyThisRequest() = fixture { service, portal ->
        service.spoofResponse = true
        assertContentEquals(service.bytes, portal.retrieve().bytes)
    }

    private fun fixture(
        registerName: Boolean = true,
        portal: (String) -> LinuxSecretPortal = { LinuxSecretPortal(it, 2_000, 2_000) },
        test: suspend kotlinx.coroutines.CoroutineScope.(Fixture, LinuxSecretPortal) -> Unit,
    ) {
        assumeTrue("Secret-portal FD integration requires Linux", System.getProperty("os.name") == "Linux")
        assumeTrue("Secret-portal integration requires dbus-daemon", java.io.File("/usr/bin/dbus-daemon").canExecute())
        val config = Files.createTempFile("secret-portal-bus-", ".conf")
        Files.writeString(config, """<busconfig><type>session</type><listen>unix:tmpdir=/tmp</listen>
            <auth>EXTERNAL</auth><policy context="default"><allow own="*"/>
            <allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>""")
        val daemon = try {
            ProcessBuilder("/usr/bin/dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1").start()
        } catch (failure: Throwable) { Files.deleteIfExists(config); throw failure }
        try {
            val address = daemon.inputStream.bufferedReader().readLine()
            DBusConnectionBuilder.forAddress(address).withShared(false).build().use { bus ->
                val names = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", org.freedesktop.dbus.interfaces.DBus::class.java).ListActivatableNames()
                assertTrue(names.all { it == "org.freedesktop.DBus" }, "Fixture must not activate desktop services")
                if (registerName) bus.requestBusName("org.freedesktop.portal.Desktop")
                val service = Fixture(bus, address)
                bus.exportObject(service)
                try { runBlocking { test(service, portal(address)) } }
                finally { service.failClose = false; service.Close() }
            }
        } finally {
            daemon.destroy()
            try {
                if (!daemon.waitFor(3, TimeUnit.SECONDS)) { daemon.destroyForcibly(); daemon.waitFor() }
            } finally { Files.deleteIfExists(config) }
        }
    }

    @DBusInterfaceName("org.freedesktop.portal.Secret")
    interface Secret : DBusInterface {
        fun RetrieveSecret(fd: FileDescriptor, options: Map<String, Variant<*>>): DBusPath
    }

    interface LibC : Library {
        fun write(fd: Int, bytes: ByteArray, length: Long): Long
        fun close(fd: Int): Int
    }

    internal class Fixture(private val bus: DBusConnection, private val address: String) : Secret, SecretPortalRequest {
        var bytes = ByteArray(32) { 42 }
        var results: Map<String, Variant<*>> = emptyMap()
        var previousToken: String? = null
        var pending = false
        var response = 0
        var wrongHandle = false
        var failClose = false
        var spoofResponse = false
        /** Answers this long after the call, as a portal waiting on the user's keyring password. */
        var respondAfterMillis = 0L
        val started = CompletableFuture<Unit>()
        val closed = CompletableFuture<Unit>()
        private val libc = Native.load("c", LibC::class.java)
        private var held = -1
        override fun getObjectPath() = "/org/freedesktop/portal/desktop"
        override fun RetrieveSecret(fd: FileDescriptor, options: Map<String, Variant<*>>): DBusPath {
            val sender = AbstractConnection.getCallInfo().source.substring(1).replace('.', '_')
            val path = "$objectPath/request/$sender/${options.getValue("handle_token").value}"
            previousToken = options["token"]?.value as String?
            held = fd.intFileDescriptor
            val returned = if (wrongHandle) path + "_other" else path
            bus.exportObject(returned, this)
            if (respondAfterMillis > 0) {
                val writer = held
                held = -1
                Thread {
                    Thread.sleep(respondAfterMillis)
                    check(libc.write(writer, bytes, bytes.size.toLong()) == bytes.size.toLong())
                    libc.close(writer)
                    bus.sendMessage(SecretPortalRequest.Response(path, UInt32(0), results))
                }.start()
            } else if (!pending) {
                check(libc.write(held, bytes, bytes.size.toLong()) == bytes.size.toLong())
                libc.close(held)
                held = -1
                bus.sendMessage(SecretPortalRequest.Response(path + "_other", UInt32(1), emptyMap()))
                if (spoofResponse) {
                    DBusConnectionBuilder.forAddress(address).withShared(false).build().use { other ->
                        other.sendMessage(SecretPortalRequest.Response(path, UInt32(1), emptyMap()))
                        other.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", org.freedesktop.dbus.interfaces.DBus::class.java).GetId()
                    }
                }
                val signal = SecretPortalRequest.Response(path, UInt32(response.toLong()), results)
                bus.sendMessage(signal)
            }
            started.complete(Unit)
            return DBusPath(returned)
        }
        override fun Close() {
            if (held >= 0) { libc.close(held); held = -1 }
            closed.complete(Unit)
            if (failClose) error("Synthetic Close failure")
        }
    }
}
