package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.LinuxKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/** Import is available only with the encrypted Linux store; ordinary desktop stores never hold keys. */
fun linuxClientCertificates(store: KeyValueStore): LinuxClientCertificates? =
    if (store is LinuxKeyValueStore) LinuxClientCertificates(store) else null

class ClientCertificateException : IllegalArgumentException(
    "Kyllene couldn't open this client certificate. Check the password and choose a PKCS#12 file with one unexpired client certificate, its matching private key and certificate chain.",
)

/** Imported keys live only in the encrypted store, selected for one hostname, never a browser. */
class LinuxClientCertificates internal constructor(private val store: KeyValueStore) {
    // The import port is metadata, not an isolation boundary: selection matches Android’s hostname lookup.
    @Serializable private data class Record(val version: Int, val host: String, val port: Int, val data: String)

    /** Consumes and clears [password]; the encrypted record does not retain the import password. */
    suspend fun import(host: String, port: Int, bytes: ByteArray, password: CharArray): String {
        try {
            return withContext(Dispatchers.IO) {
                val record = checked {
                    require(host.isNotBlank() && port in 1..65535 && bytes.size in 1..MAX_BYTES)
                    val source = KeyStore.getInstance("PKCS12").apply { bytes.inputStream().use { load(it, password) } }
                    val (key, chain) = identity(source, password)
                    val normalized = KeyStore.getInstance("PKCS12").apply {
                        load(null, charArrayOf())
                        setKeyEntry("client", key, charArrayOf(), chain)
                    }
                    val data = ByteArrayOutputStream().use { out -> normalized.store(out, charArrayOf()); out.toByteArray() }
                    try { Record(1, accessHostKey(host), port, Base64.getEncoder().encodeToString(data)) }
                    finally { data.fill(0) }
                }
                val alias = "client-${UUID.randomUUID()}"
                // Return only after the encrypted commit; store errors retain their actionable storage guidance.
                store.put("linux.mtls.$alias", Json.encodeToString(record))
                alias
            }
        } finally { password.fill('\u0000') }
    }

    internal suspend fun material(alias: String, host: String, port: Int): KeyStore? = withContext(Dispatchers.IO) {
        if (!alias.startsWith("client-") || alias.length != 43 || port !in 1..65535) return@withContext null
        val value = store.get("linux.mtls.$alias") ?: return@withContext null
        val record = checked {
            Json.decodeFromString<Record>(value).also {
                require(it.version == 1 && it.host.isNotBlank() && it.port in 1..65535 && it.data.length <= MAX_BYTES * 2)
            }
        }
        if (record.host != accessHostKey(host)) return@withContext null
        checked {
            val bytes = Base64.getDecoder().decode(record.data)
            try {
                KeyStore.getInstance("PKCS12").apply {
                    bytes.inputStream().use { load(it, charArrayOf()) }
                    identity(this, charArrayOf())
                }
            } finally { bytes.fill(0) }
        }
    }

    private fun identity(source: KeyStore, password: CharArray): Pair<PrivateKey, Array<X509Certificate>> {
        val aliases = source.aliases().toList().filter(source::isKeyEntry)
        require(aliases.size == 1)
        val key = source.getKey(aliases.single(), password) as? PrivateKey ?: throw ClientCertificateException()
        val chain = source.getCertificateChain(aliases.single())?.map { it as X509Certificate }?.toTypedArray()
            ?.takeIf { it.isNotEmpty() } ?: throw ClientCertificateException()
        chain.forEach { it.checkValidity() }
        chain.toList().zipWithNext().forEach { (certificate, issuer) ->
            require(certificate.issuerX500Principal == issuer.subjectX500Principal)
            certificate.verify(issuer.publicKey)
        }
        for (index in 1 until chain.size) {
            val issuer = chain[index]
            val below = (1 until index).count { chain[it].subjectX500Principal != chain[it].issuerX500Principal }
            require(issuer.basicConstraints >= below && issuer.keyUsage?.let { it.getOrNull(5) == true } != false)
        }
        if (chain.size > 1) {
            // Check the supplied path offline, with its final certificate as an explicit local anchor.
            // This does not assert that the gateway trusts it, and never fetches issuers or revocation URLs.
            val path = CertificateFactory.getInstance("X.509").generateCertPath(chain.dropLast(1))
            val parameters = PKIXParameters(setOf(TrustAnchor(chain.last(), null))).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(path, parameters)
        }
        val leaf = chain.first()
        require(leaf.basicConstraints == -1 && leaf.keyUsage?.getOrNull(0) != false)
        require(leaf.extendedKeyUsage?.let { "1.3.6.1.5.5.7.3.2" in it || "2.5.29.37.0" in it } != false)
        val algorithm = when (key.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> throw ClientCertificateException()
        }
        val challenge = UUID.randomUUID().toString().toByteArray()
        val signed = Signature.getInstance(algorithm).run { initSign(key); update(challenge); sign() }
        require(Signature.getInstance(algorithm).run { initVerify(leaf.publicKey); update(challenge); verify(signed) })
        return key to chain
    }

    private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (_: Exception) { throw ClientCertificateException() }

    companion object { const val MAX_BYTES = 1_048_576 }
}
