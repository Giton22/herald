package dev.hermeskotlin.android.push

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.NtfyClient
import dev.hermeskotlin.core.push.PushCrypto
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.KeyPair
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone's push identity and what it trusts, kept across restarts: its two P-256 key pairs (private
 * halves encrypted by a key that lives in the AndroidKeyStore and never leaves it), its topics, the ntfy
 * server, and the gateway keys pinned at registration. Shared preferences are excluded from backups
 * (allowBackup=false), and the wrapping key can't be exported, so a copy of the app's files can't decrypt them.
 */
class PushStore(context: Context) : PushKeys {

    private val prefs = context.getSharedPreferences("herald_push", Context.MODE_PRIVATE)

    /** Ticks whenever the identity or the pinned gateways change, so the listener can start or restart. */
    val changes = MutableStateFlow(0)

    override val deviceId: String get() = device().id

    override fun registration(name: String): PushRegistration = device().let { d ->
        PushRegistration(
            deviceId = d.id,
            name = name,
            server = d.server,
            pushTopic = d.pushTopic,
            replyTopic = d.replyTopic,
            encPub = PushCrypto.encodePublic(d.enc.public),
            sigPub = PushCrypto.encodePublic(d.sig.public),
        )
    }

    override fun pinned(gatewayUrl: String): PushGateway? = gateways()[gatewayUrl]

    /** This phone's identity, made on first use. */
    data class Device(
        val id: String,
        val enc: KeyPair,
        val sig: KeyPair,
        val pushTopic: String,
        val replyTopic: String,
        val server: String,
    )

    @Synchronized
    fun device(): Device {
        val id = prefs.getString(KEY_ID, null)
        if (id != null) {
            runCatching { load(id) }.getOrNull()?.let { return it }
        }
        // First use, or keys that can't be unwrapped any more (keystore reset): start a fresh identity.
        val fresh = Device(
            id = UUID.randomUUID().toString(),
            enc = PushCrypto.generateKeyPair(),
            sig = PushCrypto.generateKeyPair(),
            pushTopic = PushCrypto.newTopic(),
            replyTopic = PushCrypto.newTopic(),
            server = prefs.getString(KEY_SERVER, null) ?: NtfyClient.DEFAULT_SERVER,
        )
        prefs.edit {
            putString(KEY_ID, fresh.id)
            putString(KEY_ENC, wrap(PushCrypto.encodePrivate(fresh.enc.private)))
            putString(KEY_SIG, wrap(PushCrypto.encodePrivate(fresh.sig.private)))
            putString(KEY_ENC_PUB, PushCrypto.encodePublic(fresh.enc.public))
            putString(KEY_SIG_PUB, PushCrypto.encodePublic(fresh.sig.public))
            putString(KEY_PUSH_TOPIC, fresh.pushTopic)
            putString(KEY_REPLY_TOPIC, fresh.replyTopic)
            putString(KEY_SERVER, fresh.server)
            remove(KEY_GATEWAYS)
            remove(KEY_SINCE)
            remove(KEY_SEEN)
        }
        return fresh
    }

    /** Forgets this identity: new keys and topics next time, and every gateway must register it again. */
    @Synchronized
    fun reset() {
        prefs.edit { clear() }
        changes.update { it + 1 }
    }

    /** The ntfy server pushes go through. Changing it starts a new identity, which every gateway must register again. */
    @Synchronized
    fun setServer(url: String) {
        require(NtfyClient.validServer(url)) { "https only" }
        prefs.edit {
            clear()
            putString(KEY_SERVER, url.trim().trimEnd('/'))
        }
        changes.update { it + 1 }
    }

    /** Every gateway this phone receives pushes from: the push topic carries all of them. */
    fun gateways(): Map<String, PushGateway> = prefs.getString(KEY_GATEWAYS, null)?.let {
        runCatching { HermesJson.decodeFromString(MapSerializer(String.serializer(), PushGateway.serializer()), it) }.getOrNull()
    }.orEmpty()

    @Synchronized
    override fun pin(gatewayUrl: String, keys: PushGateway) {
        // Decoding refuses a malformed or off-curve key before it is trusted.
        PushCrypto.decodePublic(keys.sigPub)
        PushCrypto.decodePublic(keys.encPub)
        save(gateways() + (gatewayUrl to keys))
    }

    @Synchronized
    override fun unpin(gatewayUrl: String) = save(gateways() - gatewayUrl)

    private fun save(map: Map<String, PushGateway>) {
        val before = prefs.getString(KEY_GATEWAYS, null)
        val after = HermesJson.encodeToString(MapSerializer(String.serializer(), PushGateway.serializer()), map)
        if (before == after) return
        prefs.edit { putString(KEY_GATEWAYS, after) }
        changes.update { it + 1 }
    }

    /** The last ntfy message id read, so a reconnect picks up what came meanwhile. */
    var since: String?
        get() = prefs.getString(KEY_SINCE, null)
        set(value) = prefs.edit { putString(KEY_SINCE, value) }

    /** Recently accepted message ids, so a replayed envelope is refused even after a restart. */
    var seen: Map<String, Long>
        get() = prefs.getString(KEY_SEEN, null)?.let {
            runCatching { HermesJson.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), it) }.getOrNull()
        }.orEmpty()
        set(value) = prefs.edit { putString(KEY_SEEN, HermesJson.encodeToString(MapSerializer(String.serializer(), Long.serializer()), value)) }

    private fun load(id: String): Device {
        val encPub = PushCrypto.decodePublic(prefs.getString(KEY_ENC_PUB, null)!!)
        val sigPub = PushCrypto.decodePublic(prefs.getString(KEY_SIG_PUB, null)!!)
        return Device(
            id = id,
            enc = KeyPair(encPub, PushCrypto.decodePrivate(unwrap(prefs.getString(KEY_ENC, null)!!))),
            sig = KeyPair(sigPub, PushCrypto.decodePrivate(unwrap(prefs.getString(KEY_SIG, null)!!))),
            pushTopic = prefs.getString(KEY_PUSH_TOPIC, null)!!,
            replyTopic = prefs.getString(KEY_REPLY_TOPIC, null)!!,
            server = prefs.getString(KEY_SERVER, null) ?: NtfyClient.DEFAULT_SERVER,
        )
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(false) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return PushCrypto.b64(cipher.iv) + "." + PushCrypto.b64(cipher.doFinal(plain))
    }

    private fun unwrap(stored: String): ByteArray {
        val (iv, ct) = stored.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, PushCrypto.unb64(iv)))
        }
        return cipher.doFinal(PushCrypto.unb64(ct))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_ALIAS = "herald_push_wrap"
        const val KEY_ID = "device_id"
        const val KEY_ENC = "enc_priv"
        const val KEY_SIG = "sig_priv"
        const val KEY_ENC_PUB = "enc_pub"
        const val KEY_SIG_PUB = "sig_pub"
        const val KEY_PUSH_TOPIC = "push_topic"
        const val KEY_REPLY_TOPIC = "reply_topic"
        const val KEY_SERVER = "server"
        const val KEY_GATEWAYS = "gateways"
        const val KEY_SINCE = "since"
        const val KEY_SEEN = "seen"
    }
}
