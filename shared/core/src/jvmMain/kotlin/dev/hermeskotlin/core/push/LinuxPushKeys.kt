package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.LinuxKeyValueStore
import java.security.Signature
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only the encrypted Flatpak store may hold this identity; other desktop stores retain their existing behavior. */
fun linuxPushKeys(store: KeyValueStore): PushKeys? = if (store is LinuxKeyValueStore) LinuxPushKeys(store) else null

/** One identity and its pending revocations, committed together through the unlocked secure store. */
internal class LinuxPushKeys(private val store: KeyValueStore) : PushKeys {
    @Serializable
    private data class Device(val id: String, val encPrivate: String, val sigPrivate: String,
        val encPublic: String, val sigPublic: String, val pushTopic: String, val replyTopic: String)

    @Serializable
    private data class State(val version: Int, val device: Device?, val gatewayUrl: String?,
        val gateway: PushGateway?, val retired: Map<String, Set<String>>)

    override val deviceId: String get() = registration("").deviceId

    override fun registration(name: String): PushRegistration = locked {
        val state = read()
        val device = state.device ?: create().also { save(state.copy(device = it)) }
        PushRegistration(device.id, name, NtfyClient.DEFAULT_SERVER, device.pushTopic, device.replyTopic,
            device.encPublic, device.sigPublic)
    }

    override fun pinned(gatewayUrl: String): PushGateway? = locked {
        read().takeIf { it.gatewayUrl == gatewayUrl }?.gateway
    }

    override fun pinnedGatewayUrl(): String? = locked { read().gatewayUrl }

    override fun accepts(keys: PushGateway): Boolean = keys.version == 1 && runCatching {
        PushCrypto.decodePublic(keys.encPub)
        PushCrypto.decodePublic(keys.sigPub)
    }.isSuccess

    override fun pin(gatewayUrl: String, keys: PushGateway) = locked {
        require(gatewayUrl.isNotBlank() && accepts(keys)) { "The gateway's plugin sent unusable keys." }
        val state = read()
        check(state.device != null) { "Create a push registration before pinning its gateway." }
        check(state.gatewayUrl == null || state.gatewayUrl == gatewayUrl) { "Retire the previous gateway's identity first." }
        if (state.gatewayUrl != gatewayUrl || state.gateway != keys) save(state.copy(gatewayUrl = gatewayUrl, gateway = keys))
    }

    override fun retire(gatewayUrl: String) = locked {
        val state = read()
        val device = state.device ?: return@locked
        if (state.gatewayUrl != null && state.gatewayUrl != gatewayUrl) return@locked
        require(gatewayUrl.isNotBlank())
        save(state.copy(device = null, gatewayUrl = null, gateway = null,
            retired = state.retired + (gatewayUrl to (state.retired[gatewayUrl].orEmpty() + device.id))))
    }

    override fun retired(gatewayUrl: String): Set<String> = locked { read().retired[gatewayUrl].orEmpty() }

    override fun forgetRetired(gatewayUrl: String, deviceId: String) = locked {
        val state = read()
        val previous = state.retired[gatewayUrl].orEmpty()
        if (deviceId in previous) {
            val left = previous - deviceId
            save(state.copy(retired = if (left.isEmpty()) state.retired - gatewayUrl else state.retired + (gatewayUrl to left)))
        }
    }

    // PushKeys is synchronous. Startup unlocks the store first; disk commits run on IO before callers publish success.
    private fun read(): State {
        val text = runBlocking(Dispatchers.IO) {
            if (store is LinuxKeyValueStore) store.getChecked(KEY) else store.get(KEY)
        } ?: return State(1, null, null, null, emptyMap())
        return try {
            stateJson.decodeFromString(State.serializer(), text).also { state ->
                check(state.version == 1)
                check((state.gatewayUrl == null) == (state.gateway == null))
                state.gateway?.let { check(state.device != null && !state.gatewayUrl.isNullOrBlank() && accepts(it)) }
                state.device?.let { device ->
                    check(UUID.fromString(device.id).toString() == device.id)
                    check(TOPIC.matches(device.pushTopic) && TOPIC.matches(device.replyTopic) && device.pushTopic != device.replyTopic)
                    checkPair(device.encPrivate, device.encPublic)
                    checkPair(device.sigPrivate, device.sigPublic)
                }
                state.retired.forEach { (url, ids) ->
                    check(url.isNotBlank())
                    ids.forEach { check(UUID.fromString(it).toString() == it) }
                }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Kyllene couldn't open its push identity. Existing data has been preserved; restore a known-good backup before retrying.")
        }
    }

    private fun save(state: State) = runBlocking(Dispatchers.IO) {
        store.put(KEY, stateJson.encodeToString(State.serializer(), state))
    }

    private fun create(): Device {
        val enc = PushCrypto.generateKeyPair()
        val sig = PushCrypto.generateKeyPair()
        fun encode(key: java.security.PrivateKey): String {
            val bytes = PushCrypto.encodePrivate(key)
            return try { PushCrypto.b64(bytes) } finally { bytes.fill(0) }
        }
        return Device(UUID.randomUUID().toString(), encode(enc.private), encode(sig.private),
            PushCrypto.encodePublic(enc.public), PushCrypto.encodePublic(sig.public), PushCrypto.newTopic(), PushCrypto.newTopic())
    }

    private fun checkPair(encodedPrivate: String, encodedPublic: String) {
        val bytes = PushCrypto.unb64(encodedPrivate)
        val private = try { PushCrypto.decodePrivate(bytes) } finally { bytes.fill(0) }
        val public = PushCrypto.decodePublic(encodedPublic)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(PAIR_CHECK)
            sign()
        }
        check(Signature.getInstance("SHA256withECDSA").run {
            initVerify(public)
            update(PAIR_CHECK)
            verify(signature)
        })
    }

    // Serializes read/modify/commit even if a second adapter is created for this same store.
    private fun <T> locked(action: () -> T): T = synchronized(store, action)

    private companion object {
        const val KEY = "linux.push.identity.v1"
        // Missing fields must not become an intentionally retired identity.
        val stateJson = Json(HermesJson) { explicitNulls = true }
        val TOPIC = Regex("hp-[0-9a-f]{32}")
        val PAIR_CHECK = "herald-push/identity-pair".encodeToByteArray()
    }
}
