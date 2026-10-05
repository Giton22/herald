package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.AuthUser
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.sessions.SeenChats
import dev.hermeskotlin.core.sessions.SeenStore
import kotlinx.coroutines.flow.combine
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileRoster
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.projects.ProjectsApi
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val filter: SessionListFilter = SessionListFilter.Recent,
    /** Nothing loaded yet for this filter. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    /** The list itself failed to load. */
    val error: String? = null,
    /** Results for the search box; null while it is empty. */
    val searchResults: List<SessionSummary>? = null,
    val searching: Boolean = false,
    /** One-off feedback for a failed row action, cleared by [SessionsViewModel.dismissMessage]. */
    val message: String? = null,
    val sessionExpired: Boolean = false,
    /** The gateway's projects that have chats; empty when it has none or doesn't know projects. */
    val projects: List<Project> = emptyList(),
    /** The project the recent list is narrowed to; null for every chat. */
    val project: Project? = null,
    /** [project]'s chats; null while they load (or with no project picked). */
    val projectSessions: List<SessionSummary>? = null,
) {
    /** The recent list as it shows: [project]'s chats, or every loaded chat. */
    val listed: List<SessionSummary> get() = if (project != null) projectSessions.orEmpty() else sessions
}

/** The recent list narrowed to what's running, or to chats waiting on the user or with a reply not yet read. */
enum class AttentionFilter(val label: String) {
    All("All"),
    Running("Running"),
    NeedsAttention("Needs attention"),
}

/** What a row says about its chat besides the title, in words. [running]: a turn is going right now. */
data class RowStatus(val waiting: Waiting? = null, val unread: Boolean = false, val running: Boolean = false) {
    val needsAttention: Boolean get() = waiting != null || unread
}

/**
 * Stored-session browser: list (REST, paged), search, and row actions. Refetches when the gateway
 * broadcasts `sessions.changed` / `session.title`, and after each reconnect (events may have been missed).
 * Marks chats waiting on the user ([AttentionTracker]) and ones with a reply not yet read ([SeenStore]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val api: SessionsApi,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
    private val lastChats: LastChatStore,
    private val profiles: ProfilesApi,
    attention: AttentionTracker,
    private val seenStore: SeenStore,
    private val drafts: DraftStore,
    private val projectsApi: ProjectsApi,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connection.state

    private val bound = MutableStateFlow<Pair<GatewayUrl, String?>?>(null)

    private val _attentionFilter = MutableStateFlow(AttentionFilter.All)
    val attentionFilter: StateFlow<AttentionFilter> = _attentionFilter.asStateFlow()

    private val seen: StateFlow<SeenChats?> = bound
        .flatMapLatest { scope -> scope?.let { (url, profile) -> seenStore.seen(url, profile) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val waiting = attention.waiting
    private val running = attention.running

    fun setAttentionFilter(filter: AttentionFilter) {
        _attentionFilter.value = filter
    }

    /** The user has looked at [session] as it is now: its reply is read. */
    fun markSeen(session: SessionSummary) {
        val (url, profile) = bound.value ?: return
        viewModelScope.launch { seenStore.markSeen(url, session, profile) }
    }

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val _roster = MutableStateFlow<ProfileRoster?>(null)

    /** The gateway's profiles; null until loaded (or when the gateway doesn't list them). */
    val roster: StateFlow<ProfileRoster?> = _roster.asStateFlow()

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    /** Whether the list is on screen ([setVisible]): the sidebar stays composed while closed. */
    private val visible = MutableStateFlow(false)

    /**
     * Each listed chat's status by id; chats with nothing to say are left out. Only listed chats get one:
     * the gateway's live list spans every profile. Worked out only while the list shows, so the gateway is
     * asked for live statuses only then; hidden, the last statuses stand.
     */
    val statuses: StateFlow<Map<String, RowStatus>> = visible.flatMapLatest { shown ->
        if (!shown) return@flatMapLatest emptyFlow()
        combine(_state, waiting, seen, running) { state, waiting, seen, running ->
            (state.sessions + state.searchResults.orEmpty() + state.projectSessions.orEmpty()).mapNotNull { session ->
                RowStatus(waiting[session.id], seen?.isUnread(session) == true, running[session.id] == true)
                    .takeIf { it.needsAttention || it.running }?.let { session.id to it }
            }.toMap()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyMap())

    /** The list is on screen (the sidebar open or docked), or no longer is. */
    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    val query = TextFieldState()

    private var gateway: SavedGateway? = null

    /** The profile whose sessions are listed; null is the gateway's launch profile. */
    private var profile: String? = null
    private var loadJob: Job? = null

    /** Sessions with unsent text in their composer, marked "Draft" in the list. */
    val draftChats: StateFlow<Set<String>> = bound
        .flatMapLatest { scope -> scope?.let { (url, profile) -> drafts.chatsWithDrafts(url, profile) } ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        observeServerChanges()
        observeSearch()
    }

    /** Idempotent: binds to [gateway] and [profile] and loads the first page once. */
    fun bind(gateway: SavedGateway, profile: String? = null) {
        if (this.gateway == gateway && this.profile == profile) return
        val newGateway = this.gateway != gateway
        this.gateway = gateway
        this.profile = profile
        bound.value = gateway.gatewayUrl to profile
        _state.value = SessionsUiState()
        load(refresh = false)
        if (newGateway) {
            _user.value = null
            _roster.value = null
            viewModelScope.launch {
                (auth.me(gateway.gatewayUrl) as? ApiResult.Success)?.let { _user.value = it.value }
            }
            refreshProfiles()
        }
    }

    /** Refetches the profile list, e.g. when the account sheet opens (one may have been added on the host). */
    fun refreshProfiles() {
        val url = gateway?.gatewayUrl ?: return
        viewModelScope.launch {
            (profiles.roster(url) as? ApiResult.Success)?.let { _roster.value = it.value }
        }
    }

    fun refresh() = load(refresh = true)

    /** Background refetch without the spinner, e.g. when the list becomes visible again. */
    fun refreshQuietly() = load(refresh = false)

    fun setFilter(filter: SessionListFilter) {
        if (filter == _state.value.filter) return
        // The picked project stays for the way back; the archive isn't split by project.
        _state.update { SessionsUiState(filter = filter, projects = it.projects, project = it.project, projectSessions = it.projectSessions) }
        load(refresh = false)
    }

    /** Narrows the recent list to [project]'s chats, or shows every chat again with null. */
    fun selectProject(project: Project?) {
        if (project?.id == _state.value.project?.id) return
        _state.update { it.copy(project = project, projectSessions = null) }
        loadProjectSessions()
    }

    private var projectsJob: Job? = null
    private var projectsFor: String? = null

    /** The project list, again: after each list load, since no event says a chat moved between projects. */
    private fun loadProjects() {
        if (connection.state.value !is ConnectionState.Connected) return
        val profile = profile
        // Connecting and the first list load ask at about the same time; one answer serves both.
        if (projectsJob?.isActive == true && projectsFor == profile) return
        projectsJob?.cancel()
        projectsFor = profile
        projectsJob = viewModelScope.launch {
            val projects = try {
                projectsApi.projects(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An older gateway without projects, or a passing failure: no chips, every chat listed.
                emptyList()
            }
            if (profile != this@SessionsViewModel.profile) return@launch
            // Only worth a filter when some chats are in a project, not all in Home.
            val shown = projects.takeIf { list -> list.any { !it.isNoProject } }.orEmpty()
            _state.update { state ->
                val picked = state.project?.let { p -> shown.firstOrNull { it.id == p.id } }
                state.copy(projects = shown, project = picked, projectSessions = state.projectSessions.takeIf { picked != null })
            }
            loadProjectSessions()
        }
    }

    private fun loadProjectSessions() {
        val project = _state.value.project ?: return
        val profile = profile
        viewModelScope.launch {
            val rows = try {
                projectsApi.sessions(profile, project.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.project?.id == project.id) it.copy(projectSessions = emptyList(), message = e.message) else it }
                return@launch
            }
            _state.update { if (it.project?.id == project.id) it.copy(projectSessions = rows) else it }
        }
    }

    fun loadMore() {
        val current = _state.value
        val url = gateway?.gatewayUrl ?: return
        if (!current.canLoadMore || current.loadingMore || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            val result = api.list(url, offset = current.sessions.size, filter = current.filter, profile = profile)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> {
                        val known = state.sessions.mapTo(HashSet()) { it.id }
                        val fresh = result.value.sessions.filter { it.id !in known }
                        val merged = state.sessions + fresh
                        // A page with nothing new means the server's total counts rows the list never returns.
                        state.copy(sessions = merged, loadingMore = false, canLoadMore = fresh.isNotEmpty() && merged.size < result.value.total)
                    }
                    else -> state.copy(loadingMore = false, message = result.errorMessage, sessionExpired = result.isExpired)
                }
            }
        }
    }

    fun retryConnection() = connection.retry()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Clears the expiry flag once a screen has acted on it; the view model outlives the screen, so
     *  leaving it set would bounce the next mount after a fresh sign-in. */
    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }

    fun rename(session: SessionSummary, title: String) = mutate(
        apply = { list -> list.map { if (it.id == session.id) it.copy(title = title.ifBlank { null }) else it } },
        call = { url, profile -> api.rename(url, session.id, title.trim(), profile) },
    )

    fun togglePinned(session: SessionSummary) = mutate(
        apply = { list -> list.map { if (it.id == session.id) it.copy(pinned = !session.pinned) else it } },
        call = { url, profile -> api.setPinned(url, session.id, !session.pinned, profile) },
    )

    /** Archiving moves the row to the other filter, so it leaves the current list either way. */
    fun toggleArchived(session: SessionSummary) = mutate(
        apply = { list -> list.filterNot { it.id == session.id } },
        call = { url, profile -> api.setArchived(url, session.id, !session.archived, profile) },
    )

    fun delete(session: SessionSummary) = mutate(
        apply = { list -> list.filterNot { it.id == session.id } },
        call = { url, profile ->
            api.delete(url, session.id, profile).also { if (it is ApiResult.Success) lastChats.forget(url, session.id, profile) }
        },
    )

    /** Optimistic row update: apply locally (list, search results, project), call the server, roll back on failure. */
    private fun mutate(
        apply: (List<SessionSummary>) -> List<SessionSummary>,
        call: suspend (GatewayUrl, String?) -> ApiResult<Unit>,
    ) {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        val before = _state.value
        _state.update {
            it.copy(sessions = apply(it.sessions), searchResults = it.searchResults?.let(apply), projectSessions = it.projectSessions?.let(apply))
        }
        viewModelScope.launch {
            val result = call(url, profile)
            if (result !is ApiResult.Success) {
                _state.update {
                    it.copy(
                        sessions = before.sessions,
                        searchResults = before.searchResults,
                        projectSessions = before.projectSessions.takeIf { _ -> it.project?.id == before.project?.id } ?: it.projectSessions,
                        message = result.errorMessage,
                        sessionExpired = result.isExpired,
                    )
                }
            }
        }
    }

    private fun load(refresh: Boolean) {
        val url = gateway?.gatewayUrl ?: return
        val filter = _state.value.filter
        // This may cancel a page fetch mid-flight, which would otherwise leave its spinner up for good.
        loadJob?.cancel()
        _state.update { (if (refresh) it.copy(refreshing = true) else it.copy(loading = it.sessions.isEmpty())).copy(loadingMore = false) }
        if (filter == SessionListFilter.Recent) loadProjects()
        loadJob = viewModelScope.launch {
            // Keep however many rows are already showing so a background refetch doesn't truncate the list.
            val limit = _state.value.sessions.size.coerceIn(SessionsApi.PAGE_SIZE, 100)
            val result = api.list(url, limit = limit, filter = filter, profile = profile)
            _state.update { state ->
                if (state.filter != filter) return@update state
                when (result) {
                    is ApiResult.Success -> state.copy(
                        sessions = result.value.sessions,
                        loading = false,
                        refreshing = false,
                        error = null,
                        canLoadMore = result.value.sessions.size < result.value.total,
                    )
                    else -> state.copy(
                        loading = false,
                        refreshing = false,
                        // A failed background refresh keeps the rows; only an empty list shows the error.
                        error = result.errorMessage.takeIf { state.sessions.isEmpty() },
                        message = result.errorMessage.takeIf { state.sessions.isNotEmpty() && refresh },
                        sessionExpired = result.isExpired,
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeServerChanges() {
        viewModelScope.launch {
            connection.events
                .filter { it.type in REFRESH_EVENTS }
                .debounce(400)
                .collect { load(refresh = false) }
        }
        // Projects come over the socket, which may connect after the list (REST) has loaded.
        viewModelScope.launch {
            connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged().filter { it }.collect {
                if (gateway != null && _state.value.filter == SessionListFilter.Recent) loadProjects()
            }
        }
        viewModelScope.launch {
            var wasConnected = true
            connection.state.collect { state ->
                val connected = state is ConnectionState.Connected
                if (connected && !wasConnected) load(refresh = false)
                wasConnected = connected
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            snapshotFlow { query.text.toString().trim() }
                .distinctUntilChanged()
                .map { it.takeIf { q -> q.length >= MIN_QUERY } }
                .debounce { if (it == null) 0 else 300 }
                .collectLatest { q ->
                    val url = gateway?.gatewayUrl
                    if (q == null || url == null) {
                        _state.update { it.copy(searchResults = null, searching = false) }
                        return@collectLatest
                    }
                    _state.update { it.copy(searching = true) }
                    val result = api.search(url, q, profile = profile)
                    _state.update {
                        when (result) {
                            is ApiResult.Success -> it.copy(searchResults = result.value, searching = false)
                            else -> it.copy(
                                searchResults = emptyList(),
                                searching = false,
                                message = result.errorMessage,
                                sessionExpired = result.isExpired,
                            )
                        }
                    }
                }
        }
    }

    private companion object {
        val REFRESH_EVENTS = setOf("sessions.changed", "session.title")
        const val MIN_QUERY = 2

        /** Brief gaps (a screen rotating) keep the statuses collected. */
        const val STOP_AFTER_MS = 5_000L
    }
}

private val ApiResult<*>.isExpired: Boolean get() = this == ApiResult.SessionExpired
