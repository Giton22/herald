package dev.hermeskotlin.desktop

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue

class LinuxColorSchemeTest {

    /** A private session bus with a stand-in for the desktop's settings portal on it. */
    private class Bus : AutoCloseable {
        private val config = run {
            assumeTrue("The color scheme tests need Linux and dbus-daemon",
                System.getProperty("os.name") == "Linux" && java.io.File("/usr/bin/dbus-daemon").canExecute())
            Files.createTempFile("kyllene-scheme-bus-", ".conf")
        }
        private val daemon: Process
        val address: String
        val portal: DBusConnection
        @Volatile var scheme: Any = Variant(Variant(UInt32(1)))

        init {
            Files.writeString(config, """<busconfig><type>session</type><listen>unix:tmpdir=/tmp</listen><auth>EXTERNAL</auth>
                <policy context="default"><allow own="*"/><allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>""")
            daemon = ProcessBuilder("/usr/bin/dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1").start()
            try {
                address = CompletableFuture.supplyAsync { daemon.inputStream.bufferedReader().readLine() }.get(3, TimeUnit.SECONDS)
                portal = DBusConnectionBuilder.forAddress(address).withShared(false).build()
            } catch (failure: Exception) {
                stopDaemon()
                throw failure
            }
        }

        private fun stopDaemon() {
            daemon.destroy()
            if (!daemon.waitFor(2, TimeUnit.SECONDS)) daemon.destroyForcibly()
            Files.deleteIfExists(config)
        }

        fun serve() {
            portal.exportObject(object : SettingsPortal {
                override fun getObjectPath() = "/org/freedesktop/portal/desktop"
                override fun Read(namespace: String, key: String): Variant<*> {
                    assertEquals("org.freedesktop.appearance", namespace)
                    assertEquals("color-scheme", key)
                    return scheme as Variant<*>
                }
            })
            portal.requestBusName("org.freedesktop.portal.Desktop")
        }

        /** The portal gives up its name, as it does when it stops; [comeBack] is its restart. */
        fun leave() = portal.releaseBusName("org.freedesktop.portal.Desktop")

        fun comeBack() = portal.requestBusName("org.freedesktop.portal.Desktop")

        fun change(value: Any, namespace: String = "org.freedesktop.appearance", key: String = "color-scheme",
            from: DBusConnection = portal) {
            from.sendMessage(SettingsPortal.SettingChanged("/org/freedesktop/portal/desktop", namespace, key, Variant(value)))
        }

        override fun close() {
            runCatching { portal.close() }
            stopDaemon()
        }
    }

    /** Reports are `Boolean?`, so a "nothing" is wrapped to tell it from a timeout. */
    private class Reports {
        val queue = LinkedBlockingQueue<List<Boolean?>>()
        fun add(value: Boolean?) { queue.add(listOf(value)) }
        fun next(): Boolean? = (queue.poll(3, TimeUnit.SECONDS) ?: error("no report within 3 s")).single()
        fun none() = assertNull(queue.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun theValueIsReadWhenItStartsAndFollowedAsItChanges() {
        Bus().use { bus ->
            bus.serve()
            val reports = Reports()
            LinuxColorScheme(reports::add, bus.address).use { scheme ->
                assertTrue(scheme.awaitFirst(3_000))
                assertEquals(true, reports.next())
                bus.change(UInt32(2))
                assertEquals(false, reports.next())
                bus.change(UInt32(1))
                assertEquals(true, reports.next())
                // No preference is what turning a dark switch off says: the app goes light with it.
                bus.change(UInt32(0))
                assertEquals(false, reports.next())
            }
        }
    }

    @Test fun aPortalThatGoesAwayLeavesTheLastAnswerUntilItIsBack() {
        Bus().use { bus ->
            bus.serve()
            val reports = Reports()
            LinuxColorScheme(reports::add, bus.address).use {
                assertEquals(true, reports.next())
                bus.leave()
                reports.none()
                bus.scheme = Variant(Variant(UInt32(2)))
                bus.comeBack()
                assertEquals(false, reports.next())
            }
        }
    }

    @Test fun lightIsReadAtStartToo() {
        Bus().use { bus ->
            bus.scheme = Variant(Variant(UInt32(2)))
            bus.serve()
            val reports = Reports()
            LinuxColorScheme(reports::add, bus.address).use { assertEquals(false, reports.next()) }
        }
    }

    @Test fun aDesktopWithNoPortalSaysNothingAndTheFirstWaitEnds() {
        Bus().use { bus ->
            val reports = Reports()
            LinuxColorScheme(reports::add, bus.address).use { scheme ->
                assertTrue(scheme.awaitFirst(5_000))
                reports.none()
            }
        }
    }

    @Test fun anotherSettingOrAnotherSenderIsIgnored() {
        Bus().use { bus ->
            bus.serve()
            val reports = Reports()
            LinuxColorScheme(reports::add, bus.address).use {
                assertEquals(true, reports.next())
                bus.change(UInt32(2), key = "contrast")
                bus.change(UInt32(2), namespace = "org.gnome.desktop.interface")
                DBusConnectionBuilder.forAddress(bus.address).withShared(false).build().use { stranger ->
                    bus.change(UInt32(2), from = stranger)
                    // A round trip, so the stranger's signal was delivered before looking.
                    stranger.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", org.freedesktop.dbus.interfaces.DBus::class.java).GetId()
                }
                reports.none()
            }
        }
    }

    @Test fun aPortalThatAppearsLaterIsPickedUp() {
        Bus().use { bus ->
            val reports = Reports()
            LinuxColorScheme(reports::add, bus.address).use { scheme ->
                assertTrue(scheme.awaitFirst(5_000))
                reports.none()
                bus.serve()
                assertEquals(true, reports.next())
            }
        }
    }

    @Test fun whatEachValueMeans() {
        assertEquals(true, LinuxColorScheme.isDark(UInt32(1)))
        assertEquals(false, LinuxColorScheme.isDark(UInt32(2)))
        assertEquals(false, LinuxColorScheme.isDark(UInt32(0)))
        assertEquals(false, LinuxColorScheme.isDark(UInt32(3)))
        // Read wraps its answer twice, the change signal once.
        assertEquals(true, LinuxColorScheme.isDark(Variant(Variant(UInt32(1)))))
        assertEquals(false, LinuxColorScheme.isDark(Variant(UInt32(2))))
        assertNull(LinuxColorScheme.isDark("dark"))
        assertNull(LinuxColorScheme.isDark(null))
    }
}
