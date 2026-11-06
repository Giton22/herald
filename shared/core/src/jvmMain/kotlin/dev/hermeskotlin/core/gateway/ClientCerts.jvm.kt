package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Protocol
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal actual fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository, store: KeyValueStore): HttpClientEngine =
    linuxClientCertificates(store)?.let { desktopMtlsEngine(certs, gateways, it, desktopTrustManager()) } ?: OkHttp.create()

internal fun desktopMtlsEngine(
    certs: ClientCertAliases,
    gateways: GatewayRepository,
    identities: LinuxClientCertificates,
    trust: X509TrustManager,
): HttpClientEngine {
    fun context(keys: KeyStore? = null): SSLContext = SSLContext.getInstance("TLS").apply {
        val managers = keys?.let {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(it, charArrayOf()) }.keyManagers
        }
        init(managers ?: emptyArray(), arrayOf(trust), null)
    }
    val ordinary = context().socketFactory
    // The decrypted identity's context per alias and host, until a selection changes: opening it reads the encrypted
    // store and checks the key, too much for every connection. A store that can't answer yet isn't remembered.
    val selected = ConcurrentHashMap<Pair<String, String>, SSLSocketFactory>()
    val pool = ConnectionPool()
    certs.onChanged {
        // A new context cannot resume a TLS session authenticated with a previous selection, and no idle
        // connection made with one is reused. Done before the change returns, so the next request already sees it.
        selected.clear()
        pool.evictAll()
    }
    val sockets = ClientSocketFactory(ordinary) { host, port ->
        val alias = clientCertAliasFor(host, certs, gateways.list.value.gateways) ?: return@ClientSocketFactory ordinary
        val key = alias to accessHostKey(host)
        selected[key] ?: runBlocking(Dispatchers.IO) { identities.material(alias, host, port) }
            ?.let { context(it).socketFactory.also { factory -> selected[key] = factory } }
            ?: ordinary
    }
    return OkHttp.create {
        config {
            sslSocketFactory(sockets, trust)
            // Idle connections are pooled and dropped when a selection changes. HTTP/1.1 only: a busy connection
            // is never shared, so a request after a change can't ride one authenticated with the old selection.
            connectionPool(pool)
            protocols(listOf(Protocol.HTTP_1_1))
            followRedirects(false)
            followSslRedirects(false)
        }
    }
}

/** The actual TLS destination decides the key; host-less socket paths can never guess a selection. */
private class ClientSocketFactory(
    private val ordinary: SSLSocketFactory,
    private val select: (String, Int) -> SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = ordinary.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = ordinary.supportedCipherSuites
    override fun createSocket(s: Socket?, host: String, port: Int, autoClose: Boolean): Socket =
        select(host, port).createSocket(s, host, port, autoClose)
    override fun createSocket(host: String, port: Int): Socket = select(host, port).createSocket(host, port)
    override fun createSocket(host: String, port: Int, local: InetAddress?, localPort: Int): Socket =
        select(host, port).createSocket(host, port, local, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = select(host.hostAddress, port).createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress?, localPort: Int): Socket =
        select(host.hostAddress, port).createSocket(host, port, local, localPort)
    override fun createSocket(): Socket = ordinary.createSocket()
}

private fun desktopTrustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().single()
    }
