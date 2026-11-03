package dev.hermeskotlin.core.storage

/**
 * Small string key/value store. The platform implementation encrypts values at rest
 * (Android: AES-GCM key in the Android Keystore), so it is safe for session cookies.
 */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    /** True only after a platform-confirmed synchronous persistent write. This does not
     * guarantee survival of power loss or hardware failure. Unsupported stores fail closed. */
    suspend fun putConfirmed(key: String, value: String): Boolean = false
    suspend fun remove(key: String)
}

/** Non-persistent store for tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
