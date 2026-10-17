package dev.hermeskotlin.core.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        // addressOf(0) needs an element, so an empty input passes no pointer at all.
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            bytes.usePinned { CC_SHA256(it.addressOf(0), bytes.size.convert(), out.addressOf(0)) }
        }
    }
    return digest.asByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "The system random generator failed ($status)." }
    return bytes
}
