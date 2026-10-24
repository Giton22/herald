package dev.hermeskotlin.core.threads

import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** A thread action that didn't go through; [message] says why, for the user. */
class ThreadActionException(message: String) : Exception(message)

/** The chat a thread was started fresh into: open [sessionId], titled [title]. */
data class FreshChat(val sessionId: String, val title: String)

/**
 * The threads of the gateway and profile the chat list shows, shared by the sidebar and the open chat so both
 * see the same ones. The list lives in the profile's `ui_meta` ([ThreadsApi]); this keeps the last copy read,
 * and the chat rows last listed, to tell which row is a thread's chat after compression gave it a new id.
 */
class ThreadsRepository(
    private val api: ThreadsApi,
    private val sessions: SessionsApi,
    private val profiles: ProfilesApi,
    /** Where the next launch reopens; a thread started fresh moves it from the filed-away chat to the new one. */
    private val lastChats: LastChatStore? = null,
    /** What a thread's new chat tells the agent first, out of sight (the app's starting instruction). */
    private val startInstruction: () -> String? = { null },
    /** The local date and time an archived thread chat is named with: `yyyy-MM-dd HH:mm`, or with seconds. */
    private val stamp: (withSeconds: Boolean) -> String = { localStamp(Clock.System.now().toEpochMilliseconds(), it) },
) {

    /** The gateway and profile (null: the gateway's launch profile) the threads are for. */
    data class Scope(val url: GatewayUrl, val profile: String?)

    private val _scope = MutableStateFlow<Scope?>(null)
    val scope: StateFlow<Scope?> = _scope.asStateFlow()

    private val _threads = MutableStateFlow<List<ChatThread>>(emptyList())

    /** The threads in their order; empty until read, or on a gateway that can't keep them. */
    val threads: StateFlow<List<ChatThread>> = _threads.asStateFlow()

    private val _rows = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** The chat rows last listed, which name each thread's chat as it is now. */
    val rows: StateFlow<List<SessionSummary>> = _rows.asStateFlow()

    /** The profile's own name (`default` for the default one), which its `ui_meta` is written under. */
    private var profileName: String? = null
    private var readAt = 0L
    private val writes = Mutex()

    /** The threads being started fresh right now. */
    private val starting = mutableSetOf<String>()

    /** Shows [profile]'s threads on [url] from now on; another scope's are dropped at once. */
    fun bind(url: GatewayUrl, profile: String?) {
        val next = Scope(url, profile)
        if (_scope.value == next) return
        _scope.value = next
        profileName = profile
        readAt = 0
        _threads.value = emptyList()
        _rows.value = emptyList()
    }

    /**
     * The chat rows as the list of [profile] on [url] now has them. A list read for another scope, finishing after
     * a switch, is ignored.
     */
    fun noteRows(url: GatewayUrl, profile: String?, sessions: List<SessionSummary>) {
        if (_scope.value == Scope(url, profile)) _rows.value = sessions
    }

    /**
     * Reads the threads again; at most every [READ_EVERY_MS] unless [force]. A failed read keeps what shows. Read
     * under the write lock, so a read that began before a save can't put the list from before it back.
     */
    suspend fun refresh(force: Boolean = false) {
        val scope = _scope.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        if (!force && now - readAt < READ_EVERY_MS) return
        readAt = now
        writes.withLock {
            val read = try {
                api.threads(name(scope))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            if (_scope.value == scope) _threads.value = read
        }
    }

    /** [thread]'s chat as last listed: the row it names, or the newest row of that chat's lineage. */
    fun chatOf(thread: ChatThread): SessionSummary? = chatOf(thread, _rows.value)

    /** The thread whose chat is [storedId], by the id it keeps or the chat's id now. */
    fun threadFor(storedId: String?): ChatThread? = threadFor(storedId, _threads.value, _rows.value)

    /** Makes [session] a thread, named after it, at the end of the list; pins it so the gateway keeps it. */
    suspend fun makeThread(session: SessionSummary) {
        val scope = requireScope()
        change { it.withThread(ChatThread(newThreadId(), session.displayTitle, session.id, session.cwd)) }
        if (!session.pinned) sessions.setPinned(scope.url, session.id, true, scope.profile).orThrow("Made a thread, but couldn't pin its chat.")
    }

    /** Applies [edit] to the threads and saves them; another device's edit made meanwhile is kept. */
    suspend fun change(edit: (List<ChatThread>) -> List<ChatThread>) {
        val scope = requireScope()
        val saved = writes.withLock { api.change(name(scope), edit) }
        if (_scope.value == scope) _threads.value = saved
    }

    /**
     * Names [thread] [name], and its chat the same. The chat is titled first, since titles are unique and that is
     * the step that can be refused; a refusal leaves the thread as it was. If saving the thread then fails, the
     * chat gets its old title back. A blank [name] changes nothing: it would clear the chat's title.
     */
    suspend fun rename(thread: ChatThread, name: String) {
        if (name.isBlank()) return
        val scope = requireScope()
        val current = chatOf(thread)
        val liveId = current?.id ?: thread.sessionId
        val oldTitle = current?.title?.takeIf { it.isNotBlank() }
        if (current?.title != name) {
            val result = sessions.update(scope.url, liveId, title = name, profile = scope.profile)
            if (result.isTitleTaken()) throw ThreadActionException("Another chat is already named \"$name\".")
            result.orThrow("Couldn't rename ${thread.name}'s chat.")
        }
        try {
            change { it.renamed(thread.id, name) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            oldTitle?.let { sessions.update(scope.url, liveId, title = it, profile = scope.profile) }
            throw e
        }
    }

    /**
     * Starts [thread] fresh, in an order that never loses the thread's chat: the chat it has now is renamed
     * "<name> · <date time>" (titles are unique, and an archived chat keeps its title), archived and unpinned in one
     * call; a new chat takes the thread's name and is pinned; then the thread is pointed at it. If any later step
     * fails, the new chat is deleted and the old one put back as it was, so the thread never points at a chat in
     * the archive; when even that fails, the message says where the old chat is.
     */
    suspend fun startFresh(thread: ChatThread): FreshChat {
        val scope = requireScope()
        // A second tap while the first is under way would make a second new chat.
        if (!starting.add(thread.id)) throw ThreadActionException("${thread.name} is already starting fresh.")
        try {
            return startFresh(scope, thread)
        } finally {
            starting.remove(thread.id)
        }
    }

    private suspend fun startFresh(scope: Scope, thread: ChatThread): FreshChat {
        val current = chatOf(thread)
        val liveId = current?.id ?: thread.sessionId
        val oldTitle = current?.title?.takeIf { it.isNotBlank() } ?: thread.name
        // An earlier id of a compressed chat may hold the pin; it lets go of it too.
        val earlier = thread.sessionId.takeIf { it != liveId }
        val archivedAs = fileAway(scope, liveId, thread.name)
        var made: Made? = null
        val fresh = try {
            earlier?.let { sessions.update(scope.url, it, archived = true, pinned = false, profile = scope.profile) }
            val chat = newChat(scope, thread).also { made = it }.chat
            // Pinned, the gateway never prunes it.
            sessions.update(scope.url, chat.sessionId, pinned = true, profile = scope.profile).orThrow("Couldn't pin the new chat.")
            change { it.pointedAt(thread.id, chat.sessionId) }
            chat
        } catch (e: Exception) {
            val landed = made?.takeIf { e !is CancellationException && pointedAtAlready(scope, thread, it.chat) }
            if (landed == null) {
                // Undone even when cancelled half way, so nothing is left filed away with no thread to hold it.
                val restored = withContext(NonCancellable) { undo(scope, made, liveId, oldTitle, earlier) }
                if (e is CancellationException) throw e
                val where = if (restored) "" else " Its chat is in the archive as \"$archivedAs\"."
                throw ThreadActionException("Couldn't start ${thread.name} fresh. ${e.message.orEmpty()}".trim() + where)
            }
            landed.chat
        }
        // An empty chat isn't remembered until it has a message, so the launch would reopen the archived one.
        lastChats?.let { store ->
            val last = store.get(scope.url, scope.profile)?.sessionId
            if (last == liveId || last == thread.sessionId) store.set(scope.url, LastChat(fresh.sessionId, fresh.title), scope.profile)
        }
        return fresh
    }

    /**
     * Whether the thread already points at [chat] though saving it seemed to fail: the write landed and its reply
     * was lost. Then the start went through and nothing is undone.
     */
    private suspend fun pointedAtAlready(scope: Scope, thread: ChatThread, chat: FreshChat): Boolean {
        val read = try {
            api.threads(name(scope))
        } catch (_: Exception) {
            return false
        }
        if (read.none { it.id == thread.id && it.sessionId == chat.sessionId }) return false
        if (_scope.value == scope) _threads.value = read
        return true
    }

    /**
     * Deletes the new chat [made], letting go of its runtime first, and puts the chat [liveId] back as it was.
     * The new chat goes first: it may hold the title the old one gets back. Answers whether the old one is back.
     */
    private suspend fun undo(scope: Scope, made: Made?, liveId: String, oldTitle: String, earlier: String?): Boolean {
        made?.let {
            api.close(it.runtime)
            sessions.delete(scope.url, it.chat.sessionId, scope.profile)
        }
        val restored = sessions.update(scope.url, liveId, title = oldTitle, archived = false, pinned = true, profile = scope.profile) is ApiResult.Success
        earlier?.let { sessions.update(scope.url, it, archived = false, pinned = true, profile = scope.profile) }
        return restored
    }

    /** A new chat made for a thread: what opens, and the runtime it was made on. */
    private class Made(val chat: FreshChat, val runtime: String)

    /** Renames, archives and unpins the chat [id] in one call, its new title stamped with the time. Answers that title. */
    private suspend fun fileAway(scope: Scope, id: String, name: String): String {
        var title = "$name · ${stamp(false)}"
        var result = sessions.update(scope.url, id, title = title, archived = true, pinned = false, profile = scope.profile)
        if (result.isTitleTaken()) {
            title = "$name · ${stamp(true)}"
            result = sessions.update(scope.url, id, title = title, archived = true, pinned = false, profile = scope.profile)
        }
        result.orThrow("Couldn't file away ${name}'s chat.")
        return title
    }

    /** A new chat named after [thread], or "<name> (2)" and on while another chat has the name. */
    private suspend fun newChat(scope: Scope, thread: ChatThread): Made {
        val titles = listOf(thread.name) + (2..MAX_SUFFIX).map { "${thread.name} ($it)" }
        titles.forEach { title ->
            try {
                val (runtime, stored) = api.startChat(scope.profile, title, thread.cwd, startInstruction())
                return Made(FreshChat(stored, title), runtime)
            } catch (_: ThreadTitleTakenException) {
                // Try the next name.
            }
        }
        throw ThreadActionException("Every name for the new chat is taken.")
    }

    private suspend fun name(scope: Scope): String {
        profileName?.let { return it }
        val roster = profiles.roster(scope.url)
        val launch = (roster as? ApiResult.Success)?.value?.launch
            ?: throw ThreadActionException("Couldn't tell which profile the gateway runs. ${roster.errorMessage.orEmpty()}".trim())
        if (_scope.value == scope) profileName = launch
        return launch
    }

    private fun requireScope(): Scope = _scope.value ?: throw ThreadActionException("Not connected to a gateway.")

    companion object {
        /** How often a list refresh may read the threads again; another device's change shows within this. */
        const val READ_EVERY_MS = 10_000L

        /** The highest number tried after a taken name. */
        private const val MAX_SUFFIX = 9

        /** [thread]'s chat among [rows]. */
        fun chatOf(thread: ChatThread, rows: List<SessionSummary>): SessionSummary? = rows.firstOrNull { thread.holds(it) }

        /** The thread among [threads] whose chat is [storedId], with [rows] naming each thread's chat now. */
        fun threadFor(storedId: String?, threads: List<ChatThread>, rows: List<SessionSummary>): ChatThread? {
            storedId ?: return null
            return threads.firstOrNull { it.sessionId == storedId } ?: threads.firstOrNull { thread ->
                chatOf(thread, rows)?.isChat(storedId) == true
            }
        }
    }
}

private fun ApiResult<*>.isTitleTaken(): Boolean =
    this is ApiResult.Failed && message.contains("already in use", ignoreCase = true)

private fun ApiResult<*>.orThrow(what: String) {
    if (this !is ApiResult.Success) throw ThreadActionException("$what ${errorMessage.orEmpty()}".trim())
}

/** [epochMillis] as the device's local `yyyy-MM-dd HH:mm`, with `:ss` when [withSeconds]. */
internal expect fun localStamp(epochMillis: Long, withSeconds: Boolean): String
