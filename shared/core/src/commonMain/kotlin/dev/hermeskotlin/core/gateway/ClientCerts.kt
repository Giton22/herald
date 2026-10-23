package dev.hermeskotlin.core.gateway

import io.ktor.client.engine.HttpClientEngine

/**
 * Aliases picked for hosts whose gateway isn't saved yet: the Connect screen probes before saving, so a pick
 * there waits here until the sign-in saves it onto the [SavedGateway]. Saved gateways carry their own alias.
 * Reads happen on the handshake thread, so this is a lock-free copy-on-write map, never suspending.
 */
class ClientCertAliases {

    @Volatile private var pending: Map<String, String> = emptyMap()

    /** The alias picked for [host], or null when none was picked. */
    fun get(host: String): String? = pending[accessHostKey(host)]

    /** Remembers the pick for [host]; a null [alias] forgets it. */
    fun set(host: String, alias: String?) {
        val key = accessHostKey(host)
        pending = if (alias == null) pending - key else pending + (key to alias)
    }

    /** Every pending pick, keyed by normalized host: the handshake fallback when the socket shows an IP. */
    fun all(): Map<String, String> = pending
}

/**
 * The alias to present to [host]: the Connect screen's pending pick first, then the saved gateways.
 * Certificates are per gateway, but TLS only sees the host, like the Cloudflare Access tokens.
 */
fun clientCertAliasFor(host: String, pending: ClientCertAliases, saved: List<SavedGateway>): String? {
    val key = accessHostKey(host)
    return pending.get(key)
        ?: saved.firstOrNull { accessHostKey(GatewayUrl.parseOrNull(it.url)?.host ?: it.url) == key }?.clientCertAlias
}

/** The platform engine behind gateway traffic, presenting each gateway's client certificate when it has one. */
internal expect fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository): HttpClientEngine
