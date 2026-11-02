package dev.hermeskotlin.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SecureStorageException(val reason: Reason) : IOException(reason.guidance) {
    enum class Reason(val guidance: String) {
        Unavailable("Kyllene couldn't retrieve its storage key. Unlock your desktop keyring and check that its Secret portal is available, then retry."),
        Cancelled("Storage unlock was cancelled. Retry to unlock it; existing data has been preserved."),
        Corrupt("Kyllene couldn't open its encrypted settings. Restore a known-good backup or use the original application identity. Existing data has been preserved."),
        Version("This settings format needs a different version of Kyllene. Existing data has been preserved."),
        Read("Kyllene couldn't read its settings, or the file exceeds 16 MiB. Check file access before retrying."),
        Write("Kyllene couldn't finish saving or cleaning up its settings. Existing copies have been preserved; check disk space and file access before retrying."),
        Uncertain("Kyllene couldn't confirm its settings commit. Check file access, then retry startup or quit and restart before making further changes."),
        Changed("Settings changed outside this window. Quit and restart Kyllene to reload them before saving."),
        Legacy("Conflicting or unfinished legacy settings were found. Existing files were preserved. Quit and resolve the older copy before retrying."),
        Unsupported("Kyllene couldn't verify its Flatpak application identity. Check the installation before retrying. No plaintext fallback is available."),
    }
}

/** Present or uncertain Flatpak metadata never permits a plaintext fallback. */
internal fun desktopKeyValueStore(
    file: File,
    osName: String = System.getProperty("os.name").orEmpty(),
    flatpakInfo: File = File("/.flatpak-info"),
    readMetadata: (File) -> String? = ::flatpakApplicationId,
): KeyValueStore {
    if (!osName.startsWith("Linux")) return FileKeyValueStore(file)
    // Every Flatpak sandbox has /.flatpak-info, so without it this is a host install, whatever FLATPAK_ID a launching
    // terminal or IDE passed on (it's only a hint, never an identity).
    val identity = try { readMetadata(flatpakInfo) }
        catch (_: NoSuchFileException) { return FileKeyValueStore(file) }
        catch (_: IOException) { null }
        catch (_: SecurityException) { null }
    return LinuxKeyValueStore(File(file.absoluteFile.parentFile, "settings.secure"), identity, legacy = file)
}

private fun flatpakApplicationId(info: File): String? {
    // Only this attribute read establishes absence. A later open failure stays fail-closed.
    val attributes = Files.readAttributes(info.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isRegularFile) return null
    return info.useLines { lines ->
        lines.dropWhile { it.trim() != "[Application]" }.drop(1).takeWhile { !it.startsWith("[") }
            .firstOrNull { it.startsWith("name=") }?.removePrefix("name=")?.takeIf { it.isNotBlank() }
    }
}

/** Unlocks before desktop consumers start; other platform stores need no extra startup step. */
suspend fun prepareDesktopStorage(store: KeyValueStore) {
    if (store is LinuxKeyValueStore) store.prepare()
}

/** Sets aside encrypted settings this key can't open, so the next start begins signed out; see [LinuxKeyValueStore.setAsideUnreadable]. */
suspend fun startDesktopStorageFresh(store: KeyValueStore) {
    if (store is LinuxKeyValueStore) store.setAsideUnreadable()
}

/** The folder the user may need to look in when storage can't open, or null when there's nothing to resolve by hand. */
fun desktopStorageFolder(store: KeyValueStore): String? = (store as? LinuxKeyValueStore)?.folder

internal class LinuxKeyValueStore(
    private val file: File,
    private val appId: String?,
    private val legacy: File = File(file.absoluteFile.parentFile, "settings.json"),
    private val deleteLegacy: (Path) -> Unit = { Files.delete(it) },
    private val disk: AtomicSettingsFile = AtomicSettingsFile(file),
    private val retrieve: suspend (String?) -> PortalSecret = { LinuxSecretPortal().retrieve(it) },
) : KeyValueStore {
    private class KeyMaterial(val key: SecretKey, val token: String?)
    private class Snapshot(val values: Map<String, String>, val key: SecretKey, val token: String?,
        val digest: ByteArray?, val legacyDigest: ByteArray?)
    private val mutex = Mutex()
    private val legacyDisk = AtomicSettingsFile(legacy)
    private var state: Snapshot? = null
    private var ready = false
    private var blocked: SecureStorageException? = null
    private var pending: Snapshot? = null
    private var pendingBytes: ByteArray? = null

    val folder: String get() = file.absoluteFile.parent

    suspend fun prepare() = mutex.withLock {
        if (blocked?.reason == SecureStorageException.Reason.Uncertain && pending != null) reconcileInitialCommit()
        load()
        Unit
    }

    /**
     * For encrypted settings that won't open with this key (the keyring was reset, or a different one answered):
     * renames the file aside, never deletes it, so a later key or a backup can still open it, and the next [prepare]
     * starts empty. Sign-ins are lost; everything in the file can be set up again.
     */
    suspend fun setAsideUnreadable() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val aside = File(file.absoluteFile.parentFile, file.name + ".unreadable-" + System.currentTimeMillis())
                try { Files.move(file.toPath(), aside.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                catch (_: IOException) { throw SecureStorageException(SecureStorageException.Reason.Write) }
                disk.syncDirectory()
            }
        }
        state = null; ready = false; blocked = null; pending = null; pendingBytes = null
    }

    // After startup a failed or uncertain save stops further writes, not reads of what was last committed: every
    // part of the app reads settings, and none of them can do anything with the error.
    override suspend fun get(key: String): String? = mutex.withLock {
        val committed = state
        if (ready && committed != null) committed.values[key] else load().values[key]
    }
    override suspend fun put(key: String, value: String) = mutex.withLock {
        val previous = load()
        commit(Snapshot(previous.values + (key to value), previous.key, previous.token, previous.digest, previous.legacyDigest))
        Unit
    }
    override suspend fun remove(key: String) = mutex.withLock {
        val previous = load()
        if (key in previous.values) commit(Snapshot(previous.values - key, previous.key, previous.token, previous.digest, previous.legacyDigest))
        Unit
    }

    private suspend fun load(): Snapshot {
        blocked?.let { throw it }
        state?.let { return if (ready) it else activate(it) }
        pending?.let { return activate(commit(it)) }
        if (appId.isNullOrBlank()) throw SecureStorageException(SecureStorageException.Reason.Unsupported)
        withContext(Dispatchers.IO) { checkLegacyPart() }
        val bytes = withContext(Dispatchers.IO) { disk.read() }
        try {
            val candidate: Snapshot
            if (bytes != null) {
                val root = SettingsEnvelope.parse(bytes)
                // An authoritative encrypted path can never become a legacy import, even if its JSON looks valid.
                if (!SettingsEnvelope.isEncrypted(root)) throw SecureStorageException(SecureStorageException.Reason.Corrupt)
                val envelope = SettingsEnvelope.read(root)
                val material = key(envelope.token)
                val payload = envelope.open(material.key, appId)
                candidate = Snapshot(payload.values, material.key, material.token ?: envelope.token,
                    AtomicSettingsFile.digest(bytes), payload.legacyDigest)
                if (candidate.token == envelope.token) {
                    state = candidate
                    return activate(candidate)
                }
            } else {
                val old = withContext(Dispatchers.IO) { legacyDisk.read() }
                try {
                    // Unreadable old settings are kept, not imported as empty; say it's that file, not the encrypted one.
                    val values = try {
                        old?.let { SettingsEnvelope.values(SettingsEnvelope.parse(it)) }.orEmpty()
                    } catch (_: SecureStorageException) {
                        throw SecureStorageException(SecureStorageException.Reason.Legacy)
                    }
                    val material = key(null)
                    candidate = Snapshot(values, material.key, material.token, null, AtomicSettingsFile.digest(old))
                } finally { old?.fill(0) }
            }
            pending = candidate
            return activate(commit(candidate))
        } finally { bytes?.fill(0) }
    }

    private suspend fun key(token: String?): KeyMaterial {
        val identity = appId ?: throw SecureStorageException(SecureStorageException.Reason.Unsupported)
        val secret = try { retrieve(token) }
            catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw SecureStorageException(SecureStorageException.Reason.Unavailable)
            }
            catch (failure: CancellationException) { throw failure }
            catch (failure: SecretPortalFailure) {
                throw SecureStorageException(if (failure.reason == SecretPortalFailure.Reason.Cancelled)
                    SecureStorageException.Reason.Cancelled else SecureStorageException.Reason.Unavailable)
            }
        return try { KeyMaterial(SettingsEnvelope.derive(secret.bytes, identity), secret.token) }
        finally { secret.bytes.fill(0) }
    }

    private suspend fun commit(candidate: Snapshot): Snapshot = withContext(Dispatchers.IO) {
        val identity = appId ?: throw SecureStorageException(SecureStorageException.Reason.Unsupported)
        val bytes = SettingsEnvelope.seal(candidate.values, candidate.key, identity, candidate.token, candidate.legacyDigest)
        if (pending === candidate) pendingBytes = bytes
        val digest = try {
            disk.commit(bytes, candidate.digest) { saved ->
                val opened = SettingsEnvelope.read(SettingsEnvelope.parse(saved)).open(candidate.key, identity)
                if (opened.values != candidate.values || !AtomicSettingsFile.same(opened.legacyDigest, candidate.legacyDigest)) {
                    throw SecureStorageException(SecureStorageException.Reason.Write)
                }
            }
        } catch (failure: SecureStorageException) {
            if (failure.reason in setOf(SecureStorageException.Reason.Uncertain, SecureStorageException.Reason.Changed)) blocked = failure
            throw failure
        }
        // Publish committed state before dispatching back, even if cancellation wins that return race.
        Snapshot(candidate.values, candidate.key, candidate.token, digest, candidate.legacyDigest)
            .also { state = it; pending = null; pendingBytes = null }
    }

    /**
     * The old store wrote settings.json.part and renamed it over settings.json. Beside a settings.json, or once the
     * encrypted file exists, a .part is a save that never landed, which that store would have overwritten next time:
     * it goes. Alone, it may hold the only copy, so startup stops for the user to resolve it.
     */
    private fun checkLegacyPart() {
        val part = File(legacy.parentFile, legacy.name + ".part").toPath()
        if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) return
        val superseded = Files.exists(legacy.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (!superseded) throw SecureStorageException(SecureStorageException.Reason.Legacy)
        try { Files.deleteIfExists(part) } catch (_: IOException) { throw SecureStorageException(SecureStorageException.Reason.Write) }
    }

    private suspend fun activate(snapshot: Snapshot): Snapshot = withContext(Dispatchers.IO) {
        checkLegacyPart()
        // Durability of the encrypted name must be confirmed before the only legacy copy can be removed.
        disk.syncDirectory()
        val current = disk.read()
        try {
            if (!AtomicSettingsFile.same(snapshot.digest, AtomicSettingsFile.digest(current))) {
                throw SecureStorageException(SecureStorageException.Reason.Changed)
            }
        } finally { current?.fill(0) }
        val old = legacyDisk.read()
        if (old != null) {
            try {
                if (snapshot.legacyDigest == null || !MessageDigest.isEqual(snapshot.legacyDigest, AtomicSettingsFile.digest(old))) {
                    throw SecureStorageException(SecureStorageException.Reason.Legacy)
                }
            } finally { old.fill(0) }
            try { deleteLegacy(legacy.toPath()) } catch (_: IOException) {
                val after = legacyDisk.read()
                try {
                    if (after != null) throw SecureStorageException(SecureStorageException.Reason.Write)
                } finally { after?.fill(0) }
            }
            disk.syncDirectory()
        }
        Snapshot(snapshot.values, snapshot.key, snapshot.token, snapshot.digest, null).also { state = it; ready = true }
    }

    /** Only an explicit startup retry can reconcile an initial commit whose directory sync was uncertain. */
    private suspend fun reconcileInitialCommit() = withContext(Dispatchers.IO) {
        val candidate = pending ?: return@withContext
        val current = disk.read()
        try {
            val intended = pendingBytes
            if (current != null && intended != null && MessageDigest.isEqual(current, intended)) {
                disk.syncDirectory()
                state = Snapshot(candidate.values, candidate.key, candidate.token, AtomicSettingsFile.digest(current), candidate.legacyDigest)
                pending = null; pendingBytes = null; blocked = null
            } else if (AtomicSettingsFile.same(candidate.digest, AtomicSettingsFile.digest(current))) {
                blocked = null
            } else throw blocked ?: SecureStorageException(SecureStorageException.Reason.Uncertain)
        } finally { current?.fill(0) }
    }
}
