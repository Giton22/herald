package dev.hermeskotlin.core.cache

/** Encrypts what the offline cache writes to disk. [open] is null when the bytes can't be read (the key is gone). */
interface Sealer {
    suspend fun seal(plain: String): ByteArray

    suspend fun open(sealed: ByteArray): String?
}
