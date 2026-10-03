package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.cron.CronJobDraft
import dev.hermeskotlin.core.cron.DeliveryTarget
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
    /** The job form, for a new job or an existing one; null when it isn't open. */
    val editor: JobEditor? = null,
    val deliveryTargets: List<DeliveryTarget> = listOf(LOCAL_DELIVERY),
    /** The open job is waiting for its delete to be confirmed. */
    val confirmingDelete: Boolean = false,
) {
    val openJob: CronJob? get() = jobs.firstOrNull { it.id == openJobId }
}

/** The job form's state outside its three text fields, which live on the view model. */
data class JobEditor(
    /** Null for a new job. */
    val jobId: String? = null,
    val deliver: String = LOCAL_DELIVERY.id,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = jobId == null
}

internal val LOCAL_DELIVERY = DeliveryTarget(id = "local", name = "Local (save only)")

/**
 * Scheduled jobs and their runs. A run is an ordinary session, so opening one is just opening a
 * chat; the job page is where those chats are reached from.
 */
class ScheduledViewModel(
    private val api: CronApi,
    private val connection: GatewayConnection,
) : ViewModel() {

    val name = TextFieldState()
    val prompt = TextFieldState()
    val schedule = TextFieldState()

    private val _state = MutableStateFlow(ScheduledUiState())
    val state: StateFlow<ScheduledUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var runsJob: Job? = null

    /** Counts the times the form opened or closed, so a save that outlives its form can tell. */
    private var editorGeneration = 0

    init {
        observeServerChanges()
    }

    fun bind(gateway: SavedGateway) {
        if (this.gateway == gateway) return
        this.gateway = gateway
        editorGeneration++
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

    fun newJob() {
        name.setTextAndPlaceCursorAtEnd("")
        prompt.setTextAndPlaceCursorAtEnd("")
        schedule.setTextAndPlaceCursorAtEnd("")
        editorGeneration++
        _state.update { it.copy(editor = JobEditor()) }
        loadDeliveryTargets()
    }

    fun editJob() {
        val job = _state.value.openJob ?: return
        name.setTextAndPlaceCursorAtEnd(job.name)
        prompt.setTextAndPlaceCursorAtEnd(job.prompt)
        schedule.setTextAndPlaceCursorAtEnd(job.editableSchedule)
        editorGeneration++
        _state.update { it.copy(editor = JobEditor(jobId = job.id, deliver = job.deliver ?: LOCAL_DELIVERY.id)) }
        loadDeliveryTargets()
    }

    fun closeEditor() {
        editorGeneration++
        _state.update { it.copy(editor = null) }
    }

    fun setSchedule(text: String) = schedule.setTextAndPlaceCursorAtEnd(text)

    fun setDeliver(target: String) = _state.update { state -> state.copy(editor = state.editor?.copy(deliver = target, error = null)) }

    /** Creates the job, or sends an existing job only the fields that changed. */
    fun saveJob() {
        val url = gateway?.gatewayUrl ?: return
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val draft = CronJobDraft(
            name = name.text.toString().trim(),
            prompt = prompt.text.toString().trim(),
            schedule = schedule.text.toString().trim(),
            deliver = editor.deliver,
        )
        val existing = editor.jobId?.let { id -> _state.value.jobs.firstOrNull { it.id == id } }
        val problem = when {
            // Removed elsewhere while the form was open; saving would quietly create it again.
            editor.jobId != null && existing == null -> "This job was deleted on the gateway, so there's nothing to save."
            draft.prompt.isEmpty() -> "Say what the agent should do on each run."
            draft.schedule.isEmpty() && (existing == null || existing.editableSchedule.isNotEmpty()) -> "Say when it should run."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(editor = editor.copy(error = problem)) }
            return
        }
        _state.update { it.copy(editor = editor.copy(saving = true, error = null)) }
        val savedTo = gateway
        val generation = editorGeneration
        viewModelScope.launch {
            val result = if (existing == null) {
                api.create(url, draft)
            } else {
                val changes = buildMap {
                    if (draft.name != existing.name) put("name", draft.name)
                    if (draft.prompt != existing.prompt.trim()) put("prompt", draft.prompt)
                    if (draft.schedule.isNotEmpty() && draft.schedule != existing.editableSchedule) put("schedule", draft.schedule)
                    if (draft.deliver != (existing.deliver ?: LOCAL_DELIVERY.id)) put("deliver", draft.deliver)
                }
                if (changes.isEmpty()) ApiResult.Success(existing) else api.update(url, existing.id, changes)
            }
            // Signed in to a different gateway since; this result belongs to the old one.
            if (gateway != savedTo) return@launch
            // The form was closed or reopened while saving: report the outcome, but leave the page alone.
            val formOpen = generation == editorGeneration
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> {
                        val saved = result.value
                        val known = state.jobs.any { it.id == saved.id }
                        val jobs = if (known) state.jobs.map { if (it.id == saved.id) saved else it } else listOf(saved) + state.jobs
                        val message = if (existing == null) "Job scheduled" else "Job saved"
                        if (formOpen) {
                            state.copy(
                                editor = null,
                                jobs = jobs,
                                openJobId = saved.id,
                                runs = if (state.openJobId == saved.id) state.runs else emptyList(),
                                message = message,
                            )
                        } else {
                            state.copy(jobs = jobs, message = message)
                        }
                    }
                    else -> if (formOpen) {
                        state.copy(
                            editor = state.editor?.copy(saving = false, error = result.errorMessage),
                            sessionExpired = result == ApiResult.SessionExpired,
                        )
                    } else {
                        state.copy(
                            message = listOfNotNull("Couldn't save the job", result.errorMessage).joinToString(": "),
                            sessionExpired = result == ApiResult.SessionExpired,
                        )
                    }
                }
            }
            if (formOpen) (result as? ApiResult.Success)?.value?.let { if (existing == null) loadRuns(it.id) }
        }
    }

    fun askDelete() = _state.update { it.copy(confirmingDelete = it.openJob != null) }

    fun cancelDelete() = _state.update { it.copy(confirmingDelete = false) }

    fun deleteJob() {
        val url = gateway?.gatewayUrl ?: return
        val job = _state.value.openJob ?: return
        _state.update { it.copy(confirmingDelete = false, busy = true) }
        viewModelScope.launch {
            val result = api.delete(url, job.id)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(
                        busy = false,
                        jobs = state.jobs.filterNot { it.id == job.id },
                        openJobId = null,
                        runs = emptyList(),
                        message = "Job deleted",
                    )
                    else -> state.copy(
                        busy = false,
                        message = listOfNotNull("Couldn't delete the job", result.errorMessage).joinToString(": "),
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    /** Platforms the gateway can deliver to; on failure the form still offers local delivery. */
    private fun loadDeliveryTargets() {
        val url = gateway?.gatewayUrl ?: return
        viewModelScope.launch {
            val result = api.deliveryTargets(url)
            if (result is ApiResult.Success && result.value.isNotEmpty()) {
                _state.update { it.copy(deliveryTargets = result.value) }
            }
        }
    }

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

    /**
     * A finished run shows up as a changed session, and a job added, edited or removed anywhere (the
     * agent's cron tool, the CLI, Desktop) as `cron.changed`; refetch so the list and runs stay current.
     */
    @OptIn(FlowPreview::class)
    private fun observeServerChanges() {
        viewModelScope.launch {
            connection.events
                .filter { it.type == "sessions.changed" || it.type == "cron.changed" }
                .debounce(1_000)
                .collect { refresh() }
        }
    }
}
