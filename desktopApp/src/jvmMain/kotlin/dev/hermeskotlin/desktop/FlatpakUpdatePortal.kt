package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.update.ManagedUpdateFailure
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.CallbackHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

internal object FlatpakUpdatePortal {
    private const val NAME = "org.freedesktop.portal.Flatpak"
    private const val PATH = "/org/freedesktop/portal/Flatpak"

    suspend fun open(address: String? = null, timeoutMillis: Long = 3_000,
        onEvent: (FlatpakUpdateEvent) -> Unit): FlatpakUpdateMonitor? {
        if (System.getProperty("os.name") != "Linux") return null
        var opened: Monitor? = null
        try {
            return withContext(Dispatchers.IO) {
                Class.forName("com.sun.security.auth.module.UnixSystem")
                val builder = if (address == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(address)
                builder.withShared(false).transportConfig().withTimeout(timeoutMillis.toInt())
                builder.receivingThreadConfig().withSignalThreadCount(1)
                val bus = builder.build()
                try {
                    val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                    val owner = try { call<String>(bus, daemon, timeoutMillis, "GetNameOwner", NAME) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        call<UInt32>(bus, daemon, timeoutMillis, "StartServiceByName", NAME, UInt32(0))
                        call<String>(bus, daemon, timeoutMillis, "GetNameOwner", NAME)
                    }
                    val properties = bus.getRemoteObject(owner, PATH, Properties::class.java)
                    val version = call<Any>(bus, properties, timeoutMillis, "Get", NAME, "version")
                    check((((version as? Variant<*>)?.value ?: version) as? UInt32)?.toLong()?.let { it >= 2 } == true)
                    val token = "kyllene" + UUID.randomUUID().toString().replace("-", "")
                    val path = "$PATH/update_monitor/${bus.uniqueName.substring(1).replace('.', '_')}/$token"
                    val monitor = Monitor(bus, owner, path, timeoutMillis, onEvent).also { opened = it }
                    monitor.subscribe()
                    check(call<String>(bus, daemon, timeoutMillis, "GetNameOwner", NAME) == owner && !monitor.revoked.get())
                    val portal = bus.getRemoteObject(owner, PATH, FlatpakPortalWire::class.java)
                    val handle = call<DBusPath>(bus, portal, timeoutMillis, "CreateUpdateMonitor", mapOf("handle_token" to Variant(token)))
                    check(handle.path == path)
                    check(call<String>(bus, daemon, timeoutMillis, "GetNameOwner", NAME) == owner && !monitor.revoked.get())
                    monitor.verified = true
                    monitor
                } catch (failure: Throwable) {
                    opened?.close() ?: runCatching { bus.close() }
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            opened?.close()
            // A method deadline means unsupported/unavailable here; parent cancellation still propagates.
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) return null
            throw cancelled
        } catch (_: Exception) {
            opened?.close()
            return null
        } catch (_: LinkageError) {
            opened?.close()
            return null
        }
    }

    private class Monitor(val bus: DBusConnection, val owner: String, val path: String,
        val timeout: Long, val onEvent: (FlatpakUpdateEvent) -> Unit) : FlatpakUpdateMonitor {
        val revoked = AtomicBoolean()
        private val alive = AtomicBoolean(true)
        private val updating = AtomicBoolean()
        @Volatile var verified = false
        private val subscriptions = ArrayList<AutoCloseable>()

        fun subscribe() {
            subscriptions += bus.addSigHandler(DBus.NameOwnerChanged::class.java) {
                if (alive.get() && it.source == "org.freedesktop.DBus" && it.path == "/org/freedesktop/DBus" &&
                    it.name == NAME && it.oldOwner == owner && it.newOwner != owner && revoked.compareAndSet(false, true)) {
                    onEvent(FlatpakUpdateEvent.Failure(if (updating.get()) ManagedUpdateFailure.Uncertain else ManagedUpdateFailure.Unavailable))
                }
            }
            subscriptions += bus.addSigHandler(FlatpakMonitorWire.UpdateAvailable::class.java, owner) {
                if (accepts(it.source, it.path)) {
                    onEvent(FlatpakUpdateEvent.Info(FlatpakUpdateInfo(
                        it.info["running-commit"]?.value as? String, it.info["local-commit"]?.value as? String,
                        it.info["remote-commit"]?.value as? String)))
                }
            }
            subscriptions += bus.addSigHandler(FlatpakMonitorWire.Progress::class.java, owner) {
                if (accepts(it.source, it.path)) {
                    val status = uint(it.info, "status")
                    if (status != null && status in 1..3) updating.set(false)
                    if (status == 3) onEvent(FlatpakUpdateEvent.Failure(failure(it.info["error"]?.value as? String)))
                    else onEvent(FlatpakUpdateEvent.Progress(status, uint(it.info, "progress")))
                }
            }
        }

        private fun accepts(sender: String, handle: String) = alive.get() && !revoked.get() && sender == owner && handle == path

        override suspend fun update(parent: String) = withContext(Dispatchers.IO) {
            try {
                check(alive.get() && !revoked.get() && verified)
                updating.set(true)
                call<Any?>(bus, bus.getRemoteObject(owner, path, FlatpakMonitorWire::class.java), timeout,
                    "Update", parent, emptyMap<String, Variant<*>>())
                Unit
            } catch (error: Exception) {
                if (error is DBusExecutionException && error.type in listOf(
                        "org.freedesktop.DBus.Error.AccessDenied", "org.freedesktop.DBus.Error.NotSupported")) {
                    onEvent(FlatpakUpdateEvent.Failure(failure(error.type)))
                    close()
                } else {
                    close()
                    throw error
                }
            }
        }

        override suspend fun restart(): Long = withContext(Dispatchers.IO) {
            try {
                check(alive.get() && !revoked.get() && verified)
                call<UInt32>(bus, bus.getRemoteObject(owner, PATH, FlatpakPortalWire::class.java), timeout, "Spawn",
                    "/\u0000".toByteArray(), listOf("kyllene\u0000".toByteArray(), "--restart\u0000".toByteArray()),
                    emptyMap<UInt32, FileDescriptor>(), emptyMap<String, String>(), UInt32(2), emptyMap<String, Variant<*>>()).toLong()
            } catch (error: Exception) {
                close()
                throw error
            }
        }

        override fun close() {
            if (!alive.compareAndSet(true, false)) return
            subscriptions.asReversed().forEach { runCatching { it.close() } }
            try {
                // Only the returned, verified handle is ours. Disconnect cleans up an uncertain Create instead.
                if (verified && !revoked.get()) runCatching {
                    val result = CompletableFuture<Any?>()
                    bus.callWithCallback(bus.getRemoteObject(owner, path, FlatpakMonitorWire::class.java), "Close",
                        object : CallbackHandler<Any?> {
                            override fun handle(value: Any?) { result.complete(value) }
                            override fun handleError(error: DBusExecutionException) { result.completeExceptionally(error) }
                        })
                    result.get(300, TimeUnit.MILLISECONDS)
                }
            } finally { runCatching { bus.close() } }
        }
    }

    private suspend fun <T> call(bus: DBusConnection, target: DBusInterface, timeout: Long, method: String, vararg args: Any): T =
        withTimeout(timeout) {
            suspendCancellableCoroutine { waiting ->
                bus.callWithCallback(target, method, object : CallbackHandler<T> {
                    override fun handle(value: T) { waiting.resume(value) }
                    override fun handleError(error: DBusExecutionException) { waiting.resumeWithException(error) }
                }, *args)
            }
        }

    private fun uint(info: Map<String, Variant<*>>, key: String): Int? =
        (info[key]?.value as? UInt32)?.toLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun failure(name: String?): ManagedUpdateFailure = when (name) {
        "org.freedesktop.DBus.Error.AccessDenied" -> ManagedUpdateFailure.Denied
        "org.freedesktop.DBus.Error.NotSupported" -> ManagedUpdateFailure.SystemUpdateRequired
        else -> ManagedUpdateFailure.Failed
    }
}
