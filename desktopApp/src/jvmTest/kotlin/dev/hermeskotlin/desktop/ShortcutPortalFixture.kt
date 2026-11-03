package dev.hermeskotlin.desktop

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import kotlin.test.assertTrue

internal class ShortcutPortalFixture : AutoCloseable {
    private val config = run {
        assumeTrue("Shortcut portal integration requires Linux and dbus-daemon", System.getProperty("os.name") == "Linux" && java.io.File("/usr/bin/dbus-daemon").canExecute())
        Files.createTempFile("kyllene-shortcut-bus-", ".conf")
    }
    private val daemon: Process
    val address: String
    val bus: DBusConnection
    val service: Service
    init {
        Files.writeString(config, """<busconfig><type>session</type><listen>unix:tmpdir=/tmp</listen><auth>EXTERNAL</auth>
            <policy context="default"><allow own="*"/><allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>""")
        daemon = ProcessBuilder("/usr/bin/dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1").start()
        var connected: DBusConnection? = null
        try {
            address = CompletableFuture.supplyAsync { daemon.inputStream.bufferedReader().readLine() }.get(3, TimeUnit.SECONDS)
            bus = connect().also { connected = it }
            assertTrue(bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).ListActivatableNames().all { it == "org.freedesktop.DBus" })
            bus.requestBusName(NAME)
            service = Service(bus)
            bus.exportObject(service)
        } catch (failure: Throwable) {
            runCatching { connected?.close() }
            daemon.destroyForcibly(); daemon.waitFor(3, TimeUnit.SECONDS); Files.deleteIfExists(config)
            throw failure
        }
    }
    fun connect() = DBusConnectionBuilder.forAddress(address).withShared(false).build()
    override fun close() {
        try { bus.close() } finally {
            daemon.destroy()
            try { if (!daemon.waitFor(3, TimeUnit.SECONDS)) { daemon.destroyForcibly(); daemon.waitFor(3, TimeUnit.SECONDS) } }
            finally { Files.deleteIfExists(config) }
        }
    }

    class Service(val bus: DBusConnection) : ShortcutsPortal, Properties {
        var version = 1L
        var response = 0L
        var empty = false
        var pending = false
        var pendingCreate = false
        var wrongHandle = false
        var trigger = "Ctrl+Shift+Space"
        val binds = AtomicInteger()
        val requestClosed = CompletableFuture<Unit>()
        val sessionClosed = CompletableFuture<Unit>()
        val started = CompletableFuture<Unit>()
        val createStarted = CompletableFuture<Unit>()
        lateinit var session: String
        lateinit var request: String
        override fun getObjectPath() = PATH
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(iface: String, property: String): A = UInt32(version) as A
        override fun <A> Set(iface: String, property: String, value: A) = error("Not supported")
        override fun GetAll(iface: String): Map<String, Variant<*>> = emptyMap()
        override fun CreateSession(options: Map<String, Variant<*>>): DBusPath {
            val sender = AbstractConnection.getCallInfo().source.substring(1).replace('.', '_')
            session = "$PATH/session/$sender/${options.getValue("session_handle_token").value}"
            bus.exportObject(session, object : ShortcutsPortal.Session {
                override fun getObjectPath() = session
                override fun Close() { sessionClosed.complete(Unit) }
            })
            return respond(options, mapOf("session_handle" to Variant(session)))
        }
        override fun BindShortcuts(session: DBusPath, shortcuts: List<ShortcutsPortal.Shortcut>, parent: String, options: Map<String, Variant<*>>): DBusPath {
            check(session.path == this.session && shortcuts.single().id == ID)
            check(shortcuts.single().options["preferred_trigger"]?.value == "CTRL+SHIFT+space")
            binds.incrementAndGet()
            val result = if (empty) emptyList() else listOf(ShortcutsPortal.Shortcut(ID, mapOf("trigger_description" to Variant(trigger))))
            return respond(options, mapOf("shortcuts" to Variant(result, "a(sa{sv})")), binding = true)
        }
        private fun respond(options: Map<String, Variant<*>>, results: Map<String, Variant<*>>, binding: Boolean = false): DBusPath {
            val sender = AbstractConnection.getCallInfo().source.substring(1).replace('.', '_')
            val path = "$PATH/request/$sender/${options.getValue("handle_token").value}"
            request = path
            bus.exportObject(path, object : ShortcutsPortal.Request {
                override fun getObjectPath() = path
                override fun Close() { requestClosed.complete(Unit) }
            })
            if (!(binding && pending) && !(!binding && pendingCreate)) bus.sendMessage(ShortcutsPortal.Request.Response(path, UInt32(if (binding) response else 0L), results))
            if (binding) started.complete(Unit)
            else createStarted.complete(Unit)
            return DBusPath(if (binding && wrongHandle) path + "_wrong" else path)
        }
        fun activate(sender: DBusConnection = bus, handle: String = session, id: String = ID) {
            sender.sendMessage(ShortcutsPortal.Activated(PATH, DBusPath(handle), id, UInt64(1), emptyMap()))
        }
        fun release() { bus.sendMessage(ShortcutsPortal.Deactivated(PATH, DBusPath(session), ID, UInt64(2), emptyMap())) }
    }
    companion object {
        const val NAME = "org.freedesktop.portal.Desktop"
        const val PATH = "/org/freedesktop/portal/desktop"
        const val ID = "quick-entry"
    }
}
