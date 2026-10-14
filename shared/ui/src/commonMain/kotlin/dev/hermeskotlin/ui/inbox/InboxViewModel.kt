package dev.hermeskotlin.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.core.inbox.Follow
import dev.hermeskotlin.core.inbox.Following
import dev.hermeskotlin.core.inbox.InboxApi
import dev.hermeskotlin.core.inbox.InboxGroups
import dev.hermeskotlin.core.inbox.InboxLayout
import dev.hermeskotlin.core.inbox.InboxPluginTooOld
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.core.inbox.InboxStore
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.push.PushStatus
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What the Inbox list shows: every run, only the failed ones, one job's, or one of the user's groups. */
sealed interface InboxFilter {
    data object All : InboxFilter
    data object Failed : InboxFilter
    data class Job(val bot: String, val job: String) : InboxFilter

    /** A group the user made; [name] null is "Other", the jobs in no group. */
    data class Group(val name: String?) : InboxFilter

    /** What names this filter in a list (a folder's or a chip's key): one per filter, whatever a group is called. */
    val key: String get() = when (this) {
        All -> "all"
        Failed -> "failed"
        is Job -> "job:$bot/$job"
        is Group -> if (name == null) "other" else "group:$name"
    }

    /** This filter once the group [from] is called [to]: the group's own filter follows it. */
    fun afterRename(from: String, to: String): InboxFilter = if (this is Group && name == from) Group(to) else this
}

/**
 * Whether the chip row shows: when there is more than one chip, or a failure, to tell apart, and always while a
 * filter other than All is picked, so there is a way back from it.
 */
fun showsChips(chips: Int, failed: Int, selected: InboxFilter): Boolean = chips >= 2 || failed > 0 || selected != InboxFilter.All

/** What a save of the follow page that didn't go through says. */
fun saveErrorText(error: Throwable): String = when {
    error is InboxPluginTooOld && error.needs == InboxApi.ALL_JOBS_PLUGIN_VERSION ->
        "Couldn't save that. All on and switching jobs off need the gateway's plugin ${InboxApi.ALL_JOBS_PLUGIN_VERSION}: update it in Settings."
    error is InboxPluginTooOld -> "Couldn't save that. ${InboxApi.TOO_OLD} Update it in Settings."
    else -> error.message?.takeIf { it.isNotBlank() }?.let { "Couldn't save that: $it" } ?: "Couldn't save that."
}

/**
 * The runs a page further back adds to [have]: those not there already (a page can repeat the last second of
 * the one before), and whether the gateway may have more still (the page was full and brought something new).
 */
data class Page(val added: List<InboxRun>, val more: Boolean)

fun pageIn(have: List<InboxRun>, page: List<InboxRun>, limit: Int): Page {
    val known = have.map { it.key }.toSet()
    val added = page.distinctBy { it.key }.filter { it.key !in known }
    return Page(added, more = page.size >= limit && added.isNotEmpty())
}

/** Where the next page back starts: before the oldest run there is (plugin 0.4.2 pages strictly before it). */
fun pageCursor(runs: List<InboxRun>): Long? = runs.minOfOrNull { it.at }?.takeIf { it > 0 }

/**
 * A job's chip: what it filters to, the name it shows ([label]: the user's, else the job's own), the job's own
 * name ([own]), and how many of its runs are unread.
 */
data class JobChip(val filter: InboxFilter.Job, val label: String, val runs: Int, val unread: Int, val own: String = label) {
    val renamed: Boolean get() = label != own
}

/** A group's chip: what it filters to, its name, and how many of its runs are unread. */
data class GroupChip(val filter: InboxFilter.Group, val label: String, val runs: Int, val unread: Int)

/**
 * A chip per group the user made, in their order, then "Other" for the runs of jobs in no group. Groups with no
 * runs in the Inbox are left out, and "Other" only shows when there is a group to tell it from.
 */
fun groupChips(runs: List<InboxRun>, groups: InboxGroups, isUnread: (InboxRun) -> Boolean): List<GroupChip> {
    val byGroup = runs.groupBy { groups.of(it.bot, it.job) }
    val made = groups.names.mapNotNull { name ->
        byGroup[name]?.let { GroupChip(InboxFilter.Group(name), name, it.size, it.count(isUnread)) }
    }
    val other = byGroup[null]?.takeIf { made.isNotEmpty() }?.let { GroupChip(InboxFilter.Group(null), "Other", it.size, it.count(isUnread)) }
    return made + listOfNotNull(other)
}

/**
 * One chip per job with runs in the Inbox, the job with the newest run first, so the job that just reported is
 * at hand. Each job is named by its newest run's label, the name it goes by now.
 */
fun jobChips(runs: List<InboxRun>, groups: InboxGroups = InboxGroups(), isUnread: (InboxRun) -> Boolean): List<JobChip> =
    runs.groupBy { it.bot to it.job }
        .map { (id, ofJob) ->
            val newest = ofJob.maxBy { it.at }
            JobChip(InboxFilter.Job(id.first, id.second), groups.nameOf(newest), ofJob.size, ofJob.count(isUnread), own = newest.title)
        }
        .sortedByDescending { chip -> runs.first { it.bot == chip.filter.bot && it.job == chip.filter.job }.at }

/**
 * A folder on the Inbox's front page: a job's reports (or, split by group, a group's), its name, how many and how
 * many unread, and the newest one, whose time and opening words the folder shows.
 */
data class InboxFolder(val filter: InboxFilter, val label: String, val runs: Int, val unread: Int, val newest: InboxRun, val failed: Int)

/**
 * The Inbox's folders: per group when the chips split by group and there are groups (in the user's order, then
 * Other), else per job; either way the folder that reported last comes first, so what's new is on top.
 */
fun inboxFolders(runs: List<InboxRun>, groups: InboxGroups, isUnread: (InboxRun) -> Boolean): List<InboxFolder> {
    val byGroup = groups.mode == ChipMode.Groups && groupChips(runs, groups, isUnread).isNotEmpty()
    val folders = if (byGroup) {
        groupChips(runs, groups, isUnread).map { chip ->
            val ofGroup = runs.filtered(chip.filter, groups)
            InboxFolder(chip.filter, chip.label, chip.runs, chip.unread, ofGroup.maxBy { it.at }, ofGroup.count { it.failed })
        }
    } else {
        jobChips(runs, groups, isUnread).map { chip ->
            val ofJob = runs.filtered(chip.filter)
            InboxFolder(chip.filter, chip.label, chip.runs, chip.unread, ofJob.maxBy { it.at }, ofJob.count { it.failed })
        }
    }
    return folders.sortedByDescending { it.newest.at }
}

/** The runs [filter] keeps, in their order. */
fun List<InboxRun>.filtered(filter: InboxFilter, groups: InboxGroups = InboxGroups()): List<InboxRun> = when (filter) {
    InboxFilter.All -> this
    InboxFilter.Failed -> filter { it.failed }
    is InboxFilter.Job -> filter { it.bot == filter.bot && it.job == filter.job }
    is InboxFilter.Group -> filter { groups.of(it.bot, it.job) == filter.name }
}

/** What the Inbox's pages can ask for; [InboxViewModel] does it all, previews nothing. */
interface InboxActions {
    fun open(run: InboxRun)
    fun closeRun()
    fun markAllRead()
    fun setFilter(filter: InboxFilter)
    fun chooseJobs()
    fun closeJobs()
    fun setFollow(job: CronJob, follow: Follow)
    fun setFailures(on: Boolean)
    fun setAll(follow: Follow)
    fun openGroups()
    fun closeGroups()
    fun setLayout(layout: InboxLayout)
    fun setChipMode(mode: ChipMode)
    fun addGroup(name: String)
    fun renameGroup(from: String, to: String)
    fun removeGroup(name: String)
    fun assignGroup(job: InboxFilter.Job, group: String?)
    fun renameJob(chip: JobChip, name: String)

    /** The runs before the oldest one shown, from the gateway. */
    fun loadMore()
}

/** Why a run opened shows only the start of its text. */
enum class RunNote {
    /** Not connected: the rest is on the gateway. */
    Offline,

    /** The gateway no longer has the run. */
    Gone,

    /** The gateway was asked, and the read failed. */
    Failed,
}

/** Why the Inbox can't fetch from the gateway: it then shows what it kept, with this said once. */
enum class InboxBlock {
    /** Notifications anywhere is off: the Inbox goes by this phone's push registration. */
    PushOff,

    /** The gateway's herald-push plugin is older than the Inbox needs. */
    PluginOld,
}

data class InboxUiState(
    val runs: List<InboxRun> = emptyList(),
    val read: Set<String> = emptySet(),
    val since: Long = 0,
    val groups: InboxGroups = InboxGroups(),
    /** The groups page: make, rename and remove groups, and put jobs in them. */
    val grouping: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val block: InboxBlock? = null,
    val filter: InboxFilter = InboxFilter.All,
    /** The run open in full; null shows the list. */
    val open: InboxRun? = null,
    val openLoading: Boolean = false,
    /** Why the open run's text is only its start, or null when it is whole (or still coming). */
    val openNote: RunNote? = null,
    /** The follow page: every job with how it reaches the Inbox. */
    val choosing: Boolean = false,
    val jobs: List<CronJob> = emptyList(),
    val jobsLoading: Boolean = false,
    val following: Following = Following(),
    /**
     * [following] is what the gateway has: read from it, or saved to it. Until then (and after a save whose
     * outcome is unknown) the follow page can't change anything, since a save replaces the whole list.
     */
    val followingLoaded: Boolean = false,
    /** Why what this phone follows couldn't be read, while it can't. */
    val followingError: String? = null,
    val saving: Boolean = false,
    val message: String? = null,
    /** The gateway may have runs before the oldest one shown. */
    val more: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
) {
    /** Whether the follow page's controls take a change now. */
    val canEditFollowing: Boolean get() = followingLoaded && !saving

    fun isUnread(run: InboxRun): Boolean = run.key !in read && run.at > since
    val unread: Int get() = runs.count(::isUnread)
    val chips: List<JobChip> get() = jobChips(runs, groups, ::isUnread)
    val folders: List<InboxFolder> get() = inboxFolders(runs, groups, ::isUnread)

    /** The front page shows folders, rather than a folder or a filter opened from it. */
    val atFolders: Boolean get() = groups.layout == InboxLayout.Folders && shownFilter == InboxFilter.All

    /** The name [run]'s job shows by: the user's, else its own. */
    fun nameOf(run: InboxRun): String = groups.nameOf(run)
    val groupChips: List<GroupChip> get() = groupChips(runs, groups, ::isUnread)
    val failed: Int get() = runs.count { it.failed }

    /**
     * The filter in force: one that no longer fits (its job left the Inbox, its group was removed, or the chips
     * now split the other way) shows everything again.
     */
    val shownFilter: InboxFilter get() = filter.takeIf { f ->
        when (f) {
            InboxFilter.All -> true
            // Once the failed runs are gone (read, or dropped), the filter would show nothing to leave from.
            InboxFilter.Failed -> runs.any { it.failed }
            is InboxFilter.Job -> groups.mode == ChipMode.Jobs && runs.any { it.bot == f.bot && it.job == f.job }
            is InboxFilter.Group -> groups.mode == ChipMode.Groups && groupChips.any { it.filter == f }
        }
    } ?: InboxFilter.All
    val shown: List<InboxRun> get() = runs.filtered(shownFilter, groups)

    /** Every job with runs in the Inbox, for the groups page: its chip and the group it's in. */
    val groupableJobs: List<Pair<JobChip, String?>> get() = chips.map { it to groups.of(it.filter.bot, it.filter.job) }
}

/**
 * The Inbox: finished runs of the cron jobs this phone follows, newest first. The list is the gateway's
 * (`/herald-push cron inbox`), kept on the phone with the runs that came by push, so it reads offline too.
 * Following a job, and whether it notifies, is set per job on the gateway for this phone.
 *
 * The view model outlives a gateway switch: [bind] to another gateway starts clean, and whatever was still under
 * way for the last one (a list read, a run opened, a save) is cancelled, its answer dropped if it comes anyway.
 */
@OptIn(ExperimentalTime::class, FlowPreview::class)
class InboxViewModel(
    private val api: InboxApi,
    private val store: InboxStore,
    private val cron: CronApi,
    private val push: PushSetup,
    private val connection: GatewayConnection,
    private val settings: SettingsStore,
) : ViewModel(), InboxActions {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null

    /** Counts the gateways bound, so work started for one can tell it is no longer wanted. */
    private var generation = 0

    /** Counts the saves of the follow page, so a read that started before one doesn't undo it. */
    private var saves = 0

    /** The page shows, in an open sidebar: only then does it ask the gateway again by itself. */
    private var visible = false

    /** Runs paged in from before the kept ones; only on the screen, never kept. */
    private var older: List<InboxRun> = emptyList()

    /** Moves the store to the bound gateway; every look at the gateway waits for it. */
    private var bindJob: Job? = null
    private var loadJob: Job? = null
    private var openJob: Job? = null
    private var followJob: Job? = null
    private var saveJob: Job? = null
    private var moreJob: Job? = null

    /** Where the Inbox's lists were left (the folders, and the run list per folder), for the page to put back. */
    val places = mutableMapOf<String, ListPlace>()

    init {
        viewModelScope.launch {
            store.state.collect { kept ->
                // Another gateway's Inbox (the store is shared, and moves first) is not this one's.
                if (store.bound != gateway?.gatewayUrl?.value) return@collect
                _state.update { it.copy(runs = withOlder(kept.runs), read = kept.read, since = kept.since, groups = kept.groups) }
            }
        }
        // A run finished on the gateway: cron output arrives without an event of its own, so a sessions change
        // (an agent run is a session) is the nudge to look again. Each look reads the gateway's output files, so
        // only while the Inbox shows, and once for a burst.
        viewModelScope.launch {
            connection.events.filter { it.type == "sessions.changed" || it.type == "cron.changed" }
                .debounce(1_000)
                .collect { if (idle()) load(quiet = true) }
        }
        // Opened while the socket was still coming up: look once it is there. The follow page too, which waits
        // with its controls off until what this phone follows has been read.
        viewModelScope.launch {
            connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected && visible && gateway != null) lookAgain()
            }
        }
        viewModelScope.launch {
            combine(push.status, settings.settings) { status, prefs -> status.state to (prefs?.pushAnywhere == true) }
                .collect { (status, on) ->
                    val block = when {
                        !on || status == PushStatus.State.Off -> InboxBlock.PushOff
                        status == PushStatus.State.NotInstalled -> InboxBlock.PushOff
                        else -> null
                    }
                    val was = _state.value.block
                    _state.update { it.copy(block = block ?: it.block.takeIf { b -> b == InboxBlock.PluginOld }) }
                    if (was == InboxBlock.PushOff && block == null) load()
                }
        }
    }

    fun bind(gateway: SavedGateway) {
        if (this.gateway == gateway) return
        val first = this.gateway == null
        this.gateway = gateway
        generation++
        listOf(bindJob, loadJob, openJob, followJob, saveJob, moreJob).forEach { it?.cancel() }
        older = emptyList()
        places.clear()
        if (!first) {
            // Nothing of the last gateway's carries over: its jobs, what it follows, the run open, the filter.
            val block = _state.value.block?.takeIf { it == InboxBlock.PushOff }
            _state.value = InboxUiState(block = block)
        }
        val url = gateway.gatewayUrl.value
        val gen = generation
        bindJob = viewModelScope.launch {
            store.bind(url, Clock.System.now().epochSeconds)
            if (gen != generation) return@launch
            val kept = store.state.value
            _state.update { it.copy(runs = withOlder(kept.runs), read = kept.read, since = kept.since, groups = kept.groups) }
        }
        load()
    }

    /** Whether the page shows; it reads the gateway again when it comes into sight. */
    fun setVisible(visible: Boolean) {
        val was = this.visible
        this.visible = visible
        if (visible && !was && gateway != null) lookAgain()
    }

    /**
     * Reads the list again, and the follow page when it waits for what this phone follows (it was opened
     * offline, or out of sight when the socket came back): the jobs too, which may not have loaded either.
     */
    private fun lookAgain() {
        load(quiet = _state.value.runs.isNotEmpty())
        val s = _state.value
        if (s.choosing && !s.followingLoaded && !s.saving && followJob?.isActive != true) chooseJobs()
    }

    fun refresh() = load()

    override fun setFilter(filter: InboxFilter) = _state.update { it.copy(filter = filter) }

    /** Opens the Inbox on folders, or on every report in one list. */
    override fun setLayout(layout: InboxLayout) {
        _state.update { it.copy(filter = InboxFilter.All) }
        editGroups { it.copy(layout = layout) }
    }

    /** Splits the chips by job or by the user's groups. */
    override fun setChipMode(mode: ChipMode) {
        _state.update { it.copy(filter = InboxFilter.All) }
        editGroups { it.copy(mode = mode) }
    }

    override fun openGroups() = _state.update { it.copy(grouping = true) }

    override fun closeGroups() = _state.update { it.copy(grouping = false) }

    override fun addGroup(name: String) = editGroups { it.add(name) }

    override fun renameGroup(from: String, to: String) {
        val groups = _state.value.groups
        if (from !in groups.names || groups.refusal(to, renaming = from) != null) return
        // The folder or chip open on that group stays open under its new name.
        _state.update { it.copy(filter = it.filter.afterRename(from, InboxGroups.cleanName(to))) }
        editGroups { it.rename(from, to) }
    }

    override fun removeGroup(name: String) = editGroups { it.remove(name) }

    override fun assignGroup(job: InboxFilter.Job, group: String?) = editGroups { it.assign(job.bot, job.job, group) }

    /** Calls [chip]'s job [name] on this phone; blank puts its own name back. */
    override fun renameJob(chip: JobChip, name: String) = editGroups { it.renameJob(chip.filter.bot, chip.filter.job, name, chip.own) }

    private fun editGroups(change: (InboxGroups) -> InboxGroups) {
        val url = gateway?.gatewayUrl?.value ?: return
        viewModelScope.launch {
            bindJob?.join()
            store.editGroups(url, change)
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Nothing open over the list, and the list in sight: a background look won't get in the way. */
    private fun idle(): Boolean = visible && gateway != null && _state.value.open == null && !_state.value.choosing

    private fun load(quiet: Boolean = false) {
        if (gateway == null) return
        loadJob?.cancel()
        val gen = generation
        loadJob = viewModelScope.launch { loadNow(quiet, gen) }
    }

    private suspend fun loadNow(quiet: Boolean, gen: Int) {
        bindJob?.join()
        val url = gateway?.gatewayUrl?.value ?: return
        if (gen != generation) return
        if (!quiet) _state.update { it.copy(loading = it.runs.isEmpty(), error = null) }
        if (!ready()) {
            _state.update { it.copy(loading = false) }
            return
        }
        val result = runCatching { api.runs(InboxApi.PAGE) }
        if (gen != generation) return
        result.onSuccess { runs ->
            // A full look back starts the paging over; a quiet one keeps what was paged in.
            if (!quiet) older = emptyList()
            store.replace(url, runs, complete = runs.size < InboxApi.PAGE)
            if (gen != generation) return
            _state.update {
                it.copy(
                    // The store holds another gateway's Inbox when it moved meanwhile: this one's runs stay.
                    runs = if (store.bound == url) withOlder(store.state.value.runs) else it.runs,
                    loading = false,
                    error = null,
                    block = it.block.takeIf { b -> b == InboxBlock.PushOff },
                    more = if (quiet && older.isNotEmpty()) it.more else runs.size >= InboxApi.PAGE,
                    moreError = null,
                )
            }
        }.onFailure { e ->
            val old = e is InboxPluginTooOld
            _state.update {
                it.copy(
                    loading = false,
                    block = if (old) InboxBlock.PluginOld else it.block,
                    error = if (old || it.runs.isNotEmpty() || quiet) null else e.message ?: "Couldn't load the Inbox.",
                )
            }
        }
    }

    override fun loadMore() {
        val s = _state.value
        if (gateway == null || !s.more || s.loadingMore || moreJob?.isActive == true) return
        val before = pageCursor(s.runs) ?: return
        val gen = generation
        _state.update { it.copy(loadingMore = true, moreError = null) }
        moreJob = viewModelScope.launch {
            val result = if (ready()) runCatching { api.runs(InboxApi.PAGE, before) } else Result.failure(IllegalStateException("Not connected to the gateway."))
            if (gen != generation) return@launch
            result.onSuccess { page ->
                val (added, more) = pageIn(_state.value.runs, page, InboxApi.PAGE)
                older = (older + added).distinctBy { it.key }
                // Onto the runs shown, which are this gateway's: the shared store may hold another's.
                _state.update { it.copy(runs = withOlder(it.runs), loadingMore = false, more = more) }
            }.onFailure { e ->
                _state.update { it.copy(loadingMore = false, moreError = e.message ?: "Couldn't load more.") }
            }
        }
    }

    /** The kept runs and the ones paged in from before them, newest first, each once. */
    private fun withOlder(kept: List<InboxRun>): List<InboxRun> =
        if (older.isEmpty()) kept else (kept + older).distinctBy { it.key }.sortedByDescending { it.at }

    /** Whether the gateway can be asked now: connected, with Notifications anywhere on. */
    private suspend fun ready(): Boolean {
        if (connection.state.value !is ConnectionState.Connected) return false
        val prefs = settings.settings.first { it != null }
        if (prefs?.pushAnywhere != true) {
            _state.update { it.copy(block = InboxBlock.PushOff) }
            return false
        }
        return true
    }

    override fun open(run: InboxRun) {
        val url = gateway?.gatewayUrl?.value ?: return
        val gen = generation
        _state.update { it.copy(open = run, openLoading = true, openNote = null) }
        openJob?.cancel()
        openJob = viewModelScope.launch {
            bindJob?.join()
            store.markRead(url, run)
            val (full, note) = when {
                !ready() -> null to RunNote.Offline
                else -> runCatching { api.read(run) }.fold(
                    onSuccess = { it to (if (it == null) RunNote.Gone else null) },
                    onFailure = { null to RunNote.Failed },
                )
            }
            if (gen != generation) return@launch
            if (full != null) {
                store.add(url, full)
                if (older.any { it.key == full.key }) older = older.map { if (it.key == full.key) full else it }
            }
            _state.update { state ->
                if (state.open?.key != run.key) return@update state
                state.copy(open = full ?: run, openLoading = false, openNote = note)
            }
        }
    }

    /** Opens the run [key] names ([InboxRun.key]), from a notification; it came by push, so it's kept. */
    fun openKey(key: String) {
        val gen = generation
        openJob?.cancel()
        openJob = viewModelScope.launch {
            // The push that brought it may still be on its way into the store.
            val run = withTimeoutOrNull(OPEN_KEY_WAIT_MS) {
                store.state.first { kept -> store.bound == gateway?.gatewayUrl?.value && kept.runs.any { it.key == key } }
            }?.runs?.firstOrNull { it.key == key }
            if (gen != generation) return@launch
            if (run == null) {
                _state.update { it.copy(message = "That report isn't on the phone. It may be further back in the list.") }
                return@launch
            }
            _state.update { it.copy(choosing = false, grouping = false) }
            open(run)
        }
    }

    override fun closeRun() {
        openJob?.cancel()
        _state.update { it.copy(open = null, openLoading = false, openNote = null) }
    }

    override fun markAllRead() {
        val url = gateway?.gatewayUrl?.value ?: return
        val also = older
        viewModelScope.launch { store.markAllRead(url, also) }
    }

    /** Opens the follow page: every job, and how each reaches the Inbox now. */
    override fun chooseJobs() {
        val url = gateway?.gatewayUrl ?: return
        val gen = generation
        _state.update { it.copy(choosing = true, jobsLoading = it.jobs.isEmpty(), followingError = null) }
        followJob?.cancel()
        followJob = viewModelScope.launch {
            val jobs = cron.jobs(url)
            if (gen != generation) return@launch
            _state.update {
                it.copy(
                    jobs = (jobs as? ApiResult.Success)?.value?.sortedBy { job -> job.displayName.lowercase() } ?: it.jobs,
                    jobsLoading = false,
                    message = if (jobs !is ApiResult.Success) "Couldn't load the jobs." else it.message,
                )
            }
            readFollowing(gen)
        }
    }

    /**
     * Reads what this phone follows from the gateway. An answer is dropped when a save started after it was
     * asked: the save's own answer is newer.
     */
    private suspend fun readFollowing(gen: Int) {
        val savesBefore = saves
        val result = if (ready()) runCatching { api.following() } else Result.failure(IllegalStateException("Connect to the gateway to see what this phone follows."))
        if (gen != generation || saves != savesBefore) return
        result.onSuccess { following ->
            _state.update { it.copy(following = following, followingLoaded = true, followingError = null) }
        }.onFailure { e ->
            _state.update { it.copy(followingLoaded = false, followingError = followingErrorText(e)) }
        }
    }

    override fun closeJobs() = _state.update { it.copy(choosing = false) }

    override fun setFollow(job: CronJob, follow: Follow) = save { it.with(job.id, follow) }

    override fun setFailures(on: Boolean) = save { it.copy(failures = on) }

    /** Every job, new ones too, to [follow]: All on (into the Inbox) or All off. */
    override fun setAll(follow: Follow) = save { it.all(follow) }

    /** Saves [change] of what the gateway has; nothing until that has been read, as a save replaces all of it. */
    private fun save(change: (Following) -> Following) {
        val s = _state.value
        val url = gateway?.gatewayUrl?.value ?: return
        if (!s.canEditFollowing) return
        val before = s.following
        val next = change(before)
        val gen = generation
        saves++
        _state.update { it.copy(following = next, saving = true) }
        saveJob = viewModelScope.launch {
            if (!ready()) {
                // Nothing was sent, so nothing changed on the gateway.
                if (gen == generation) _state.update { it.copy(following = before, saving = false, message = "Connect to the gateway to change this.") }
                return@launch
            }
            val saved = runCatching { api.setFollowing(next) }
            if (gen != generation) return@launch
            saved.onSuccess { following ->
                _state.update { it.copy(following = following, followingLoaded = true, saving = false) }
                // The gateway only lists what is followed now; the runs kept from before would stay on show.
                store.prune(url, following::covers)
                older = older.filter(following::covers)
                load(quiet = true)
            }.onFailure { e ->
                // The save may have reached the gateway though its answer didn't (or was only partly applied,
                // by an old plugin): what it has now is asked again rather than guessed.
                _state.update { it.copy(message = saveErrorText(e)) }
                val reread = runCatching { api.following() }
                if (gen != generation) return@launch
                reread.onSuccess { following ->
                    _state.update { it.copy(following = following, followingLoaded = true, saving = false, followingError = null) }
                }.onFailure { again ->
                    _state.update {
                        it.copy(
                            followingLoaded = false,
                            saving = false,
                            followingError = "Couldn't tell whether that was saved. ${followingErrorText(again)}",
                        )
                    }
                }
            }
        }
    }

    private fun followingErrorText(e: Throwable): String =
        if (e is InboxPluginTooOld) InboxApi.TOO_OLD else e.message ?: "Couldn't read what this phone follows."

    companion object {
        /** The plugin the Inbox needs, for the message that asks to update it. */
        const val NEEDS_PLUGIN = InboxApi.MIN_PLUGIN_VERSION
        const val PLUGIN_NOW = PushApi.PLUGIN_VERSION

        /** How long a run opened from a notification is waited for, while its push is stored. */
        const val OPEN_KEY_WAIT_MS = 10_000L
    }
}
