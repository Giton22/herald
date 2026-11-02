package dev.hermeskotlin.core.gateway

import io.ktor.client.engine.HttpClientEngine

/**
 * Aliases picked for hosts whose gateway isn't saved yet: the Connect screen probes before saving, so a pick
 * there waits here until the sign-in saves it onto the [SavedGateway]. Saved gateways carry their own alias,
 * and only they: a pending pick for a saved gateway's host would shadow it. Reads happen on the handshake
 * thread, so this is a lock-free copy-on-write map, never suspending.
 */
class ClientCertAliases {

    @Volatile private var pending: Map<String, String> = emptyMap()

    @Volatile private var listeners: List<(host: String) -> Unit> = emptyList()

    /** The alias picked for [host], or null when none was picked. */
    fun get(host: String): String? = pending[accessHostKey(host)]

    /** Remembers the pick for [host]; a null [alias] forgets it. Either way, [host]'s certificate may have changed. */
    fun set(host: String, alias: String?) {
        val key = accessHostKey(host)
        pending = if (alias == null) pending - key else pending + (key to alias)
        changed(key)
    }

    /** Forgets every pending pick: the add flow they were made for has ended, with a sign-in or a cancel. */
    fun clear() {
        val hosts = pending.keys
        pending = emptyMap()
        hosts.forEach(::changed)
    }

    /**
     * Tells the engine that the certificate for [host] changed, a saved gateway's too, so that it opens new
     * connections to it instead of reusing ones made with the old certificate.
     */
    fun changed(host: String) {
        val key = accessHostKey(host)
        listeners.forEach { it(key) }
    }

    /** Calls [listener] with the normalized host each time its certificate may have changed. */
    fun onChanged(listener: (host: String) -> Unit) {
        listeners = listeners + listener
    }
}

/**
 * The alias to present to [host]: the Connect screen's pending pick first, then the saved gateways.
 * Certificates are per gateway, but TLS only sees the host, like the Cloudflare Access tokens.
 */
fun clientCertAliasFor(host: String, pending: ClientCertAliases, saved: List<SavedGateway>): String? {
    val key = accessHostKey(host)
    return pending.get(key)
        ?: saved.firstOrNull {
            it.clientCertAlias != null && accessHostKey(GatewayUrl.parseOrNull(it.url)?.host ?: it.url) == key
        }?.clientCertAlias
}

/** The platform engine behind gateway traffic, presenting each gateway's client certificate when it has one. */
internal expect fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository): HttpClientEngine
