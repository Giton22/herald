package dev.hermeskotlin.core.gateway

import android.content.Context
import android.security.KeyChain
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

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
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(keyTypes: Array<String>, issuers: Array<Principal>?, socket: Socket?): String? {
        val host = (socket?.remoteSocketAddress as? InetSocketAddress)?.hostString ?: return null
        return resolveAlias(host)
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
 */
internal actual fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository): HttpClientEngine {
    val keyManager = MtlsKeyManager(
        resolveAlias = { host -> clientCertAliasFor(host, certs, gateways.list.value.gateways) },
        // MtlsAppContext is read here, on the handshake thread, not at engine-construction time: the engine is
        // built eagerly during startKoin while the platform module's createdAtStart setter still hasn't run.
        privateKey = { alias -> runCatching { KeyChain.getPrivateKey(MtlsAppContext.context, alias) }.getOrNull() },
        chain = { alias -> runCatching { KeyChain.getCertificateChain(MtlsAppContext.context, alias) }.getOrNull() },
    )
    val trustManager = defaultTrustManager()
    val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }
    return OkHttp.create {
        config {
            sslSocketFactory(ssl.socketFactory, trustManager)
        }
    }
}

/** Holds the application context for [KeyChain], set once from the platform module at startup. */
internal object MtlsAppContext {
    lateinit var context: Context
}

private fun defaultTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
