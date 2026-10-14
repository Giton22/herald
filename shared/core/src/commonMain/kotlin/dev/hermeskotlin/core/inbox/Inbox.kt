package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.cron.Routines
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.CoroutineContext

/**
 * One finished run of a cron job in the Inbox, as the herald-push plugin reads it from the gateway's cron
 * output files (`cron inbox`, `cron read`, or a `cron_run` push). [text] is the start of its output in a list,
 * the whole of it once read.
 */
@Serializable
data class InboxRun(
    /** The profile whose cron store holds the job. */
    val bot: String = "default",
    val job: String,
    /** The output file's name, `YYYY-MM-DD_HH-MM-SS`: with [bot] and [job], what names the run. */
    val run: String,
    /** The job's name. */
    val label: String = "",
    /** `ok` or `failed`. */
    val status: String = OK,
    /** When the run finished, unix seconds. */
    val at: Long = 0,
    val text: String = "",
) {
    val key: String get() = "$bot/$job/$run"
    val failed: Boolean get() = status == FAILED
    /** The job's name as the gateway has it, without a Bot Mode routine's `[bot:…]` tag. */
    val title: String get() = Routines.title(label).trim().ifBlank { job }

    companion object {
        const val OK = "ok"
        const val FAILED = "failed"

        /** The run a `cron_run` push names, or null when the push is missing what names it. */
        fun from(message: PushMessage): InboxRun? {
            val job = message.job?.takeIf { JOB_ID.matches(it) } ?: return null
            val run = message.run?.takeIf { RUN_ID.matches(it) } ?: return null
            val bot = message.bot?.takeIf { PROFILE.matches(it) } ?: "default"
            return InboxRun(
                bot = bot,
                job = job,
                run = run,
                label = message.label?.filter { !it.isISOControl() }?.trim()?.take(MAX_LABEL).orEmpty(),
                status = if (message.status == FAILED) FAILED else OK,
                at = message.at ?: message.ts,
                text = message.text.orEmpty(),
            )
        }

        private const val MAX_LABEL = 64
        private val JOB_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val PROFILE = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val RUN_ID = Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}$")
    }
}

/**
 * A report written for a chat app (Telegram keeps every line break) as Markdown that keeps them too: a single
 * line break would otherwise join two lines into one paragraph, so "**Event** · date" and "🎟 0 tickets" run
 * together. Each line that a non-blank line follows gets a hard break, except in code fences and table rows,
 * where Markdown keeps lines apart already.
 */
fun keepLineBreaks(markdown: String): String {
    val lines = markdown.lines()
    val out = StringBuilder(markdown.length + lines.size * 2)
    var fence: String? = null
    lines.forEachIndexed { i, line ->
        val trimmed = line.trimStart()
        val opensFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
        val inFence = fence != null
        if (opensFence) fence = if (inFence && trimmed.startsWith(fence!!)) null else fence ?: trimmed.take(3)
        out.append(line)
        if (i < lines.lastIndex) {
            val next = lines[i + 1]
            val breakHere = !inFence && !opensFence && line.isNotBlank() && next.isNotBlank() &&
                !trimmed.startsWith("|") && !next.trimStart().startsWith("|") && !line.endsWith("  ") && !line.endsWith("\\")
            if (breakHere) out.append("  ")
            out.append('\n')
        }
    }
    return out.toString()
}

/** How one job reaches the Inbox: not at all, into it quietly, or with a notification. */
enum class Follow(val wire: String) {
    Off("off"),
    Quiet("quiet"),
    Notify("notify");

    companion object {
        fun of(wire: String?): Follow = entries.firstOrNull { it.wire == wire } ?: Off
    }
}

/**
 * What this phone follows on the gateway: [default] for every job (new ones too), [jobs] where a job differs
 * from it, and whether any failed run notifies.
 */
data class Following(val jobs: Map<String, Follow> = emptyMap(), val failures: Boolean = false, val default: Follow = Follow.Off) {
    fun of(jobId: String): Follow = jobs[jobId] ?: default

    /** Whether [run] belongs in the Inbox: its job is followed, or it failed and failures are asked for. */
    fun covers(run: InboxRun): Boolean = of(run.job) != Follow.Off || (failures && run.failed)
    val any: Boolean get() = failures || default != Follow.Off || jobs.values.any { it != Follow.Off }

    fun with(jobId: String, follow: Follow): Following =
        copy(jobs = if (follow == default) jobs - jobId else jobs + (jobId to follow))

    /** Every job set to [follow], new ones too: what was set per job is dropped. */
    fun all(follow: Follow): Following = copy(jobs = emptyMap(), default = follow)

    /**
     * Whether [saved], what the gateway answered a save of this with, says the same: a plugin that ignores part
     * of a save (one before 0.4.1 drops `default`) answers with less than it was sent.
     */
    fun echoedBy(saved: Following): Boolean =
        saved.default == default && saved.failures == failures && jobs.all { (job, follow) -> saved.of(job) == follow }
}

@Serializable
private data class FollowReply(
    val ok: Boolean = false,
    val error: String? = null,
    val jobs: Map<String, String> = emptyMap(),
    val failures: Boolean = false,
    /** Absent from a plugin before 0.4.1, which has no default: every job not listed is off. */
    val default: String? = null,
)

@Serializable
private data class InboxReply(val ok: Boolean = false, val error: String? = null, val runs: List<InboxRun> = emptyList())

@Serializable
private data class ReadReply(val ok: Boolean = false, val error: String? = null, val run: InboxRun? = null)

/**
 * The gateway's herald-push plugin is older than an Inbox call needs: [needs] is the oldest version that has it,
 * [InboxApi.MIN_PLUGIN_VERSION] for the Inbox itself, [InboxApi.ALL_JOBS_PLUGIN_VERSION] for All on and jobs
 * switched off.
 */
class InboxPluginTooOld(val needs: String) : Exception(
    if (needs == InboxApi.MIN_PLUGIN_VERSION) InboxApi.TOO_OLD else "The gateway's herald-push plugin needs $needs for that.",
)

/**
 * The Inbox's side of the herald-push plugin (`/herald-push cron …`, from 0.4.0), over the dashboard socket. This
 * phone is named by its push registration's device id, so the Inbox needs Notifications anywhere on.
 */
class InboxApi(private val push: PushApi, private val keys: PushKeys) {

    suspend fun following(): Following = follow("following") {}

    /**
     * Saves what this phone follows and gives back what the gateway now has. A plugin that answers with less
     * than it was sent (one before 0.4.1 ignores `default`, and refuses jobs set to off) is [InboxPluginTooOld].
     */
    suspend fun setFollowing(following: Following): Following {
        val saved = runCatching {
            follow("follow") {
                putJsonObject("jobs") { following.jobs.forEach { (job, mode) -> put(job, mode.wire) } }
                put("failures", following.failures)
                // A plugin before 0.4.1 refuses "off" and a default: say them only when they matter.
                if (following.default != Follow.Off) put("default", following.default.wire)
            }
        }.getOrElse { e ->
            // 0.4.0's refusal of a job set to off.
            if (e is RpcException && e.message.startsWith(REFUSES_OFF)) throw InboxPluginTooOld(ALL_JOBS_PLUGIN_VERSION)
            throw e
        }
        if (!following.echoedBy(saved)) throw InboxPluginTooOld(ALL_JOBS_PLUGIN_VERSION)
        return saved
    }

    /**
     * The runs this phone follows, newest first, at most [limit]; [before] (unix seconds) pages back to the runs
     * that finished before it. Plugin 0.4.2 ends a page with every run of its last second, so asking again before
     * the oldest run's time leaves none out; an older plugin may cut that second short.
     */
    suspend fun runs(limit: Int = PAGE, before: Long = 0): List<InboxRun> {
        val body = request {
            put("limit", limit)
            if (before > 0) put("before", before)
        }
        val reply = decode(InboxReply.serializer(), push.cron("inbox $body")) { it.ok to it.error }
        if (!reply.ok) throw RpcException(0, reply.error ?: "The gateway couldn't list the runs.")
        return reply.runs
    }

    /** One run in full, or null when the gateway no longer has it. */
    suspend fun read(run: InboxRun): InboxRun? {
        val body = request {
            put("bot", run.bot)
            put("job", run.job)
            put("run", run.run)
        }
        val reply = decode(ReadReply.serializer(), push.cron("read $body")) { it.ok to it.error }
        if (reply.ok) return reply.run
        if (reply.error == NO_SUCH_RUN) return null
        throw RpcException(0, reply.error ?: "The gateway couldn't read the run.")
    }

    private suspend fun follow(op: String, more: JsonObjectBuilder.() -> Unit): Following {
        val reply = decode(FollowReply.serializer(), push.cron("$op ${request(more)}")) { it.ok to it.error }
        if (!reply.ok) throw RpcException(0, reply.error ?: "The gateway refused.")
        val default = Follow.of(reply.default ?: Follow.Off.wire)
        return Following(reply.jobs.mapValues { Follow.of(it.value) }.filterValues { it != default }, reply.failures, default)
    }

    private fun request(more: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("device_id", keys.deviceId)
        more()
    }

    private fun <T> decode(serializer: KSerializer<T>, output: String?, status: (T) -> Pair<Boolean, String?>): T {
        output ?: throw RpcException(0, "The gateway's herald-push plugin isn't there.")
        val reply = runCatching { HermesJson.decodeFromString(serializer, output) }
            .getOrElse { throw RpcException(0, "The gateway's herald-push plugin gave an answer the Inbox can't read.") }
        val (ok, error) = status(reply)
        if (!ok && isUsage(error)) throw InboxPluginTooOld(MIN_PLUGIN_VERSION)
        return reply
    }

    companion object {
        /** The oldest plugin with `/herald-push cron`. */
        const val MIN_PLUGIN_VERSION = "0.4.0"

        /** The oldest plugin that follows every job by default and switches single jobs off. */
        const val ALL_JOBS_PLUGIN_VERSION = "0.4.1"

        /** What a call says when the plugin predates the Inbox; the Inbox page shows its update notice for it. */
        const val TOO_OLD = "The gateway's herald-push plugin is too old for the Inbox."

        /** The most runs the plugin lists at once. */
        const val PAGE = 100

        private const val NO_SUCH_RUN = "no such run"
        private const val REFUSES_OFF = "mode must be notify or quiet"

        /**
         * Whether a refusal is the plugin's usage line: what it answers a `cron` call it doesn't know, so one
         * from before the Inbox (0.4.0 has `follow` and `following` only, an older one no `cron` at all).
         */
        fun isUsage(error: String?): Boolean = error?.trimStart()?.startsWith("usage:") == true
    }
}

/**
 * The Inbox kept on the phone, newest first: what the gateway listed last, plus runs that came by push while it
 * couldn't be reached, and which have been read. Kept per gateway, so it shows before (or without) a connection.
 *
 * Every change names the gateway it is for: one that comes in after the store moved to another gateway (a
 * list read, or a push, that outlived the switch) is dropped rather than written into the other's Inbox.
 */
class InboxStore(private val store: KeyValueStore, private val work: CoroutineContext = Dispatchers.Default) {

    @Serializable
    private data class Saved(
        val runs: List<InboxRun> = emptyList(),
        val read: Set<String> = emptySet(),
        /** Runs read before this (unix seconds) count as read: the Inbox's first look isn't a pile of unread. */
        @SerialName("since") val since: Long = 0,
        val groups: InboxGroups = InboxGroups(),
    )

    data class Snapshot(
        val runs: List<InboxRun> = emptyList(),
        val read: Set<String> = emptySet(),
        val since: Long = 0,
        val groups: InboxGroups = InboxGroups(),
    ) {
        fun isUnread(run: InboxRun): Boolean = run.key !in read && run.at > since
        val unread: Int get() = runs.count(::isUnread)
    }

    private val lock = Mutex()
    private var gateway: String? = null
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** The gateway whose Inbox this is now, or null before the first [bind]. */
    val bound: String? get() = gateway

    /** Loads [gatewayUrl]'s Inbox, starting it now (everything before counts as read) the first time. */
    suspend fun bind(gatewayUrl: String, now: Long = getTimeMillis() / 1000) = lock.withLock {
        if (gateway == gatewayUrl) return@withLock
        val saved = loadOrStart(gatewayUrl, now)
        // [bound] moves with [state], not before it: until the new Inbox is read, the last one is still shown.
        gateway = gatewayUrl
        _state.value = Snapshot(saved.runs, saved.read, saved.since, saved.groups)
    }

    /**
     * A run that came by push for [gatewayUrl]: into its Inbox, without moving the store there. The app may have
     * switched to another gateway since the push was signed, and that one's Inbox stays the one shown. An Inbox
     * started by this run starts just before it, so the run counts as new. Gives back that Inbox's groups, for the
     * name the user gave the job.
     */
    suspend fun receive(gatewayUrl: String, run: InboxRun): InboxGroups = lock.withLock {
        if (gateway == gatewayUrl) {
            val next = _state.value.let { s -> s.copy(runs = merge(listOf(run) + s.runs.filter { it.key != run.key })) }
            _state.value = next
            save(gatewayUrl, Saved(next.runs, next.read, next.since, next.groups))
            next.groups
        } else {
            val saved = loadOrStart(gatewayUrl, run.at - 1)
            save(gatewayUrl, saved.copy(runs = merge(listOf(run) + saved.runs.filter { it.key != run.key })))
            saved.groups
        }
    }

    private suspend fun loadOrStart(gatewayUrl: String, now: Long): Saved =
        load(gatewayUrl) ?: Saved(since = now).also { save(gatewayUrl, it) }

    /**
     * What the gateway listed, newest first: it replaces the kept runs, by [mergeListed]'s rules. [complete] is
     * whether the list holds every run the gateway has for this phone (it came back shorter than asked for).
     */
    suspend fun replace(gatewayUrl: String, runs: List<InboxRun>, complete: Boolean) = edit(gatewayUrl) { s ->
        s.copy(runs = mergeListed(s.runs, runs, complete).take(MAX_KEPT))
    }

    /** Drops the kept runs [keep] says no to: what a phone stopped following no longer shows. */
    suspend fun prune(gatewayUrl: String, keep: (InboxRun) -> Boolean) = edit(gatewayUrl) { s ->
        s.copy(runs = s.runs.filter(keep))
    }

    /** A run that came by push, or was read in full: added, or put in place of its shorter copy. */
    suspend fun add(gatewayUrl: String, run: InboxRun) = edit(gatewayUrl) { s ->
        s.copy(runs = merge(listOf(run) + s.runs.filter { it.key != run.key }))
    }

    suspend fun markRead(gatewayUrl: String, run: InboxRun) = edit(gatewayUrl) { s -> s.copy(read = trimRead(s.read + run.key)) }

    /** Every run kept, and [also] (runs paged in from further back), counts as read. */
    suspend fun markAllRead(gatewayUrl: String, also: List<InboxRun> = emptyList()) = edit(gatewayUrl) { s ->
        s.copy(read = trimRead(s.read + (s.runs + also).map { it.key }))
    }

    /** Changes the user's groups (kept on this phone, per gateway). */
    suspend fun editGroups(gatewayUrl: String, change: (InboxGroups) -> InboxGroups) = edit(gatewayUrl) { s -> s.copy(groups = change(s.groups)) }

    /** Forgets [gatewayUrl]'s Inbox, when the gateway itself is forgotten. */
    suspend fun forget(gatewayUrl: String) = lock.withLock {
        store.remove(key(gatewayUrl))
        if (gateway == gatewayUrl) {
            gateway = null
            _state.value = Snapshot()
        }
    }

    private suspend fun edit(url: String, change: (Snapshot) -> Snapshot) = lock.withLock {
        if (gateway != url) return@withLock
        val next = change(_state.value)
        _state.value = next
        save(url, Saved(next.runs, next.read, next.since, next.groups))
    }

    private fun merge(runs: List<InboxRun>): List<InboxRun> =
        runs.distinctBy { it.key }.sortedByDescending { it.at }.take(MAX_KEPT)

    /**
     * What was saved for [url], or null when nothing was. A part that no longer reads (an older or damaged
     * copy) starts empty on its own, so a bad run list doesn't take the user's groups and names with it.
     */
    private suspend fun load(url: String): Saved? {
        val raw = store.get(key(url)) ?: return null
        return withContext(work) { decodeSaved(raw) }
    }

    private suspend fun save(url: String, saved: Saved) {
        // A kept run holds the start of its text only: the whole of a long briefing is read from the gateway.
        val trimmed = saved.copy(runs = saved.runs.map { it.copy(text = it.text.take(MAX_KEPT_TEXT)) })
        val encoded = withContext(work) { HermesJson.encodeToString(Saved.serializer(), trimmed) }
        store.put(key(url), encoded)
    }

    private fun decodeSaved(raw: String): Saved {
        runCatching { return HermesJson.decodeFromString(Saved.serializer(), raw) }
        val obj = runCatching { HermesJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return Saved()
        fun <T> part(name: String, serializer: KSerializer<T>, empty: T): T =
            obj[name]?.let { runCatching { HermesJson.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: empty
        return Saved(
            runs = part("runs", ListSerializer(InboxRun.serializer()), emptyList()),
            read = part("read", SetSerializer(String.serializer()), emptySet()),
            since = part("since", Long.serializer(), 0L),
            groups = part("groups", InboxGroups.serializer(), InboxGroups()),
        )
    }

    private fun key(url: String) = keyFor(url)

    companion object {
        const val MAX_KEPT = 100
        const val MAX_KEPT_TEXT = 2_000

        /** The most runs remembered as read: past it, the oldest go first. */
        const val MAX_READ = 1_000

        /** Where [gatewayUrl]'s Inbox is kept. */
        fun keyFor(gatewayUrl: String) = "inbox.v1.$gatewayUrl"

        /**
         * The read runs to remember: all of them up to [MAX_READ], else the newest by their run's time. A run is
         * not forgotten just because it left the kept list: it may come back in a later list, or a page further
         * back, and must not show as unread again.
         */
        fun trimRead(read: Set<String>): Set<String> =
            if (read.size <= MAX_READ) read else read.sortedByDescending { it.substringAfterLast('/') }.take(MAX_READ).toSet()

        /**
         * The kept runs once the gateway listed [listed] (newest first): every listed run, with the longer text of
         * it where a push or a read in full brought more than the list's start; and the kept runs it didn't list
         * that it may not have meant to drop: newer than its newest (the gateway can lag a push), or older than its
         * oldest when the list stopped short ([complete] false: more runs were there than it was asked for). Any
         * other kept run is one the gateway no longer has for this phone, so it goes, and a complete empty list
         * leaves none.
         */
        fun mergeListed(kept: List<InboxRun>, listed: List<InboxRun>, complete: Boolean): List<InboxRun> {
            val keptByKey = kept.associateBy { it.key }
            val fresh = listed.distinctBy { it.key }.map { run ->
                val old = keptByKey[run.key]
                if (old != null && old.text.length > run.text.length) run.copy(text = old.text) else run
            }
            val listedKeys = fresh.map { it.key }.toSet()
            val newest = fresh.maxOfOrNull { it.at }
            val oldest = fresh.minOfOrNull { it.at }
            val unlisted = kept.filter { run ->
                run.key !in listedKeys && when {
                    newest == null -> false
                    run.at > newest -> true
                    !complete && oldest != null && run.at < oldest -> true
                    else -> false
                }
            }
            return (fresh + unlisted).sortedByDescending { it.at }
        }
    }
}

/** How the Inbox's chips split the runs: a chip per job, or per group the user made. */
@Serializable
enum class ChipMode { Jobs, Groups }

/** How the Inbox opens: a folder per job (or per group), or every report in one list. */
@Serializable
enum class InboxLayout { Folders, List }

/**
 * The user's own groups of jobs for the Inbox, such as "RUBIX" and "Personal": [names] in the order made, and
 * [jobs] mapping a job ([jobKey]) to its group. A job is in one group at most; one in none shows under "Other".
 * Kept on the phone: they only sort what the phone shows.
 */
@Serializable
data class InboxGroups(
    val names: List<String> = emptyList(),
    val jobs: Map<String, String> = emptyMap(),
    val mode: ChipMode = ChipMode.Jobs,
    /** Names the user gave jobs on this phone ([jobKey] to name), shown instead of the job's own. */
    val renamed: Map<String, String> = emptyMap(),
    val layout: InboxLayout = InboxLayout.Folders,
) {
    fun of(bot: String, job: String): String? = jobs[jobKey(bot, job)]?.takeIf { it in names }

    /** What [run]'s job is called here: the name the user gave it, else its own. */
    fun nameOf(run: InboxRun): String = renamed[jobKey(run.bot, run.job)] ?: run.title

    /** The name the user gave a job, or null when it goes by its own. */
    fun renamedOf(bot: String, job: String): String? = renamed[jobKey(bot, job)]

    /**
     * Calls a job [name] on this phone (trimmed, at most [MAX_NAME]); blank, or the job's own name [own], puts its
     * own name back.
     */
    fun renameJob(bot: String, job: String, name: String, own: String): InboxGroups {
        val clean = cleanName(name)
        val key = jobKey(bot, job)
        return if (clean.isEmpty() || clean == own) copy(renamed = renamed - key) else copy(renamed = renamed + (key to clean))
    }

    /**
     * Why [name] can't be a group's name (taken, or [reserved]), else null; [renaming] is the group being renamed,
     * whose own name is free. The name is judged as [cleanName] would keep it.
     */
    fun refusal(name: String, renaming: String? = null): NameRefusal? {
        val clean = cleanName(name)
        return when {
            clean.isEmpty() -> NameRefusal.Blank
            reserved(clean) -> NameRefusal.Reserved
            names.any { it != renaming && it.equals(clean, ignoreCase = true) } -> NameRefusal.Taken
            renaming == null && names.size >= MAX_GROUPS -> NameRefusal.TooMany
            else -> null
        }
    }

    /** Adds a group called [name] (trimmed, at most [MAX_NAME]); a blank, taken or [reserved] name changes nothing. */
    fun add(name: String): InboxGroups {
        if (refusal(name) != null) return this
        return copy(names = names + cleanName(name))
    }

    /** Renames [from] to [to], keeping its jobs; a blank, taken or [reserved] name changes nothing. */
    fun rename(from: String, to: String): InboxGroups {
        val clean = cleanName(to)
        if (from !in names || refusal(to, renaming = from) != null) return this
        return copy(names = names.map { if (it == from) clean else it }, jobs = jobs.mapValues { if (it.value == from) clean else it.value })
    }

    /** Removes the group [name]; its jobs go back to none. */
    fun remove(name: String): InboxGroups = copy(names = names - name, jobs = jobs.filterValues { it != name })

    /** Puts a job in [group], or in none when it is null. */
    fun assign(bot: String, job: String, group: String?): InboxGroups =
        if (group == null || group !in names) copy(jobs = jobs - jobKey(bot, job)) else copy(jobs = jobs + (jobKey(bot, job) to group))

    /** Why a group name was turned down. */
    enum class NameRefusal { Blank, Taken, Reserved, TooMany }

    companion object {
        const val MAX_NAME = 40
        const val MAX_GROUPS = 20

        /** What the Inbox calls the jobs in no group: no group can be named so, or the two would be one. */
        const val OTHER = "Other"

        fun jobKey(bot: String, job: String) = "$bot/$job"

        /** A group or job name as kept: trimmed, at most [MAX_NAME] characters. */
        fun cleanName(name: String): String = name.trim().take(MAX_NAME).trim()

        /** Names a group can't take: the Other folder's, and `null`, which reads as no group at all. */
        fun reserved(name: String): Boolean = RESERVED.any { it.equals(cleanName(name), ignoreCase = true) }

        private val RESERVED = listOf(OTHER, "null")
    }
}

/**
 * A tapped Inbox notification, waiting for the app to show it: the sidebar opens on the Inbox, at [key]'s run
 * when it is one ([InboxRun.key]), at the list when it is null.
 */
data class InboxLink(val key: String?)

class InboxLinks {
    private val _pending = MutableStateFlow<InboxLink?>(null)
    val pending: StateFlow<InboxLink?> = _pending.asStateFlow()

    fun open(key: String?) {
        _pending.value = InboxLink(key)
    }

    /** Takes [link] once it's shown, unless a newer one came meanwhile. */
    fun consume(link: InboxLink) = _pending.update { if (it == link) null else it }
}
