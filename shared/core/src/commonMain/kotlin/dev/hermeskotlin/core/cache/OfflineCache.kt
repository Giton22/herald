package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.chat.fromFirstTurn
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A chat list as last read from the gateway; [savedAt] in epoch ms. */
data class SavedList(val sessions: List<SessionSummary>, val savedAt: Long)

/** A chat's newest rows as last read from the gateway; [savedAt] in epoch ms. */
data class SavedTranscript(
    val rows: List<SessionMessage>,
    val rowsSessionId: String?,
    val savedAt: Long,
)

/**
 * The device's copy of the chat list and of the newest rows of recently read chats, so they still show while the
 * gateway can't be reached. The gateway's answer always replaces what's saved; nothing here is ever sent back.
 * Rows are sealed with a device key before they're written. A failure to read or write only means no saved copy:
 * the cache never stands in the way of the gateway.
 */
class OfflineCache(
    private val dao: OfflineDao,
    private val sealer: Sealer,
    /** Where sealing and JSON run; tests keep them on their own dispatcher. */
    private val work: CoroutineContext = Dispatchers.Default,
    private val now: () -> Long,
) {
    suspend fun savedList(gateway: GatewayUrl, profile: String?, filter: SessionListFilter): SavedList? = quietly {
        val rows = dao.list(gateway.value, profile.key, filter.name)
        if (rows.isEmpty()) return@quietly null
        // A row that no longer opens (the key was reset) spoils the order the list had: show none of it.
        val sessions = rows.map { open(it.row, SessionSummary.serializer()) ?: return@quietly null }
        SavedList(sessions, rows.minOf { it.savedAt })
    }

    suspend fun saveList(gateway: GatewayUrl, profile: String?, filter: SessionListFilter, sessions: List<SessionSummary>) = quietly {
        val at = now()
        val rows = sessions.take(MAX_LIST_ROWS).distinctBy { it.id }.mapIndexed { index, session ->
            SavedListRow(gateway.value, profile.key, filter.name, session.id, index, at, seal(session, SessionSummary.serializer()))
        }
        dao.replaceList(gateway.value, profile.key, filter.name, rows)
    }

    suspend fun savedTranscript(gateway: GatewayUrl, profile: String?, sessionId: String): SavedTranscript? = quietly {
        val transcript = dao.transcript(gateway.value, profile.key, sessionId) ?: return@quietly null
        val rows = dao.messages(gateway.value, profile.key, sessionId).map {
            open(it.row, SessionMessage.serializer()) ?: return@quietly null
        }
        if (rows.isEmpty()) return@quietly null
        SavedTranscript(rows, transcript.rowsSessionId, transcript.savedAt)
    }

    /**
     * Saves the newest [MAX_TRANSCRIPT_ROWS] of [rows], from a turn's start, in place of what [sessionId] had saved,
     * and drops the least recently saved chats past [MAX_TRANSCRIPTS].
     */
    suspend fun saveTranscript(
        gateway: GatewayUrl,
        profile: String?,
        sessionId: String,
        rows: List<SessionMessage>,
        rowsSessionId: String?,
    ) = quietly {
        val newest = if (rows.size > MAX_TRANSCRIPT_ROWS) rows.takeLast(MAX_TRANSCRIPT_ROWS).fromFirstTurn() else rows
        val sealed = newest.map { row ->
            seal(row, SessionMessage.serializer()).takeIf { it.size <= MAX_ROW_BYTES }
                // Too big to read back (a huge tool output): kept without its text, which says so.
                ?: seal(row.copy(content = JsonPrimitive(TOO_BIG_NOTE), displayContent = null, reasoning = null), SessionMessage.serializer())
        }
        // Still too big (huge tool-call arguments): only the turns after it are kept.
        val tooBig = sealed.indexOfLast { it.size > MAX_ROW_BYTES }
        val kept = if (tooBig < 0) newest else newest.drop(tooBig + 1).fromFirstTurn()
        if (kept.isEmpty()) return@quietly dao.forgetTranscript(gateway.value, profile.key, sessionId)
        val transcript = SavedTranscriptRow(
            gateway = gateway.value,
            profile = profile.key,
            sessionId = sessionId,
            rowsSessionId = rowsSessionId,
            savedAt = now(),
        )
        val saved = sealed.takeLast(kept.size).mapIndexed { index, row -> SavedMessageRow(gateway.value, profile.key, sessionId, index, row) }
        dao.replaceTranscript(transcript, saved)
        dao.transcriptsBeyond(MAX_TRANSCRIPTS).forEach { dao.forgetTranscript(it.gateway, it.profile, it.sessionId) }
    }

    /** Drops [sessionId] from the device: it was deleted, or the gateway no longer has it. */
    suspend fun forgetChat(gateway: GatewayUrl, profile: String?, sessionId: String) = quietly {
        dao.forgetTranscript(gateway.value, profile.key, sessionId)
        dao.removeListRow(gateway.value, profile.key, sessionId)
    }

    /** Drops everything saved from [gateway], e.g. on signing out of it. */
    suspend fun forgetGateway(gateway: GatewayUrl) = quietly { dao.forgetGateway(gateway.value) }

    /** Drops everything saved from [profile] on [gateway]: it was deleted (a bot, with its chats). */
    suspend fun forgetProfile(gateway: GatewayUrl, profile: String) = quietly { dao.forgetProfile(gateway.value, profile) }

    private suspend fun <T> seal(value: T, serializer: KSerializer<T>): ByteArray = sealer.seal(json.encodeToString(serializer, value))

    private suspend fun <T> open(bytes: ByteArray, serializer: KSerializer<T>): T? =
        sealer.open(bytes)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    // Off the main thread: sealing and JSON for a hundred rows is real work.
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        withContext(work) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private val String?.key: String get() = this ?: ""

    companion object {
        /** Chats whose rows are kept; reading another drops the one read longest ago. */
        const val MAX_TRANSCRIPTS = 50

        /** Rows kept per chat: a few pages, enough to read back over the last turns. */
        const val MAX_TRANSCRIPT_ROWS = 200

        /** Rows kept per list: what one refresh of the list reads at most. */
        const val MAX_LIST_ROWS = 100

        /** Android reads a database row through a 2 MB window; a sealed row over this isn't saved. */
        const val MAX_ROW_BYTES = 1_000_000

        /** What a row too big to keep shows instead of its text. */
        const val TOO_BIG_NOTE = "(Too long to keep on this device. Hermes has the full text.)"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
