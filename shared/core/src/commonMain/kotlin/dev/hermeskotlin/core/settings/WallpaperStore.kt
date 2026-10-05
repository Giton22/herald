package dev.hermeskotlin.core.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One file on the device, read and written whole. */
interface BlobFile {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    suspend fun delete()
}

/** Non-persistent [BlobFile] for tests and previews. */
class InMemoryBlobFile(var bytes: ByteArray? = null) : BlobFile {
    override suspend fun read(): ByteArray? = bytes
    override suspend fun write(bytes: ByteArray) {
        this.bytes = bytes
    }
    override suspend fun delete() {
        bytes = null
    }
}

/** The chat's background: an encoded image, or none. */
class Wallpaper(val image: ByteArray?)

/**
 * Holds the [Wallpaper], kept on the device like [SettingsStore]: null until the stored copy is read.
 * Too big for the key/value store, so the image gets a file of its own. Changes show at once and are saved behind.
 */
class WallpaperStore(private val file: BlobFile, private val scope: CoroutineScope) {

    private val _wallpaper = MutableStateFlow<Wallpaper?>(null)
    val wallpaper: StateFlow<Wallpaper?> = _wallpaper.asStateFlow()

    private val writes = Mutex()

    init {
        scope.launch {
            val stored = runCatching { file.read() }.getOrNull()
            // A change made before the read finished wins over the stored copy.
            _wallpaper.compareAndSet(null, Wallpaper(stored))
        }
    }

    fun set(image: ByteArray) = change(Wallpaper(image))

    fun clear() = change(Wallpaper(null))

    private fun change(next: Wallpaper) {
        _wallpaper.value = next
        scope.launch {
            writes.withLock {
                // Only the newest image matters if several writes queue up.
                if (_wallpaper.value !== next) return@withLock
                runCatching { next.image?.let { file.write(it) } ?: file.delete() }
            }
        }
    }
}
