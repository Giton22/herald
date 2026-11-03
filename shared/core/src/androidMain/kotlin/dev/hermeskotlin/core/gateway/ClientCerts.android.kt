package dev.hermeskotlin.core.gateway

import android.content.Context
import android.security.KeyChain
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The hostname OkHttp is handshaking with, captured from the socket factory on the connecting thread.
 * OkHttp connects by IP, so the socket's own address is usually numeric while the pick is stored under
 * the hostname; without this the alias lookup misses and no certificate is sent. Every [createSocket]
 * overload sets or clears this: host-less paths clear it so a pooled thread never presents one host's
 * certificate to another host's handshake.
 */
private val handshakeSniHost = ThreadLocal<String>()

/** Captures the SNI host for [MtlsKeyManager], creating sockets from the [delegate] current at each call. */
internal class SniCapturingSocketFactory(private val delegate: () -> SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate().defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate().supportedCipherSuites
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket? {
        if (host != null) handshakeSniHost.set(host) else handshakeSniHost.remove()
        return delegate().createSocket(s, host, port, autoClose)
    }
    override fun createSocket(host: String?, port: Int): Socket? {
        if (host != null) handshakeSniHost.set(host) else handshakeSniHost.remove()
        return delegate().createSocket(host, port)
    }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket? {
        if (host != null) handshakeSniHost.set(host) else handshakeSniHost.remove()
        return delegate().createSocket(host, port, localHost, localPort)
    }
    // No hostname on these paths: clear any previous handshake's host so a pooled thread
    // never presents one host's certificate to another host's handshake.
    override fun createSocket(host: InetAddress?, port: Int): Socket? {
        handshakeSniHost.remove()
        return delegate().createSocket(host, port)
    }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket? {
        handshakeSniHost.remove()
        return delegate().createSocket(address, port, localAddress, localPort)
    }
    override fun createSocket(): Socket? {
        handshakeSniHost.remove()
        return delegate().createSocket()
    }
}

/**
 * An [X509ExtendedKeyManager] that presents the alias [resolveAlias] picks for the socket's host, with the key
 * and chain the system store ([KeyChain]) holds for it. The key material never enters the app: the store's
 * [PrivateKey] is only handed to the TLS stack, on its own thread. No alias for the host means no certificate,
 * so gateways without one handshake exactly as before.
 */
internal class MtlsKeyManager(
    private val resolveAlias: (host: String) -> String?,
    private val privateKey: (alias: String) -> PrivateKey?,
    private val chain: (alias: String) -> Array<X509Certificate>?,
    private val sniHost: () -> String? = { handshakeSniHost.get() },
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(keyTypes: Array<String>, issuers: Array<Principal>?, socket: Socket?): String? {
        // The socket address is usually numeric: OkHttp connects by IP, so the stored hostname never
        // matches it. The SNI host captured from the socket factory is authoritative; the socket host
        // stays as a second try (IP-literal gateways pick under the IP string, which matches it).
        // Nothing else is guessed: a handshake whose host matches no pick presents no certificate.
        val socketHost = (socket?.remoteSocketAddress as? InetSocketAddress)?.hostString
        return listOfNotNull(sniHost(), socketHost).distinct().firstNotNullOfOrNull { resolveAlias(it) }
    }

    /** OkHttp handshakes on sockets, never engines, so there is no host to pick an alias for. */
    override fun chooseEngineClientAlias(keyTypes: Array<String>, issuers: Array<Principal>?, engine: SSLEngine?): String? = null

    override fun chooseServerAlias(keyType: String, issuers: Array<Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String): Array<X509Certificate>? = chain(alias)

    override fun getPrivateKey(alias: String): PrivateKey? = privateKey(alias)

    override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String>? = null

    override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String>? = null
}

/**
 * The gateway engine: OkHttp whose TLS presents each gateway's client certificate. The lookup reads the live
 * saved gateways (a pending Connect-screen pick first), so switching or editing gateways needs no rebuild, and
 * the same [HttpClient] serves the REST calls and the `/api/ws` upgrade. The context only feeds [KeyChain].
 *
 * When a certificate changes ([ClientCertAliases.changed]), idle pooled connections are dropped and the TLS
 * session cache starts empty, so the next request handshakes again with the new certificate instead of reusing
 * a connection or resuming a session made with the old one (or none). A connection in use at that moment, such
 * as an open `/api/ws` socket or a call still running, keeps its certificate until it closes.
 */
internal actual fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository): HttpClientEngine {
    val keyManager = MtlsKeyManager(
        resolveAlias = { host -> clientCertAliasFor(host, certs, gateways.list.value.gateways) },
        // MtlsAppContext is read here, on the handshake thread, not at engine-construction time: the engine is
        // built eagerly during startKoin while the platform module's createdAtStart setter still hasn't run.
        // A null context (startup, or host tests) means no certificate rather than a crash. Not cached: a
        // certificate renewed under the same alias, or a grant taken back, counts from the next handshake.
        privateKey = { alias ->
            val ctx = MtlsAppContext.context
            if (ctx == null) null else runCatching { KeyChain.getPrivateKey(ctx, alias) }.getOrNull()
        },
        chain = { alias ->
            val ctx = MtlsAppContext.context
            if (ctx == null) null else runCatching { KeyChain.getCertificateChain(ctx, alias) }.getOrNull()
        },
    )
    val trustManager = defaultTrustManager()
    val pool = ConnectionPool()
    val ssl = AtomicReference(tlsContext(keyManager, trustManager))
    // Changes come from the main thread, and closing a TLS socket there throws NetworkOnMainThreadException.
    val evictor = Executors.newSingleThreadExecutor { Thread(it, "mtls-evict").apply { isDaemon = true } }
    certs.onChanged {
        // Swapped at once, so the next handshake (a probe right after a pick) already starts a fresh session.
        ssl.set(tlsContext(keyManager, trustManager))
        evictor.execute { runCatching { pool.evictAll() } }
    }
    return OkHttp.create {
        config {
            connectionPool(pool)
            sslSocketFactory(SniCapturingSocketFactory { ssl.get().socketFactory }, trustManager)
        }
    }
}

/** A new context, so a new client session cache: nothing resumes a session made with another certificate. */
private fun tlsContext(keyManager: KeyManager, trustManager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }

/** Holds the application context for [KeyChain], set once from the platform module at startup. */
internal object MtlsAppContext {
    /** Null until the platform module publishes it (and in host tests): handshakes then present no certificate. */
    @Volatile var context: Context? = null
}

private fun defaultTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
