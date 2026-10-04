package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.AuthUser
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileRoster
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
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
)

/**
 * Stored-session browser: list (REST, paged), search, and row actions. Refetches when the gateway
 * broadcasts `sessions.changed` / `session.title`, and after each reconnect (events may have been missed).
 */
class SessionsViewModel(
    private val api: SessionsApi,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
    private val lastChats: LastChatStore,
    private val profiles: ProfilesApi,
    private val drafts: DraftStore,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connection.state

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val _roster = MutableStateFlow<ProfileRoster?>(null)

    /** The gateway's profiles; null until loaded (or when the gateway doesn't list them). */
    val roster: StateFlow<ProfileRoster?> = _roster.asStateFlow()

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    val query = TextFieldState()

    private var gateway: SavedGateway? = null

    /** The profile whose sessions are listed; null is the gateway's launch profile. */
    private var profile: String? = null
    private var loadJob: Job? = null

    private val bound = MutableStateFlow<Pair<GatewayUrl, String?>?>(null)

    /** Sessions with unsent text in their composer, marked "Draft" in the list. */
    @OptIn(ExperimentalCoroutinesApi::class)
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
        _state.update { SessionsUiState(filter = filter) }
        load(refresh = false)
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

    /** Optimistic row update: apply locally (list and search results), call the server, roll back on failure. */
    private fun mutate(
        apply: (List<SessionSummary>) -> List<SessionSummary>,
        call: suspend (GatewayUrl, String?) -> ApiResult<Unit>,
    ) {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        val before = _state.value
        _state.update { it.copy(sessions = apply(it.sessions), searchResults = it.searchResults?.let(apply)) }
        viewModelScope.launch {
            val result = call(url, profile)
            if (result !is ApiResult.Success) {
                _state.update {
                    it.copy(
                        sessions = before.sessions,
                        searchResults = before.searchResults,
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
    }
}

private val ApiResult<*>.isExpired: Boolean get() = this == ApiResult.SessionExpired
