package dev.hermeskotlin.desktop

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Linux wire tests require dbus-daemon; this bus has no service activation directories. */
internal class FlatpakPortalFixture : AutoCloseable {
    private val config = run {
        assumeTrue("Flatpak portal integration requires Linux and dbus-daemon",
            System.getProperty("os.name") == "Linux" && java.io.File("/usr/bin/dbus-daemon").canExecute())
        Files.createTempFile("kyllene-update-bus-", ".conf")
    }
    private val daemon: Process
    val address: String
    val bus: DBusConnection
    val service: Service
    val registry: DBus
    init {
        Files.writeString(config, """<busconfig><type>session</type><listen>unix:tmpdir=/tmp</listen><auth>EXTERNAL</auth>
            <policy context="default"><allow own="*"/><allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>""")
        daemon = ProcessBuilder("/usr/bin/dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1").start()
        var connected: DBusConnection? = null
        try {
            address = CompletableFuture.supplyAsync { daemon.inputStream.bufferedReader().readLine() }.get(3, TimeUnit.SECONDS)
            bus = connect().also { connected = it }
            registry = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            assertTrue(registry.ListActivatableNames().all { it == "org.freedesktop.DBus" })
            service = Service(bus)
            bus.exportObject(service)
            bus.requestBusName(NAME)
        } catch (failure: Throwable) {
            runCatching { connected?.close() }
            daemon.destroyForcibly(); daemon.waitFor(3, TimeUnit.SECONDS); Files.deleteIfExists(config)
            throw failure
        }
    }
    fun connect() = DBusConnectionBuilder.forAddress(address).withShared(false).build()
    fun awaitDisconnected() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (registry.NameHasOwner(service.client) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(!registry.NameHasOwner(service.client))
    }
    override fun close() {
        service.createGate?.complete(Unit)
        service.updateGate?.complete(Unit)
        try { bus.close() } finally {
            daemon.destroy()
            try { if (!daemon.waitFor(3, TimeUnit.SECONDS)) { daemon.destroyForcibly(); daemon.waitFor(3, TimeUnit.SECONDS) } }
            finally { Files.deleteIfExists(config) }
        }
    }

    class Service(val bus: DBusConnection) : FlatpakPortalWire, Properties {
        var version: Any = UInt32(2)
        var client = ""
        var handle = ""
        var wrongHandle = false
        var createGate: CompletableFuture<Unit>? = null
        var updateGate: CompletableFuture<Unit>? = null
        var error: String? = null
        val creates = AtomicInteger()
        val updates = AtomicInteger()
        val closes = AtomicInteger()
        val spawns = AtomicInteger()
        val parents = ConcurrentLinkedQueue<String>()
        override fun getObjectPath() = PATH
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(iface: String, property: String): A {
            assertEquals(NAME, iface); assertEquals("version", property)
            return version as A
        }
        override fun <A> Set(iface: String, property: String, value: A) = error("Not supported")
        override fun GetAll(iface: String): Map<String, Variant<*>> = emptyMap()
        override fun CreateUpdateMonitor(options: Map<String, Variant<*>>): DBusPath {
            creates.incrementAndGet()
            client = AbstractConnection.getCallInfo().source
            assertEquals(setOf("handle_token"), options.keys)
            val token = options.getValue("handle_token").value as String
            assertTrue(token.matches(Regex("[A-Za-z0-9_]+")))
            handle = "$PATH/update_monitor/${client.substring(1).replace('.', '_')}/$token"
            bus.exportObject(handle, object : FlatpakMonitorWire {
                override fun getObjectPath() = handle
                override fun Close() { closes.incrementAndGet() }
                override fun Update(parent: String, options: Map<String, Variant<*>>) {
                    assertTrue(options.isEmpty()); parents += parent; updates.incrementAndGet()
                    progress(mapOf("status" to Variant(UInt32(0)), "progress" to Variant(UInt32(100))))
                    error?.let { progress(mapOf("status" to Variant(UInt32(3)), "error" to Variant(it),
                        "error_message" to Variant("private diagnostic"))) }
                        ?: progress(mapOf("status" to Variant(UInt32(2))))
                    updateGate?.get(3, TimeUnit.SECONDS)
                }
            })
            bus.sendMessage(FlatpakMonitorWire.UpdateAvailable(handle, mapOf("local-commit" to Variant("A"))))
            createGate?.get(3, TimeUnit.SECONDS)
            return DBusPath(if (wrongHandle) handle + "_unrelated" else handle)
        }
        fun progress(info: Map<String, Variant<*>>, sender: DBusConnection = bus, path: String = handle) {
            sender.sendMessage(FlatpakMonitorWire.Progress(path, info))
        }
        override fun Spawn(cwd: ByteArray, argv: List<ByteArray>, fds: Map<UInt32, FileDescriptor>, env: Map<String, String>,
            flags: UInt32, options: Map<String, Variant<*>>): UInt32 {
            spawns.incrementAndGet()
            assertEquals("/\u0000", cwd.toString(Charsets.UTF_8))
            assertEquals(listOf("kyllene\u0000", "--restart\u0000"), argv.map { it.toString(Charsets.UTF_8) })
            assertTrue(fds.isEmpty() && env.isEmpty() && options.isEmpty())
            assertEquals(2L, flags.toLong())
            return UInt32(42)
        }
    }
    companion object {
        const val NAME = "org.freedesktop.portal.Flatpak"
        const val PATH = "/org/freedesktop/portal/Flatpak"
    }
}
