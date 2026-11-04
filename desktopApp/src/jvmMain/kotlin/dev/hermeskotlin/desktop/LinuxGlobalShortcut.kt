package dev.hermeskotlin.desktop

import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.CallbackHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** One portal session while Quick entry is enabled; consent never blocks the window thread. */
internal class LinuxGlobalShortcut(
    private val onPress: () -> Unit,
    private val onState: (State, String?) -> Unit,
    private val address: String? = null,
    private val flatpakInfo: File = File("/.flatpak-info"),
) : AutoCloseable {
    enum class State { Binding, Bound, Unassigned, Declined, Unavailable }
    private class OwnerChanged : Exception()
    private class Declined : Exception()
    private class Binding(val owner: String, val session: String) {
        val valid = AtomicBoolean(true)
        val pressed = AtomicBoolean(false)
        @Volatile var pressedAt = 0L
        val waiting = AtomicReference<CompletableFuture<*>?>()
        @Volatile var bound = false
        @Volatile var revoked = false
    }
    private val alive = AtomicBoolean(true)
    private val active = AtomicReference<Binding?>()
    private val changes = ArrayBlockingQueue<Unit>(1)
    private val worker = thread(name = "kyllene-shortcut-portal", isDaemon = true) { run() }

    override fun close() {
        if (!alive.getAndSet(false)) return
        active.get()?.valid?.set(false)
        worker.interrupt()
        worker.join(1_000)
    }

    private fun state(value: State, trigger: String? = null) {
        if (alive.get()) onState(value, trigger)
    }

    private fun run() {
        if (System.getProperty("os.name") != "Linux") { state(State.Unavailable); return }
        try {
            // Check the authentication class before D-Bus can open a socket.
            Class.forName("com.sun.security.auth.module.UnixSystem")
            val builder = if (address == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(address)
            builder.withShared(false).transportConfig().withTimeout(3_000)
            builder.receivingThreadConfig().withSignalThreadCount(1)
            builder.build().use { bus ->
                val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                bus.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                    if (signal.source == "org.freedesktop.DBus" && signal.name == NAME && signal.path == "/org/freedesktop/DBus") {
                        active.get()?.takeIf { it.owner == signal.oldOwner && it.owner != signal.newOwner }?.let {
                            it.valid.set(false)
                            it.waiting.get()?.completeExceptionally(OwnerChanged())
                        }
                        changes.offer(Unit)
                    }
                }.use {
                    // A portal that fails to start now may still appear later; the owner wait below picks it up.
                    try {
                        if (!call<Boolean>(bus, daemon, "NameHasOwner", NAME)) call<UInt32>(bus, daemon, "StartServiceByName", NAME, UInt32(0))
                    } catch (failure: InterruptedException) { throw failure } catch (_: Exception) {}
                    while (alive.get()) {
                        // Not runCatching: it would swallow close()'s interrupt and leave this thread parked in take().
                        val owner = try { call<String>(bus, daemon, "GetNameOwner", NAME) }
                            catch (failure: InterruptedException) { throw failure } catch (_: Exception) { null }
                        if (owner == null) { state(State.Unavailable); changes.take(); continue }
                        val sender = bus.uniqueName.substring(1).replace('.', '_')
                        val binding = Binding(owner, "$PATH/session/$sender/${token()}")
                        active.set(binding)
                        try {
                            if (call<String>(bus, daemon, "GetNameOwner", NAME) != owner) { binding.valid.set(false); throw OwnerChanged() }
                            // Registration is per portal process, so a restarted portal needs it again.
                            if (!flatpakInfo.isFile) registerHost(bus, binding)
                            bind(bus, binding)
                            // The desktop (or the user, in its settings) ended the session: like a refusal, a toggle asks again.
                            if (binding.revoked && binding.valid.get()) { state(State.Declined); return }
                        } catch (failure: Throwable) {
                            val cause = if (failure is ExecutionException) failure.cause else failure
                            when {
                                cause is InterruptedException -> throw cause
                                !binding.valid.get() && alive.get() -> Unit // Only an authoritative owner change retries.
                                cause is Declined || binding.revoked -> { state(State.Declined); return }
                                else -> { state(State.Unavailable); return }
                            }
                        } finally {
                            binding.valid.set(false)
                            active.compareAndSet(binding, null)
                            // The predicted path is ours even when CreateSession's reply was interrupted.
                            cleanupCall(bus, owner, binding.session, ShortcutsPortal.Session::class.java)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) { state(State.Unavailable) }
        catch (_: LinkageError) { state(State.Unavailable) }
    }

    private fun bind(bus: DBusConnection, binding: Binding) {
        state(State.Binding)
        val properties = bus.getRemoteObject(binding.owner, PATH, Properties::class.java)
        val version = call<Any>(bus, properties, "Get", "org.freedesktop.portal.GlobalShortcuts", "version", binding = binding)
        check(((version as? Variant<*>)?.value ?: version) is UInt32)
        check((((version as? Variant<*>)?.value ?: version) as UInt32).toLong() >= 1)
        val portal = bus.getRemoteObject(binding.owner, PATH, ShortcutsPortal::class.java)
        val subscriptions = ArrayList<AutoCloseable>()
        try {
            subscriptions += bus.addSigHandler(ShortcutsPortal.Activated::class.java, binding.owner) {
                // A held key opens once, but a release can go missing (KDE sends none when another tool, such as
                // KRunner, fires the shortcut), so an old latch no longer holds. Signals arrive on one thread.
                val now = System.nanoTime()
                val held = binding.pressed.get() && now - binding.pressedAt < HELD_NANOS
                if (it.path == PATH && it.session.path == binding.session && it.id == ID && binding.bound &&
                    binding.valid.get() && alive.get() && !binding.revoked && !held) {
                    binding.pressed.set(true)
                    binding.pressedAt = now
                    onPress()
                }
            }
            subscriptions += bus.addSigHandler(ShortcutsPortal.Deactivated::class.java, binding.owner) {
                if (it.path == PATH && it.session.path == binding.session && it.id == ID) binding.pressed.set(false)
            }
            subscriptions += bus.addSigHandler(ShortcutsPortal.ShortcutsChanged::class.java, binding.owner) { changed ->
                if (changed.path == PATH && changed.session.path == binding.session && binding.bound && binding.valid.get()) {
                    changed.shortcuts.singleOrNull { it.id == ID }?.let { shortcut -> runCatching { trigger(shortcut.options) }.onSuccess(::report) }
                }
            }
            subscriptions += bus.addSigHandler(ShortcutsPortal.Session.Closed::class.java, binding.owner) {
                if (it.path == binding.session) {
                    binding.revoked = true
                    binding.waiting.get()?.completeExceptionally(IllegalStateException("Shortcut session closed"))
                    changes.offer(Unit)
                }
            }
            val sessionToken = binding.session.substringAfterLast('/')
            val created = request(bus, binding) { options ->
                call(bus, portal, "CreateSession", options + ("session_handle_token" to Variant(sessionToken)), binding = binding)
            }
            // Specified as a string, but some portals send it as an object path.
            val handle = created["session_handle"]?.value
            check(((handle as? DBusPath)?.path ?: handle) == binding.session)
            val result = request(bus, binding) { options ->
                val shortcut = ShortcutsPortal.Shortcut(ID, mapOf("description" to Variant("Open Kyllene quick entry"), "preferred_trigger" to Variant("CTRL+SHIFT+space")))
                call(bus, portal, "BindShortcuts", DBusPath(binding.session), listOf(shortcut), "", options, binding = binding)
            }
            val shortcuts = result["shortcuts"]?.value
            val rows = when (shortcuts) { is List<*> -> shortcuts; is Array<*> -> shortcuts.toList(); else -> error("Invalid shortcut response") }
            check(rows.size <= 32)
            val entry = rows.map { row ->
                when (row) {
                    is ShortcutsPortal.Shortcut -> listOf(row.id, row.options)
                    is Array<*> -> row.toList()
                    is List<*> -> row
                    else -> error("Invalid shortcut")
                }
            }.singleOrNull { it.size == 2 && it[0] == ID } ?: throw Declined()
            val description = trigger(entry[1] as? Map<*, *>)
            check(binding.valid.get() && !binding.revoked && alive.get())
            binding.bound = true
            report(description)
            while (alive.get() && binding.valid.get() && !binding.revoked) changes.take()
        } finally {
            binding.bound = false
            subscriptions.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** The desktop's text for the granted keys; null when it doesn't say. */
    private fun trigger(options: Map<*, *>?): String? {
        val description = (options?.get("trigger_description") as? Variant<*>)?.value
        check(description == null || description is String)
        check(description !is String || description.length <= 200 && description.none { it.isISOControl() })
        return description as String?
    }

    /**
     * KDE grants the shortcut with no key ("") when its dialog was closed or the key was cleared, and remembers that,
     * so asking again changes nothing: the user has to pick one in the desktop's settings.
     */
    private fun report(trigger: String?) {
        if (trigger != null && trigger.isBlank()) state(State.Unassigned) else state(State.Bound, trigger)
    }

    /**
     * Names this host install to the portal before anything else on the connection. Since 1.19 the portal refuses a
     * host app without an ID ("An app id is required"), so the .deb never bound; a Flatpak's ID comes from its sandbox.
     */
    private fun registerHost(bus: DBusConnection, binding: Binding) {
        val registry = bus.getRemoteObject(binding.owner, PATH, ShortcutsPortal.HostRegistry::class.java)
        try { call<Void>(bus, registry, "Register", HOST_APP_ID, emptyMap<String, Variant<*>>(), binding = binding) }
        // Older portals have no registry and take host apps as they are; an owner change surfaces again in bind().
        catch (_: ExecutionException) {} catch (_: TimeoutException) {}
    }

    private fun request(bus: DBusConnection, binding: Binding, dispatch: (Map<String, Variant<*>>) -> DBusPath): Map<String, Variant<*>> {
        val path = "$PATH/request/${bus.uniqueName.substring(1).replace('.', '_')}/${token()}"
        val response = CompletableFuture<ShortcutsPortal.Request.Response>()
        val subscription = bus.addSigHandler(ShortcutsPortal.Request.Response::class.java, binding.owner) { if (it.path == path) response.complete(it) }
        var terminal = false
        try {
            val handle = dispatch(mapOf("handle_token" to Variant(path.substringAfterLast('/'))))
            check(handle.path == path)
            binding.waiting.set(response)
            check(!binding.revoked) { "Shortcut session closed" }
            if (!binding.valid.get() || !alive.get()) throw OwnerChanged()
            val result = response.get()
            terminal = true
            when (result.response.toLong()) { 0L -> return result.results; 1L -> throw Declined(); else -> error("Shortcut request failed") }
        } finally {
            binding.waiting.compareAndSet(response, null)
            if (!terminal) cleanupCall(bus, binding.owner, path, ShortcutsPortal.Request::class.java)
            subscription.close()
        }
    }

    private fun <T> call(bus: DBusConnection, target: DBusInterface, method: String, vararg arguments: Any, binding: Binding? = null, timeout: Long = 3_000): T {
        val result = CompletableFuture<T>()
        binding?.waiting?.set(result)
        try {
            check(binding?.revoked != true) { "Shortcut session closed" }
            if (binding != null && (!binding.valid.get() || !alive.get())) throw OwnerChanged()
            bus.callWithCallback(target, method, object : CallbackHandler<T> {
                override fun handle(value: T) { result.complete(value) }
                override fun handleError(error: DBusExecutionException) { result.completeExceptionally(error) }
            }, *arguments)
            return result.get(timeout, TimeUnit.MILLISECONDS)
        } finally { binding?.waiting?.compareAndSet(result, null) }
    }

    private fun <T : DBusInterface> cleanupCall(bus: DBusConnection, owner: String, path: String, type: Class<T>) {
        val interrupted = Thread.interrupted()
        try { runCatching { call<Void>(bus, bus.getRemoteObject(owner, path, type), "Close", timeout = 300) } }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    companion object {
        private const val NAME = "org.freedesktop.portal.Desktop"
        private const val PATH = "/org/freedesktop/portal/desktop"
        private const val ID = "quick-entry"
        // The portal takes only an ID with an installed desktop entry: the .deb's (jpackage's package-launcher name).
        private const val HOST_APP_ID = "kyllene-Kyllene"
        private const val HELD_NANOS = 1_000_000_000L
        private fun token() = "kyllene" + UUID.randomUUID().toString().replace("-", "")
    }
}
