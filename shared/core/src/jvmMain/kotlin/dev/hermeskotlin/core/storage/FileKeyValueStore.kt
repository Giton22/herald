package dev.hermeskotlin.core.storage

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/**
 * The desktop's [KeyValueStore]: one JSON file of values. On Windows each value is sealed with DPAPI, so only
 * this Windows user can open it, as the Keystore does on Android. Elsewhere (an experiment for now) the file
 * is only kept private to the user.
 */
class FileKeyValueStore(private val file: File) : KeyValueStore {
    private val mutex = Mutex()
    private var values: MutableMap<String, String>? = null
    private val sealed = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    override suspend fun get(key: String): String? = mutex.withLock { load()[key] }?.let { stored ->
        runCatching { open(stored) }.getOrNull()
    }

    override suspend fun put(key: String, value: String) = mutex.withLock {
        load()[key] = seal(value)
        save()
    }

    override suspend fun remove(key: String) = mutex.withLock {
        if (load().remove(key) != null) save()
    }

    private suspend fun load(): MutableMap<String, String> = values ?: withContext(Dispatchers.IO) {
        val read = runCatching { Json.parseToJsonElement(file.readText()) as JsonObject }.getOrNull()
        read.orEmpty().mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap(mutableMapOf())
    }.also { values = it }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val text = JsonObject(load().mapValues { JsonPrimitive(it.value) }).toString()
        val partial = File(file.parentFile, "${file.name}.part")
        partial.writeText(text)
        if (!sealed) {
            partial.setReadable(false, false)
            partial.setReadable(true, true)
        }
        if (!partial.renameTo(file)) {
            file.delete()
            if (!partial.renameTo(file)) error("Couldn't save ${file.name}.")
        }
    }

    private fun seal(value: String): String =
        if (sealed) Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.encodeToByteArray())) else value

    private fun open(stored: String): String =
        if (sealed) Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)).decodeToString() else stored
}
