package dev.hermeskotlin.core.storage

import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.CallbackHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** Opaque portal material, not an encryption key. The recipient owns clearing bytes. */
internal class PortalSecret(val bytes: ByteArray, val token: String?) {
    override fun toString() = "PortalSecret"
}

internal class SecretPortalFailure(val reason: Reason) : java.io.IOException("Secret portal: $reason") {
    enum class Reason { Cancelled, Unavailable, Failed, InvalidSecret }
}

/**
 * One independently cancellable request; no stored key, retry or fallback.
 *
 * [timeoutMillis] bounds each step with the bus and the portal (connecting, starting the portal, the call's reply).
 * The portal's answer may wait on the user unlocking their keyring, so it gets the much longer
 * [responseTimeoutMillis]; cancelling the caller still closes the request at once.
 */
internal class LinuxSecretPortal(
    private val address: String? = null,
    private val timeoutMillis: Long = 20_000,
    private val responseTimeoutMillis: Long = 300_000,
) {
    suspend fun retrieve(token: String? = null): PortalSecret {
        var received: PortalSecret? = null
        try {
            return runInterruptible(Dispatchers.IO) {
                retrieveBlocking(token).also { received = it }
            }
        } catch (failure: Throwable) {
            received?.bytes?.fill(0)
            throw failure
        }
    }

    private fun retrieveBlocking(previousToken: String?): PortalSecret {
        if (System.getProperty("os.name") != "Linux") throw SecretPortalFailure(SecretPortalFailure.Reason.Unavailable)
        // Outside a sandbox the portal sees a host app, whose secret every other host app may share.
        if (address == null && !File("/.flatpak-info").isFile) throw SecretPortalFailure(SecretPortalFailure.Reason.Unavailable)
        var received: ByteArray? = null
        try {
            // Inside the try: a session without a bus address fails here, and is then a missing portal, not a crash.
            val builder = if (address == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(address)
            builder.withShared(false).transportConfig().withTimeout(3_000)
            // The pipe outlives the connection: a call cancelled before the sender thread wrote it out must not have
            // its descriptor closed, and the number reused by another file, while that thread may still pass it on.
            SecretPipe().use { pipe ->
                builder.build().use { bus ->
                    if (!bus.isFileDescriptorSupported) throw SecretPortalFailure(SecretPortalFailure.Reason.Unavailable)
                    val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                    val name = "org.freedesktop.portal.Desktop"
                    if (!bus.call<Boolean>(daemon, "NameHasOwner", name)) {
                        bus.call<UInt32>(daemon, "StartServiceByName", name, UInt32(0))
                    }
                    val owner = bus.call<String>(daemon, "GetNameOwner", name)
                    val handleToken = "kyllene" + UUID.randomUUID().toString().replace("-", "")
                    val path = "/org/freedesktop/portal/desktop/request/" + bus.uniqueName.substring(1).replace('.', '_') + "/" + handleToken
                    // Where the request lives: the predicted path, or the one an older portal returned instead.
                    var requestPath = path
                    val response = CompletableFuture<SecretPortalRequest.Response>()
                    val subscription = bus.addSigHandler(SecretPortalRequest.Response::class.java, owner) {
                        if (it.path == path) response.complete(it)
                    }
                    var dispatched = false
                    var terminal = false
                    val cleanup = AutoCloseable {
                        // Cancellation must still send Close on this owner/path, with its own short budget.
                        val interrupted = Thread.interrupted()
                        try {
                            try {
                                if (dispatched && !terminal) {
                                    val request = bus.getRemoteObject(owner, requestPath, SecretPortalRequest::class.java)
                                    bus.call<Void>(request, "Close", timeout = 500)
                                }
                            } finally { subscription.close() }
                        } finally { if (interrupted) Thread.currentThread().interrupt() }
                    }
                    cleanup.use {
                        val options = mutableMapOf<String, Variant<*>>("handle_token" to Variant(handleToken))
                        if (previousToken != null) options["token"] = Variant(previousToken)
                        val portal = bus.getRemoteObject(owner, "/org/freedesktop/portal/desktop", SecretPortalWire::class.java)
                        dispatched = true
                        val handle = bus.call<DBusPath>(portal, "RetrieveSecret", FileDescriptor(pipe.output), options)
                        if (handle.path != path) {
                            requestPath = handle.path
                            throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)
                        }
                        pipe.closeWriter()
                        val result = try {
                            response.get(responseTimeoutMillis, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {
                            throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)
                        }
                        terminal = true
                        when (result.response.toLong()) {
                            0L -> Unit
                            1L -> throw SecretPortalFailure(SecretPortalFailure.Reason.Cancelled)
                            else -> throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)
                        }
                        val token = result.results["token"]?.value
                        if (token != null && token !is String) throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)
                        // A backend that keeps its copy of the pipe open would never send end of file.
                        received = pipe.read(timeoutMillis)
                        return PortalSecret(received!!, token)
                    }
                }
            }
        } catch (failure: Throwable) {
            received?.fill(0)
            when (failure) {
                // An Error is a broken build (a missing module or native library), not a missing portal.
                is InterruptedException, is SecretPortalFailure, is Error -> throw failure
                else -> throw SecretPortalFailure(SecretPortalFailure.Reason.Unavailable)
            }
        }
    }

    private fun <T> DBusConnection.call(target: DBusInterface, method: String, vararg arguments: Any, timeout: Long = timeoutMillis): T {
        val result = CompletableFuture<T>()
        callWithCallback(target, method, object : CallbackHandler<T> {
            override fun handle(value: T) { result.complete(value) }
            override fun handleError(error: DBusExecutionException) { result.completeExceptionally(error) }
        }, *arguments)
        return result.get(timeout, TimeUnit.MILLISECONDS)
    }
}
