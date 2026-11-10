package dev.hermeskotlin.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.Settings")
internal interface SettingsPortal : DBusInterface {
    fun Read(namespace: String, key: String): Variant<*>

    class SettingChanged(path: String, val namespace: String, val key: String, val value: Variant<*>) :
        DBusSignal(path, namespace, key, value)
}

/**
 * The Linux desktop's light or dark preference, from the settings portal's `org.freedesktop.appearance`
 * `color-scheme`, which a Flatpak may read as any app may. Compose reads the preference once on Linux and never
 * looks again, so without this the app follows a change only after a restart. [onChange] gets true for dark,
 * false for light and null when the desktop has none or the portal can't be asked; it is called from this
 * class's own thread, first with what the portal says now and then at each change.
 */
internal class LinuxColorScheme(
    private val onChange: (Boolean?) -> Unit,
    private val address: String? = null,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val first = CountDownLatch(1)
    private val stop = CountDownLatch(1)
    private val worker = thread(name = "kyllene-color-scheme", isDaemon = true) { run() }

    /** Waits up to [millis] for the first answer, or for the failure to get one, so a first frame can have it. */
    fun awaitFirst(millis: Long): Boolean = first.await(millis, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!alive.getAndSet(false)) return
        stop.countDown()
        worker.join(1_000)
    }

    private fun report(dark: Boolean?) {
        if (alive.get()) onChange(dark)
        first.countDown()
    }

    private fun run() {
        try {
            if (System.getProperty("os.name") != "Linux") { report(null); return }
            // Check the authentication class before D-Bus can open a socket.
            Class.forName("com.sun.security.auth.module.UnixSystem")
            val builder = if (address == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(address)
            builder.withShared(false).transportConfig().withTimeout(3_000)
            builder.receivingThreadConfig().withSignalThreadCount(1)
            builder.build().use { bus ->
                val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                // The sender of a change must be the portal itself, whichever process that is now.
                val owner = AtomicReference<String?>(null)
                bus.addSigHandler(SettingsPortal.SettingChanged::class.java) { signal ->
                    if (signal.source == owner.get() && signal.path == PATH && signal.namespace == NAMESPACE && signal.key == KEY) {
                        report(isDark(signal.value))
                    }
                }.use {
                    // A restarted portal (or one that starts late) has a new owner and may hold another answer.
                    bus.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                        if (signal.source == "org.freedesktop.DBus" && signal.path == "/org/freedesktop/DBus" && signal.name == NAME) {
                            val now = signal.newOwner.takeIf { it.isNotEmpty() }
                            owner.set(now)
                            report(now?.let { read(bus, it) })
                        }
                    }.use {
                        val now = try { daemon.GetNameOwner(NAME) } catch (_: Exception) {
                            try { daemon.StartServiceByName(NAME, UInt32(0)); daemon.GetNameOwner(NAME) } catch (_: Exception) { null }
                        }
                        owner.set(now)
                        report(now?.let { read(bus, it) })
                        stop.await()
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            report(null)
        } catch (_: LinkageError) {
            report(null)
        } finally {
            first.countDown()
        }
    }

    private fun read(bus: DBusConnection, owner: String): Boolean? = try {
        isDark(bus.getRemoteObject(owner, PATH, SettingsPortal::class.java).Read(NAMESPACE, KEY))
    } catch (_: Exception) { null }

    companion object {
        private const val NAME = "org.freedesktop.portal.Desktop"
        private const val PATH = "/org/freedesktop/portal/desktop"
        private const val NAMESPACE = "org.freedesktop.appearance"
        private const val KEY = "color-scheme"

        /**
         * What a `color-scheme` value says: 1 is dark, 2 is light, 0 (no preference) and anything else says
         * nothing. `Read` wraps its answer in a second variant, which `ReadOne` and the change signal don't.
         */
        fun isDark(value: Any?): Boolean? {
            var inner = value
            while (inner is Variant<*>) inner = inner.value
            return when ((inner as? UInt32)?.toLong()) {
                1L -> true
                2L -> false
                else -> null
            }
        }
    }
}
