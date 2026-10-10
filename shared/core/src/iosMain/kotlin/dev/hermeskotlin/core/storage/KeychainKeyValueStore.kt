package dev.hermeskotlin.core.storage

import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.core.platform.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Generic-password Keychain items, one per key, under [service]. The Keychain encrypts them at rest with a
 * device key. They open after the first unlock since boot (so a push extension can read them in the background)
 * and never leave this device in a backup. [accessGroup] shares them with the app's extensions.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainKeyValueStore(
    private val service: String,
    private val accessGroup: String? = null,
) : KeyValueStore {

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val query = dictionary(item(key) + listOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne))
        try {
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status != errSecSuccess) return@memScoped null
                (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
            }
        } finally {
            CFRelease(query)
        }
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        val data = value.encodeToByteArray().toNSData()
        val query = dictionary(item(key))
        val update = dictionary(listOf(kSecValueData to data))
        try {
            var status = SecItemUpdate(query, update)
            if (status == errSecItemNotFound) {
                val add = dictionary(item(key) + listOf(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
                status = try {
                    SecItemAdd(add, null)
                } finally {
                    CFRelease(add)
                }
            }
            check(status == errSecSuccess) { "Couldn't save to the Keychain ($status)." }
        } finally {
            CFRelease(query)
            CFRelease(update)
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        val query = dictionary(item(key))
        try {
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
        Unit
    }

    private fun item(key: String): List<Pair<CFStringRef?, Any?>> = buildList {
        add(kSecClass to kSecClassGenericPassword)
        add(kSecAttrService to service)
        add(kSecAttrAccount to key)
        accessGroup?.let { add(kSecAttrAccessGroup to it) }
    }
}

/**
 * A CoreFoundation dictionary for the Security calls; the caller releases it. A value that is already a CF
 * pointer (a constant such as `kSecClassGenericPassword`) goes in as it is; a Kotlin or Foundation object is
 * bridged.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun dictionary(entries: List<Pair<CFStringRef?, Any?>>): CFMutableDictionaryRef {
    val dict = CFDictionaryCreateMutable(null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        ?: error("Out of memory.")
    for ((key, value) in entries) {
        if (value is CPointer<*>) {
            CFDictionaryAddValue(dict, key, value)
        } else {
            val bridged = CFBridgingRetain(value)
            CFDictionaryAddValue(dict, key, bridged)
            // The dictionary holds its own reference now.
            CFRelease(bridged)
        }
    }
    return dict
}
