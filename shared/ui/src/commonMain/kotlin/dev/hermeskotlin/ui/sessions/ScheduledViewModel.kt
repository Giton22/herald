package dev.hermeskotlin.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScheduledUiState(
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The job whose page is open; null shows the job list. */
    val openJobId: String? = null,
    val runs: List<SessionSummary> = emptyList(),
    val runsLoading: Boolean = false,
    val runsError: String? = null,
    /** A pause, resume or run-now request is in flight. */
    val busy: Boolean = false,
    val message: String? = null,
    val sessionExpired: Boolean = false,
) {
    val openJob: CronJob? get() = jobs.firstOrNull { it.id == openJobId }
}

/**
 * Scheduled jobs and their runs. A run is an ordinary session, so opening one is just opening a
 * chat; the job page is where those chats are reached from.
 */
class ScheduledViewModel(
    private val api: CronApi,
    private val connection: GatewayConnection,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduledUiState())
    val state: StateFlow<ScheduledUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var runsJob: Job? = null

    init {
        observeServerChanges()
    }

    fun bind(gateway: SavedGateway) {
        if (this.gateway == gateway) return
        this.gateway = gateway
        _state.value = ScheduledUiState()
        refresh()
    }

    /** Refetches the jobs, and the open job's runs. */
    fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        viewModelScope.launch {
            val result = api.jobs(url)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(jobs = result.value, loading = false, error = null)
                    else -> state.copy(
                        loading = false,
                        error = result.errorMessage.takeIf { state.jobs.isEmpty() },
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
        _state.value.openJobId?.let(::loadRuns)
    }

    fun openJob(jobId: String) {
        _state.update { it.copy(openJobId = jobId, runs = emptyList(), runsError = null) }
        loadRuns(jobId)
    }

    fun closeJob() {
        runsJob?.cancel()
        _state.update { it.copy(openJobId = null, runs = emptyList(), runsLoading = false, runsError = null) }
    }

    fun togglePaused() {
        val job = _state.value.openJob ?: return
        act(if (job.paused) "Couldn't resume the job" else "Couldn't pause the job") { url ->
            if (job.paused) api.resume(url, job.id) else api.pause(url, job.id)
        }
    }

    fun runNow() {
        val job = _state.value.openJob ?: return
        act("Couldn't start a run", success = "Run started") { url -> api.trigger(url, job.id) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Calls a job action and swaps in the job record the server returns. */
    private fun act(failure: String, success: String? = null, call: suspend (GatewayUrl) -> ApiResult<CronJob>) {
        val url = gateway?.gatewayUrl ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = call(url)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(
                        busy = false,
                        jobs = state.jobs.map { if (it.id == result.value.id) result.value else it },
                        message = success,
                    )
                    else -> state.copy(
                        busy = false,
                        message = listOfNotNull(failure, result.errorMessage).joinToString(": "),
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
            if (result is ApiResult.Success) _state.value.openJobId?.let(::loadRuns)
        }
    }

    private fun loadRuns(jobId: String) {
        val url = gateway?.gatewayUrl ?: return
        runsJob?.cancel()
        _state.update { it.copy(runsLoading = it.runs.isEmpty()) }
        runsJob = viewModelScope.launch {
            val result = api.runs(url, jobId)
            _state.update { state ->
                if (state.openJobId != jobId) return@update state
                when (result) {
                    is ApiResult.Success -> state.copy(runs = result.value, runsLoading = false, runsError = null)
                    else -> state.copy(
                        runsLoading = false,
                        runsError = result.errorMessage.takeIf { state.runs.isEmpty() },
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    /** A finished run shows up as a changed session; refetch so new runs and next-run times appear. */
    @OptIn(FlowPreview::class)
    private fun observeServerChanges() {
        viewModelScope.launch {
            connection.events
                .filter { it.type == "sessions.changed" }
                .debounce(1_000)
                .collect { refresh() }
        }
    }
}
