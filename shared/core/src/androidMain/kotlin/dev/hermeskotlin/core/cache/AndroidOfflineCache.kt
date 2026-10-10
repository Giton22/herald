package dev.hermeskotlin.core.cache

import android.content.Context
import android.util.Base64
import androidx.room.Room
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a key of the cache's own, kept in the [store] (itself sealed by the Keystore), so a row costs
 * no round trip to the Keystore. A store that lost the key makes a new one, and the old rows stop opening.
 */
class KeystoreSealer(private val store: KeyValueStore) : Sealer {
    private val mutex = Mutex()
    private var key: SecretKeySpec? = null

    override suspend fun seal(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain.encodeToByteArray())
    }

    override suspend fun open(sealed: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).decodeToString()
    }.getOrNull()

    private suspend fun key(): SecretKeySpec = mutex.withLock {
        key ?: run {
            val bytes = store.get(STORE_KEY)?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == KEY_BYTES }
                ?: ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
                    .also { store.put(STORE_KEY, Base64.encodeToString(it, Base64.NO_WRAP)) }
            SecretKeySpec(bytes, "AES").also { key = it }
        }
    }

    private companion object {
        const val STORE_KEY = "offline_cache_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** The cache's database file in the app's private storage. It holds only a copy, so a new version starts it over. */
fun offlineDatabase(context: Context): OfflineDatabase =
    Room.databaseBuilder<OfflineDatabase>(context.applicationContext, context.getDatabasePath("offline-cache.db").absolutePath)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
