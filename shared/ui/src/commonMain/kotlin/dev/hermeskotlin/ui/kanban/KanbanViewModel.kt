package dev.hermeskotlin.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.core.kanban.KanbanBoardResponse
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanEvents
import dev.hermeskotlin.core.kanban.KanbanLogResponse
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.KanbanTaskDetailResponse
import dev.hermeskotlin.core.kanban.stale
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.kanbanBoard
import dev.hermeskotlin.core.settings.withKanbanBoard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether [task] matches the toolbar filters, the dashboard's predicate (search is a substring over the row). */
fun KanbanTask.matches(q: String, tenant: String?, assignee: String?): Boolean {
    if (tenant != null && tenant.isNotBlank() && tenant != this.tenant) return false
    if (assignee != null && assignee.isNotBlank() && assignee != this.assignee) return false
    if (q.isBlank()) return true
    val hay = "$id ${title.ifBlank { "" }} ${body.orEmpty()} ${result.orEmpty()} ${latestSummary.orEmpty()} ${assignee.orEmpty()} ${tenant.orEmpty()}".lowercase()
    return q.trim().lowercase() in hay
}

data class KanbanUiState(
    val boards: List<KanbanBoardMeta> = emptyList(),
    val boardsLoading: Boolean = true,
    val boardsError: String? = null,
    /** The board the columns belong to; null while the board list loads, and when the gateway has none. */
    val board: String? = null,
    val response: KanbanBoardResponse? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** A quiet reload (live event or refresh) failed; the snapshot above may be stale. */
    val reloadError: Boolean = false,
    /** The events tail ended on its own (refused or terminal close); live updates are off until a reload. */
    val eventsDead: Boolean = false,
    val sessionExpired: Boolean = false,
    val search: String = "",
    val tenantFilter: String? = null,
    val assigneeFilter: String? = null,
    val includeArchived: Boolean = false,
    val openTaskId: String? = null,
    val detail: KanbanTaskDetailResponse? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val log: KanbanLogResponse? = null,
    val logLoading: Boolean = false,
    val logError: String? = null,
) {
    val filteredColumns: List<KanbanColumn> get() {
        val q = search
        return response?.columns.orEmpty().map { col ->
            col.copy(tasks = col.tasks.filter { it.matches(q, tenantFilter, assigneeFilter) })
        }
    }
}

/**
 * The sidebar's Kanban page: boards, then one board's columns, then one task's detail. Read-only.
 *
 * The REST pin (`board=` on every call) and the events socket pin (`?board=` at the handshake) always agree:
 * both read the page's [KanbanUiState.board], and the board only changes through [selectBoard], which restarts
 * the tail. The tail itself is one per board: event-driven reloads reuse its collector instead of re-opening
 * the socket, and it only restarts after a board change or once it has ended on its own.
 *
 * Every load guards its answer against the gateway, board and task it was issued for, so a switch to another
 * gateway (or board, or task) cancels the old work and never shows its late replies.
 */
class KanbanViewModel(
    private val api: KanbanApi,
    private val events: KanbanEvents,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(KanbanUiState())
    val state: StateFlow<KanbanUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var stopped = false
    private var boardsJob: Job? = null
    private var boardJob: Job? = null
    private var detailJob: Job? = null
    private var logJob: Job? = null
    private var eventsJob: Job? = null
    private var tailUrl: GatewayUrl? = null
    private var tailBoard: String? = null
    private var cursor: Long? = null

    /**
     * A task asked for from outside the page (a widget tap, a `hermes://kanban/<board>/<taskId>` link) that
     * arrived before its board loaded: the board slug ("" names whatever board resolves) and the task id.
     * Applied once a board load completes — [selectBoard] runs first when the queue names another board —
     * and a task the board doesn't have clears the queue silently (no error row for a deleted task).
     */
    private val _pendingOpenTask = MutableStateFlow<Pair<String, String>?>(null)
    val pendingOpenTask: StateFlow<Pair<String, String>?> = _pendingOpenTask.asStateFlow()

    fun queueOpenTask(board: String, taskId: String) {
        _pendingOpenTask.value = board to taskId
    }

    /** Runs on every completed board load: selects the queued board first when it differs, else opens the task. */
    private fun applyPendingOpenTask(slug: String) {
        val (board, taskId) = _pendingOpenTask.value ?: return
        if (board.isNotEmpty() && board != slug) {
            selectBoard(board)
            return
        }
        _pendingOpenTask.value = null
        val tasks = _state.value.response?.columns.orEmpty().flatMap { it.tasks }
        if (tasks.any { it.id == taskId }) openTask(taskId)
        // else: a deleted task clears the queue silently.
    }

    /** The page appeared. A new gateway starts over; a reopen after [stop] refreshes and retails. */
    fun bind(gateway: SavedGateway) {
        if (this.gateway == gateway) {
            if (!stopped) return
            stopped = false
            refresh()
            return
        }
        cancelJobs()
        this.gateway = gateway
        stopped = false
        _state.value = KanbanUiState()
        cursor = null
        // A pending task belongs to the gateway it was queued on; the new one starts clean.
        _pendingOpenTask.value = null
        refresh()
    }

    /** The page left the composition: every load and the events tail stop with it. */
    fun stop() {
        if (stopped) return
        stopped = true
        cancelJobs()
    }

    fun refresh() {
        if (stopped) return
        val url = gateway?.gatewayUrl ?: return
        boardsJob?.cancel()
        boardsJob = viewModelScope.launch {
            val board = refreshBoards(url) ?: return@launch
            loadBoard(url, board, quiet = _state.value.response != null)
        }
    }

    fun selectBoard(slug: String) {
        val url = gateway?.gatewayUrl ?: return
        if (_state.value.board == slug) return
        cancelJobs()
        cursor = null
        _state.update {
            it.copy(
                board = slug, response = null, loading = true, error = null,
                reloadError = false, eventsDead = false,
                openTaskId = null, detail = null, detailLoading = false, detailError = null,
                log = null, logLoading = false, logError = null,
            )
        }
        settingsStore.update { s -> s.withKanbanBoard(url, slug) }
        loadBoard(url, slug, quiet = false)
    }

    fun setSearch(q: String) = _state.update { it.copy(search = q) }

    // The gateway filters tenant server-side on /board, so a tenant change must reload the snapshot
    // the client filter below runs over — like setIncludeArchived — or the chip and the server
    // snapshot disagree after any quiet reload (picking waypoint over a herald-only snapshot
    // showed "No tasks" until the next reload).
    fun setTenant(tenant: String?) {
        if (_state.value.tenantFilter == tenant) return
        _state.update { it.copy(tenantFilter = tenant) }
        val url = gateway?.gatewayUrl ?: return
        val slug = _state.value.board ?: return
        loadBoard(url, slug, quiet = false)
    }

    fun setAssignee(assignee: String?) = _state.update { it.copy(assigneeFilter = assignee) }
    fun setIncludeArchived(on: Boolean) {
        if (_state.value.includeArchived == on) return
        _state.update { it.copy(includeArchived = on) }
        val url = gateway?.gatewayUrl ?: return
        val slug = _state.value.board ?: return
        loadBoard(url, slug, quiet = false)
    }

    fun openTask(taskId: String) {
        val url = gateway?.gatewayUrl ?: return
        val slug = _state.value.board ?: return
        detailJob?.cancel()
        logJob?.cancel()
        _state.update {
            it.copy(
                openTaskId = taskId, detail = null, detailLoading = true, detailError = null,
                log = null, logLoading = false, logError = null,
            )
        }
        loadDetail(url, slug, taskId, quiet = false)
    }

    fun closeTask() {
        detailJob?.cancel()
        logJob?.cancel()
        _state.update {
            it.copy(
                openTaskId = null, detail = null, detailLoading = false, detailError = null,
                log = null, logLoading = false, logError = null,
            )
        }
    }

    /** The drawer's retry: loads the task again (never the worker log). */
    fun reloadDetail() {
        if (stopped) return
        val url = gateway?.gatewayUrl ?: return
        val slug = _state.value.board ?: return
        val taskId = _state.value.openTaskId ?: return
        loadDetail(url, slug, taskId, quiet = false)
    }

    /** The worker-log section's retry: loads the log again. */
    fun reloadLog() {
        if (stopped) return
        val url = gateway?.gatewayUrl ?: return
        val slug = _state.value.board ?: return
        val taskId = _state.value.openTaskId ?: return
        loadLog(url, slug, taskId)
    }

    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }

    private fun cancelJobs() {
        boardsJob?.cancel()
        boardJob?.cancel()
        detailJob?.cancel()
        logJob?.cancel()
        eventsJob?.cancel()
        boardsJob = null
        boardJob = null
        detailJob = null
        logJob = null
        eventsJob = null
        tailUrl = null
        tailBoard = null
    }

    /** Awaits the stored settings (cold start must not lose the per-gateway pin), then lists the boards. */
    private suspend fun refreshBoards(url: GatewayUrl): String? {
        val settings = settingsStore.settings.filterNotNull().first()
        val result = api.boards(url)
        if (stopped || gateway?.gatewayUrl != url) return null
        _state.update { state ->
            when (result) {
                is ApiResult.Success -> {
                    val boards = result.value.boards
                    val picked = settings.kanbanBoard(url)?.takeIf { slug -> boards.any { it.slug == slug } }
                    // The board to show: the still-existing selection, then the saved pin, then the
                    // gateway's current, then the first board. None at all is a real, reachable state.
                    val board = state.board?.takeIf { slug -> boards.any { it.slug == slug } }
                        ?: picked
                        ?: result.value.current.takeIf { slug -> boards.any { it.slug == slug } }
                        ?: boards.firstOrNull()?.slug
                    // A change of board invalidates the columns below it: never keep the old board's
                    // response (or its open drawer) under the new selection.
                    val changed = board != state.board
                    state.copy(
                        boards = boards,
                        boardsLoading = false,
                        boardsError = null,
                        board = board,
                        response = if (changed) null else state.response,
                        loading = if (changed) true else state.loading,
                        error = if (changed) null else state.error,
                        reloadError = false,
                        eventsDead = false,
                        openTaskId = if (changed) null else state.openTaskId,
                        detail = if (changed) null else state.detail,
                        detailLoading = if (changed) false else state.detailLoading,
                        detailError = if (changed) null else state.detailError,
                        log = if (changed) null else state.log,
                        logLoading = if (changed) false else state.logLoading,
                        logError = if (changed) null else state.logError,
                    )
                }
                else -> state.copy(
                    boardsLoading = false,
                    boardsError = result.errorMessage.takeIf { state.boards.isEmpty() },
                    // A boards refresh that fails over a live snapshot says the columns may be stale,
                    // like a quiet board reload does — it must not clear every warning.
                    reloadError = state.response != null,
                    sessionExpired = state.sessionExpired || result == ApiResult.SessionExpired,
                )
            }
        }
        return _state.value.board.takeIf { result is ApiResult.Success }
    }

    private fun loadBoard(url: GatewayUrl, slug: String, quiet: Boolean) {
        if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug) return
        boardJob?.cancel()
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        boardJob = viewModelScope.launch {
            val filters = _state.value
            val result = api.board(url, slug, tenant = filters.tenantFilter, includeArchived = filters.includeArchived)
            // A late answer from a replaced board/gateway must not populate the current state.
            if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug) return@launch
            _state.update { state ->
                val expired = state.sessionExpired || result == ApiResult.SessionExpired
                when (result) {
                    is ApiResult.Success -> state.copy(
                        response = result.value, loading = false, error = null,
                        reloadError = false, eventsDead = false, sessionExpired = expired,
                    )
                    // A quiet failure keeps the last snapshot but says so; a first load shows the error.
                    else -> if (state.response == null) {
                        state.copy(loading = false, error = result.errorMessage, sessionExpired = expired)
                    } else {
                        state.copy(reloadError = true, sessionExpired = expired)
                    }
                }
            }
            if (result is ApiResult.Success) {
                cursor = maxOf(cursor ?: 0L, result.value.latestEventId)
                startTail(url, slug)
                applyPendingOpenTask(slug)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startTail(url: GatewayUrl, slug: String) {
        if (stopped) return
        // One tail per board: quiet reloads and later frames reuse this collector, and the socket stays open.
        if (eventsJob?.isActive == true && tailUrl == url && tailBoard == slug) return
        eventsJob?.cancel()
        tailUrl = url
        tailBoard = slug
        eventsJob = viewModelScope.launch {
            events.observe(url, slug, cursor)
                .debounce(RELOAD_MS)
                .collect { frame ->
                    if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug) return@collect
                    cursor = maxOf(cursor ?: frame.cursor, frame.cursor)
                    val open = _state.value.openTaskId
                    loadBoard(url, slug, quiet = true)
                    if (open != null && frame.events.any { it.taskId == open }) {
                        loadDetail(url, slug, open, quiet = true)
                    }
                }
            // The tail ended on its own (terminal close or refusal give-up): live updates are off for now.
            if (!stopped && gateway?.gatewayUrl == url && _state.value.board == slug) {
                _state.update { it.copy(eventsDead = true) }
            }
        }
    }

    private fun loadDetail(url: GatewayUrl, slug: String, taskId: String, quiet: Boolean) {
        if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug) return
        detailJob?.cancel()
        if (!quiet) _state.update { it.copy(detailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
            val result = api.task(url, taskId, slug)
            if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug || _state.value.openTaskId != taskId) return@launch
            _state.update { state ->
                val expired = state.sessionExpired || result == ApiResult.SessionExpired
                when (result) {
                    is ApiResult.Success -> state.copy(
                        detail = result.value, detailLoading = false, detailError = null, sessionExpired = expired,
                    )
                    else -> state.copy(
                        detailLoading = false,
                        // A quiet reload failure over an open drawer keeps the stale content and the
                        // warning; only the message matters, never hiding the detail that is still shown.
                        detailError = result.errorMessage,
                        sessionExpired = expired,
                    )
                }
            }
            if (result is ApiResult.Success && _state.value.openTaskId == taskId && _state.value.log == null) {
                loadLog(url, slug, taskId)
            }
        }
    }

    private fun loadLog(url: GatewayUrl, slug: String, taskId: String) {
        if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug) return
        logJob?.cancel()
        _state.update { it.copy(logLoading = true, logError = null) }
        logJob = viewModelScope.launch {
            val result = api.log(url, taskId, slug)
            if (stopped || gateway?.gatewayUrl != url || _state.value.board != slug || _state.value.openTaskId != taskId) return@launch
            _state.update { state ->
                val expired = state.sessionExpired || result == ApiResult.SessionExpired
                when (result) {
                    is ApiResult.Success -> state.copy(log = result.value, logLoading = false, logError = null, sessionExpired = expired)
                    else -> state.copy(
                        logLoading = false,
                        logError = result.errorMessage.takeIf { state.log == null },
                        sessionExpired = expired,
                    )
                }
            }
        }
    }

    companion object {
        /** Any frame reloads the board this long after the last one lands, as the dashboard's `scheduleReload`. */
        private const val RELOAD_MS = 250L
    }
}
