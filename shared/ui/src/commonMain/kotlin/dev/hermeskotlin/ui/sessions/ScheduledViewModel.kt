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
import dev.hermeskotlin.core.cron.Routines
import dev.hermeskotlin.core.cron.isRoutineOf
import dev.hermeskotlin.core.cron.routineTitle
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

/** The bot whose routines a page shows: its profile and the name it goes by. */
data class RoutineOwner(val profile: String, val label: String)

internal val LOCAL_DELIVERY = DeliveryTarget(id = "local", name = "Local (save only)")

/**
 * Scheduled jobs and their runs. A run is an ordinary session, so opening one is just opening a
 * chat; the job page is where those chats are reached from. Bound to a [RoutineOwner], it is that
 * bot's Routines instead: only its jobs, and new ones made in its own cron store, named and delivered
 * the way Bot Mode's are ([Routines]).
 */
class ScheduledViewModel(
    private val api: CronApi,
    private val connection: GatewayConnection,
) : ViewModel(), ScheduledActions {

    val name = TextFieldState()
    val prompt = TextFieldState()
    val schedule = TextFieldState()

    private val _state = MutableStateFlow(ScheduledUiState())
    val state: StateFlow<ScheduledUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var runsJob: Job? = null

    /** The bot whose routines these are; null for every scheduled job. */
    private var owner: RoutineOwner? = null

    /** Counts the times the form opened or closed, so a save that outlives its form can tell. */
    private var editorGeneration = 0

    init {
        observeServerChanges()
    }

    fun bind(gateway: SavedGateway, owner: RoutineOwner? = null) {
        if (this.gateway == gateway && this.owner == owner) return
        this.gateway = gateway
        this.owner = owner
        editorGeneration++
        _state.value = ScheduledUiState()
        refresh()
    }

    /** Refetches the jobs, and the open job's runs. */
    override fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        val owner = owner
        viewModelScope.launch {
            val result = api.jobs(url)
            if (this@ScheduledViewModel.owner != owner) return@launch
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(
                        jobs = result.value.filter { owner == null || it.isRoutineOf(owner.profile) },
                        loading = false,
                        error = null,
                    )
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

    override fun openJob(jobId: String) {
        _state.update { it.copy(openJobId = jobId, runs = emptyList(), runsError = null) }
        loadRuns(jobId)
    }

    override fun closeJob() {
        runsJob?.cancel()
        _state.update { it.copy(openJobId = null, runs = emptyList(), runsLoading = false, runsError = null) }
    }

    override fun togglePaused() {
        val job = _state.value.openJob ?: return
        act(if (job.paused) "Couldn't resume the job" else "Couldn't pause the job") { url ->
            if (job.paused) api.resume(url, job.id, job.profile) else api.pause(url, job.id, job.profile)
        }
    }

    override fun runNow() {
        val job = _state.value.openJob ?: return
        act("Couldn't start a run", success = "Run started") { url -> api.trigger(url, job.id, job.profile) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Clears the expiry flag once a screen has acted on it; the view model outlives the screen, so
     *  leaving it set would bounce the next mount after a fresh sign-in. */
    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }

    override fun newJob() {
        name.setTextAndPlaceCursorAtEnd("")
        prompt.setTextAndPlaceCursorAtEnd("")
        schedule.setTextAndPlaceCursorAtEnd("")
        editorGeneration++
        // A bot's routine reports to the bot by default, which reads it and answers there.
        _state.update { it.copy(editor = JobEditor(deliver = if (owner != null) Routines.BOT_CHAT_DELIVERY else LOCAL_DELIVERY.id)) }
        loadDeliveryTargets()
    }

    override fun editJob() {
        val job = _state.value.openJob ?: return
        // A routine's tag isn't the user's to edit: it's put back on save.
        name.setTextAndPlaceCursorAtEnd(if (Routines.taggedBot(job.name) != null) job.routineTitle else job.name)
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

    fun setDeliver(target: String) = _state.update { state -> state.copy(editor = state.editor?.copy(deliver = target, error = null)) }

    /** Creates the job, or sends an existing job only the fields that changed. */
    fun saveJob() {
        val url = gateway?.gatewayUrl ?: return
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val existing = editor.jobId?.let { id -> _state.value.jobs.firstOrNull { it.id == id } }
        val title = name.text.toString().trim()
        // Whose routine it is, when it's one: kept from the job, or this page's bot for a new one.
        val routineOf = existing?.let { Routines.taggedBot(it.name) } ?: owner?.profile?.takeIf { existing == null }
        val draft = CronJobDraft(
            name = if (routineOf != null && title.isNotEmpty()) Routines.name(routineOf, title) else title,
            prompt = prompt.text.toString().trim(),
            schedule = schedule.text.toString().trim(),
            deliver = editor.deliver,
        )
        val problem = when {
            // Removed elsewhere while the form was open; saving would quietly create it again.
            editor.jobId != null && existing == null -> "This job was deleted on the gateway, so there's nothing to save."
            routineOf != null && title.isEmpty() -> "Give the routine a name."
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
        val owner = owner
        viewModelScope.launch {
            val result = if (existing == null) {
                // A bot's routine goes in the bot's own store, so it runs as the bot.
                api.create(url, draft, profile = owner?.profile).withProfile(owner?.profile)
            } else {
                val changes = buildMap {
                    if (draft.name != existing.name) put("name", draft.name)
                    if (draft.prompt != existing.prompt.trim()) put("prompt", draft.prompt)
                    if (draft.schedule.isNotEmpty() && draft.schedule != existing.editableSchedule) put("schedule", draft.schedule)
                    if (draft.deliver != (existing.deliver ?: LOCAL_DELIVERY.id)) put("deliver", draft.deliver)
                }
                if (changes.isEmpty()) ApiResult.Success(existing) else api.update(url, existing.id, changes, existing.profile).withProfile(existing.profile)
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
                        val message = "${noun(owner)} ${if (existing == null) "scheduled" else "saved"}"
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

    override fun askDelete() = _state.update { it.copy(confirmingDelete = it.openJob != null) }

    override fun cancelDelete() = _state.update { it.copy(confirmingDelete = false) }

    override fun deleteJob() {
        val url = gateway?.gatewayUrl ?: return
        val job = _state.value.openJob ?: return
        _state.update { it.copy(confirmingDelete = false, busy = true) }
        viewModelScope.launch {
            val result = api.delete(url, job.id, job.profile)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(
                        busy = false,
                        jobs = state.jobs.filterNot { it.id == job.id },
                        openJobId = null,
                        runs = emptyList(),
                        message = "${noun(owner)} deleted",
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

    /**
     * Places the job's replies can go; on failure the form still offers local delivery. A bot's routine
     * can also report into the bot's own chat, first since that's the point of having one.
     */
    private fun loadDeliveryTargets() {
        val url = gateway?.gatewayUrl ?: return
        val owner = owner
        val botChat = owner?.let { DeliveryTarget(id = Routines.BOT_CHAT_DELIVERY, name = "${it.label}'s chat") }
        _state.update { it.copy(deliveryTargets = listOfNotNull(botChat) + it.deliveryTargets.filterNot { t -> t.id == Routines.BOT_CHAT_DELIVERY }) }
        viewModelScope.launch {
            val result = api.deliveryTargets(url, owner?.profile)
            if (result is ApiResult.Success && result.value.isNotEmpty()) {
                // The gateway lists every bot's chat too (`bot-chat:<profile>`); this bot's own is the one above.
                val others = result.value.filterNot { owner != null && it.id.equals("${Routines.BOT_CHAT_DELIVERY}:${owner.profile}", ignoreCase = true) }
                _state.update { it.copy(deliveryTargets = listOfNotNull(botChat) + others) }
            }
        }
    }

    /** Calls a job action and swaps in the job record the server returns. */
    private fun act(failure: String, success: String? = null, call: suspend (GatewayUrl) -> ApiResult<CronJob>) {
        val url = gateway?.gatewayUrl ?: return
        if (_state.value.busy) return
        val before = _state.value.openJob
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = call(url).withProfile(before?.profile)
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
        val profile = _state.value.jobs.firstOrNull { it.id == jobId }?.profile
        runsJob?.cancel()
        _state.update { it.copy(runsLoading = it.runs.isEmpty()) }
        runsJob = viewModelScope.launch {
            val result = api.runs(url, jobId, profile = profile)
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

/** What the page calls a job: a bot's are its routines. */
private fun noun(owner: RoutineOwner?): String = if (owner != null) "Routine" else "Job"

/**
 * Only the cross-profile list tags a job with its profile; a job sent back from a create or an action
 * isn't, so it keeps the profile it's known to be in, or a routine would drop out of its bot's list.
 */
private fun ApiResult<CronJob>.withProfile(profile: String?): ApiResult<CronJob> =
    if (this is ApiResult.Success && value.profile == null && profile != null) ApiResult.Success(value.copy(profile = profile)) else this
