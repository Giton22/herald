package dev.hermeskotlin.core.gateway

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlin.jvm.JvmInline

class InvalidGatewayUrlException(message: String) : IllegalArgumentException(message)

/**
 * Normalized base URL of a remote Hermes dashboard ("remote gateway"), e.g. `http://100.64.0.1:9119`.
 *
 * Mirrors the desktop's `normalizeRemoteBaseUrl`: scheme-less `host:port` gets `http://`, only
 * http/https are accepted, query/fragment are dropped and trailing slashes are trimmed.
 */
@JvmInline
value class GatewayUrl private constructor(val value: String) {

    /** `ws(s)://host[:port][/prefix]/api/ws` for the JSON-RPC socket. */
    val webSocketUrl: String
        get() {
            val url = Url(value)
            val scheme = if (url.protocol == URLProtocol.HTTPS) "wss" else "ws"
            return "$scheme://${url.hostWithPortIfSpecified}${url.encodedPath.trimEnd('/')}/api/ws"
        }

    /** Host name only, e.g. `hermes.example.ts.net`. */
    val host: String get() = Url(value).host

    /**
     * Plain `http://` to a host that isn't on a private network, so the password, session cookie and
     * every message cross the internet readable. A Tailscale address is already encrypted by Tailscale.
     */
    val isExposed: Boolean get() = Url(value).protocol != URLProtocol.HTTPS && !isPrivateHost(host)

    fun resolve(path: String): String = value + "/" + path.trimStart('/')

    override fun toString(): String = value

    companion object {
        private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

        fun parse(raw: String): GatewayUrl {
            var input = raw.trim()
            if (input.isEmpty()) throw InvalidGatewayUrlException("Gateway URL is required.")
            if (!SCHEME.containsMatchIn(input)) input = "http://$input"

            val scheme = input.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") {
                throw InvalidGatewayUrlException("Gateway URL must be http:// or https://, got $scheme://")
            }

            val builder = try {
                URLBuilder().takeFrom(input)
            } catch (e: Exception) {
                throw InvalidGatewayUrlException("Gateway URL is not valid: ${e.message}")
            }
            if (builder.host.isBlank()) throw InvalidGatewayUrlException("Gateway URL has no host.")

            builder.parameters.clear()
            builder.fragment = ""
            builder.encodedPath = builder.encodedPath.trimEnd('/')
            return GatewayUrl(builder.buildString().trimEnd('/'))
        }

        fun parseOrNull(raw: String): GatewayUrl? = try {
            parse(raw)
        } catch (_: InvalidGatewayUrlException) {
            null
        }
    }
}

/** Loopback, LAN and Tailscale (100.64.0.0/10, MagicDNS `.ts.net`) hosts, and local-only names. */
internal fun isPrivateHost(host: String): Boolean {
    val name = host.lowercase().trim('[', ']').trimEnd('.')
    if (name == "localhost" || PRIVATE_SUFFIXES.any { name.endsWith(it) } || '.' !in name && ':' !in name) return true
    if (':' in name) return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe80")
    val octets = name.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4) return false
    val (a, b) = octets
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
}

private val PRIVATE_SUFFIXES = listOf(".ts.net", ".local", ".lan", ".home.arpa", ".internal", ".localhost")

private val Url.hostWithPortIfSpecified: String
    get() = if (specifiedPort == 0 || specifiedPort == protocol.defaultPort) host else "$host:$specifiedPort"
