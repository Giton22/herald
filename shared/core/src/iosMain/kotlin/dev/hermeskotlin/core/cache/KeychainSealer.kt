package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.auth.secureRandomBytes
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * AES-256-GCM with a key of the cache's own, kept in the [store] (the Keychain), so a row costs no Keychain
 * round trip. Each sealed row is the IV, the ciphertext and the tag. A store that lost the key makes a new one,
 * and the old rows stop opening.
 */
class KeychainSealer(private val store: KeyValueStore) : Sealer {
    private val mutex = Mutex()
    private var key: AES.GCM.Key? = null

    override suspend fun seal(plain: String): ByteArray = cipher().encrypt(plain.encodeToByteArray())

    override suspend fun open(sealed: ByteArray): String? = runCatching { cipher().decrypt(sealed).decodeToString() }.getOrNull()

    private suspend fun cipher() = key().cipher()

    private suspend fun key(): AES.GCM.Key = mutex.withLock {
        key ?: run {
            val bytes = store.get(STORE_KEY)?.let { runCatching { Base64.decode(it) }.getOrNull() }?.takeIf { it.size == KEY_BYTES }
                ?: secureRandomBytes(KEY_BYTES).also { store.put(STORE_KEY, Base64.encode(it)) }
            CryptographyProvider.Default.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, bytes).also { key = it }
        }
    }

    private companion object {
        const val STORE_KEY = "offline_cache_key"
        const val KEY_BYTES = 32
    }
}
