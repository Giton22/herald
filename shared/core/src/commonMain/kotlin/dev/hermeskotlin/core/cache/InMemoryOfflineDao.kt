package dev.hermeskotlin.core.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An [OfflineDao] in memory, for tests and previews; the transactions run one at a time. */
class InMemoryOfflineDao : OfflineDao {
    private val lists = mutableListOf<SavedListRow>()
    private val transcripts = mutableListOf<SavedTranscriptRow>()
    private val messages = mutableListOf<SavedMessageRow>()
    private val mutex = Mutex()

    override suspend fun list(gateway: String, profile: String, filter: String) = mutex.withLock {
        lists.filter { it.gateway == gateway && it.profile == profile && it.filter == filter }.sortedBy { it.position }
    }

    override suspend fun clearList(gateway: String, profile: String, filter: String) = mutex.withLock {
        lists.removeAll { it.gateway == gateway && it.profile == profile && it.filter == filter }
        Unit
    }

    override suspend fun insertList(rows: List<SavedListRow>) = mutex.withLock { lists += rows }

    override suspend fun removeListRow(gateway: String, profile: String, id: String) = mutex.withLock {
        lists.removeAll { it.gateway == gateway && it.profile == profile && it.id == id }
        Unit
    }

    override suspend fun transcript(gateway: String, profile: String, sessionId: String) = mutex.withLock {
        transcripts.firstOrNull { it.gateway == gateway && it.profile == profile && it.sessionId == sessionId }
    }

    override suspend fun messages(gateway: String, profile: String, sessionId: String) = mutex.withLock {
        messages.filter { it.gateway == gateway && it.profile == profile && it.sessionId == sessionId }.sortedBy { it.position }
    }

    override suspend fun insertTranscript(transcript: SavedTranscriptRow) = mutex.withLock { transcripts += transcript }

    override suspend fun insertMessages(rows: List<SavedMessageRow>) = mutex.withLock { messages += rows }

    override suspend fun clearTranscript(gateway: String, profile: String, sessionId: String) = mutex.withLock {
        transcripts.removeAll { it.gateway == gateway && it.profile == profile && it.sessionId == sessionId }
        Unit
    }

    override suspend fun clearMessages(gateway: String, profile: String, sessionId: String) = mutex.withLock {
        messages.removeAll { it.gateway == gateway && it.profile == profile && it.sessionId == sessionId }
        Unit
    }

    override suspend fun transcriptsBeyond(keep: Int) = mutex.withLock {
        transcripts.sortedByDescending { it.savedAt }.drop(keep)
    }

    override suspend fun clearProfileLists(gateway: String, profile: String) = mutex.withLock {
        lists.removeAll { it.gateway == gateway && it.profile == profile }
        Unit
    }

    override suspend fun clearProfileTranscripts(gateway: String, profile: String) = mutex.withLock {
        transcripts.removeAll { it.gateway == gateway && it.profile == profile }
        Unit
    }

    override suspend fun clearProfileMessages(gateway: String, profile: String) = mutex.withLock {
        messages.removeAll { it.gateway == gateway && it.profile == profile }
        Unit
    }

    override suspend fun clearGatewayLists(gateway: String) = mutex.withLock {
        lists.removeAll { it.gateway == gateway }
        Unit
    }

    override suspend fun clearGatewayTranscripts(gateway: String) = mutex.withLock {
        transcripts.removeAll { it.gateway == gateway }
        Unit
    }

    override suspend fun clearGatewayMessages(gateway: String) = mutex.withLock {
        messages.removeAll { it.gateway == gateway }
        Unit
    }
}

/** Keeps text as it is: for tests and previews only. */
object PlainSealer : Sealer {
    override suspend fun seal(plain: String): ByteArray = plain.encodeToByteArray()

    override suspend fun open(sealed: ByteArray): String = sealed.decodeToString()
}
