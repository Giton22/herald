package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.capabilities.HubActionResult
import dev.hermeskotlin.core.capabilities.HubPreview
import dev.hermeskotlin.core.capabilities.HubScan
import dev.hermeskotlin.core.capabilities.HubSkill
import dev.hermeskotlin.core.capabilities.SkillsHubApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The `running` key of the "update all" action. */
const val UPDATE_ALL = "update-all"

/** A skill opened for a closer look; [error] is why its preview didn't load, [scanError] why its scan didn't. */
data class HubDetail(
    val skill: HubSkill,
    val preview: HubPreview? = null,
    val scan: HubScan? = null,
    val error: String? = null,
    val scanError: String? = null,
)

data class SkillsHubUiState(
    val featured: Loadable<HubSkill> = Loadable(),
    val official: Loadable<HubSkill> = Loadable(),
    /** Null while there is no query. */
    val results: Loadable<HubSkill>? = null,
    val timedOut: List<String> = emptyList(),
    /** Identifiers of what the profile has installed from the hub. */
    val installed: Set<String> = emptySet(),
    val detail: HubDetail? = null,
    /** A scan that wants the user's yes before the install goes ahead. */
    val confirm: HubScan? = null,
    /** Actions in flight, keyed by installKey, uninstall name or [UPDATE_ALL]. */
    val running: Set<String> = emptySet(),
    val message: String? = null,
    val unsupported: Boolean = false,
    val sessionExpired: Boolean = false,
)

interface SkillsHubActions {
    fun refresh()
    fun search(query: String)
    fun open(skill: HubSkill)
    fun closeDetail()
    fun install(skill: HubSkill)
    fun confirmInstall()
    fun cancelConfirm()
    fun uninstall(name: String)
    fun updateAll()
    fun dismissMessage()
}

/** Browsing, searching, scanning and installing skills from the hub, for the bound gateway and profile. */
class SkillsHubViewModel(private val api: SkillsHubApi) : ViewModel(), SkillsHubActions {

    val query = TextFieldState()

    /** Called after any action that changed the profile's skills, so their list can refetch. */
    var onSkillsChanged: () -> Unit = {}

    private val _state = MutableStateFlow(SkillsHubUiState())
    val state: StateFlow<SkillsHubUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var profile: String? = null
    private var binding: Job = newBinding()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var pending: HubSkill? = null
    private var hubOpen = false

    /**
     * Counts installs and uninstalls this view model finished. A list read that started before one
     * of them may not show it yet, so its installed map doesn't replace the marks then.
     */
    private var localChanges = 0

    /** Points the hub at [gateway] and [profile], dropping what the last binding loaded. Loads nothing: see [openHub]. */
    fun bind(gateway: SavedGateway, profile: String?) {
        if (this.gateway == gateway && this.profile == profile) return
        this.gateway = gateway
        this.profile = profile
        binding.cancel()
        binding = newBinding()
        searchJob = null
        pending = null
        query.clearText()
        _state.value = SkillsHubUiState()
    }

    /** The hub page opened: loads the lists afresh, so a failed or stale load never outlives a visit. */
    fun openHub() {
        hubOpen = true
        refresh()
    }

    /** The hub page closed: its sheets don't wait for the next visit. */
    fun closeHub() {
        hubOpen = false
        pending = null
        _state.update { it.copy(detail = null, confirm = null) }
    }

    /** Loads the featured and official lists, again if they were loaded before. */
    override fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        loadJob?.cancel()
        val seen = localChanges
        _state.update { it.copy(featured = Loadable(items = it.featured.items, loading = true), official = Loadable(items = it.official.items, loading = true), unsupported = false) }
        loadJob = viewModelScope.launch(binding) {
            launch {
                when (val r = api.sources(url, profile)) {
                    // The gateway's installed map is all of the profile's hub skills (the official
                    // list's marks are a part of it), so it replaces what an earlier visit marked.
                    is ApiResult.Success -> _state.update {
                        it.copy(featured = Loadable(items = r.value.featured), installed = if (localChanges == seen) r.value.installed.keys else it.installed)
                    }
                    else -> _state.update {
                        it.copy(
                            featured = Loadable(error = r.errorMessage),
                            unsupported = (r as? ApiResult.Failed)?.status == 404,
                            sessionExpired = it.sessionExpired || r == ApiResult.SessionExpired,
                        )
                    }
                }
            }
            launch {
                when (val r = api.official(url, profile)) {
                    is ApiResult.Success -> _state.update {
                        it.copy(official = Loadable(items = r.value), installed = it.installed + r.value.filter { s -> s.installed }.map { s -> s.installKey })
                    }
                    else -> _state.update {
                        it.copy(official = Loadable(error = r.errorMessage), sessionExpired = it.sessionExpired || r == ApiResult.SessionExpired)
                    }
                }
            }
        }
    }

    override fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(results = null, timedOut = emptyList()) }
            return
        }
        val url = gateway?.gatewayUrl ?: return
        searchJob = viewModelScope.launch(binding) {
            delay(SEARCH_PAUSE_MS)
            _state.update { it.copy(results = Loadable(items = it.results?.items, loading = true)) }
            val seen = localChanges
            when (val r = api.search(url, profile, q)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        results = Loadable(items = r.value.results),
                        timedOut = r.value.timedOut,
                        installed = if (localChanges == seen) r.value.installed.keys else it.installed,
                    )
                }
                else -> _state.update {
                    it.copy(results = Loadable(error = r.errorMessage), sessionExpired = it.sessionExpired || r == ApiResult.SessionExpired)
                }
            }
        }
    }

    override fun open(skill: HubSkill) {
        val url = gateway?.gatewayUrl ?: return
        _state.update { it.copy(detail = HubDetail(skill)) }
        val id = skill.installKey
        viewModelScope.launch(binding) {
            val r = api.preview(url, profile, id)
            _state.update { s ->
                if (s.detail?.skill?.installKey != id) s
                else s.copy(detail = s.detail.run { if (r is ApiResult.Success) copy(preview = r.value) else copy(error = r.errorMessage) })
            }
        }
        viewModelScope.launch(binding) {
            val r = api.scan(url, profile, id)
            _state.update { s ->
                when {
                    s.detail?.skill?.installKey != id -> s
                    r is ApiResult.Success -> s.copy(detail = s.detail.copy(scan = r.value))
                    // Install scans again when this one failed.
                    else -> s.copy(
                        detail = s.detail.copy(scanError = "Couldn't scan: ${r.errorMessage}"),
                        sessionExpired = s.sessionExpired || r == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    override fun closeDetail() = _state.update { it.copy(detail = null) }

    override fun install(skill: HubSkill) {
        val url = gateway?.gatewayUrl ?: return
        val key = skill.installKey
        if (key in _state.value.running) return
        setRunning(key)
        if (skill.isOfficial) return runInstall(skill)
        // The open sheet scanned this skill already; that verdict is as fresh as a second scan.
        val scanned = _state.value.detail?.takeIf { it.skill.installKey == key }?.scan
        viewModelScope.launch(binding) {
            when (val r = scanned?.let { ApiResult.Success(it) } ?: api.scan(url, profile, key)) {
                is ApiResult.Success -> {
                    val scan = r.value
                    // A fresh scan replaces a failed one on the open page.
                    if (scanned == null) _state.update { s ->
                        val d = s.detail?.takeIf { it.skill.installKey == key } ?: return@update s
                        s.copy(detail = d.copy(scan = scan, scanError = null))
                    }
                    when {
                        scan.policy == "block" -> {
                            stopRunning(key)
                            showScan(skill, scan)
                        }
                        scan.verdict == "safe" -> runInstall(skill)
                        else -> {
                            stopRunning(key)
                            pending = skill
                            _state.update { it.copy(confirm = scan) }
                        }
                    }
                }
                else -> {
                    stopRunning(key)
                    _state.update {
                        it.copy(message = "Couldn't check ${skill.name}: ${r.errorMessage}", sessionExpired = it.sessionExpired || r == ApiResult.SessionExpired)
                    }
                }
            }
        }
    }

    override fun confirmInstall() {
        val skill = pending ?: return
        pending = null
        _state.update { it.copy(confirm = null) }
        if (skill.installKey in _state.value.running) return
        setRunning(skill.installKey)
        runInstall(skill)
    }

    override fun cancelConfirm() {
        pending = null
        _state.update { it.copy(confirm = null) }
    }

    override fun uninstall(name: String) {
        val url = gateway?.gatewayUrl ?: return
        if (name in _state.value.running) return
        setRunning(name)
        viewModelScope.launch(binding) {
            finish(name, api.uninstall(url, profile, name), failure = "Couldn't remove $name") {
                localChanges++
                _state.update { s -> s.copy(installed = s.installed.filterNot { it == name || it.endsWith("/$name") }.toSet(), message = "Removed $name. $APPLIES_NEXT_CHAT") }
            }
        }
    }

    override fun updateAll() {
        val url = gateway?.gatewayUrl ?: return
        if (UPDATE_ALL in _state.value.running) return
        setRunning(UPDATE_ALL)
        viewModelScope.launch(binding) {
            finish(UPDATE_ALL, api.updateAll(url, profile), failure = "Couldn't update skills") {
                _state.update { it.copy(message = "Skills updated. $APPLIES_NEXT_CHAT") }
            }
        }
    }

    override fun dismissMessage() = _state.update { it.copy(message = null) }

    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }

    private fun runInstall(skill: HubSkill) {
        val url = gateway?.gatewayUrl ?: return
        val key = skill.installKey
        viewModelScope.launch(binding) {
            val result = api.install(url, profile, key)
            if (result is ApiResult.Success && result.value is HubActionResult.Blocked) {
                stopRunning(key)
                _state.update { it.copy(message = "${skill.name} was blocked by the security scan.") }
                when (val scan = api.scan(url, profile, key)) {
                    is ApiResult.Success -> showScan(skill, scan.value)
                    // Only the sheet still open on this skill gets the error; the Blocked message already told them.
                    else -> _state.update { s ->
                        val d = s.detail?.takeIf { it.skill.installKey == key }
                        s.copy(
                            detail = d?.copy(error = scan.errorMessage) ?: s.detail,
                            sessionExpired = s.sessionExpired || scan == ApiResult.SessionExpired,
                        )
                    }
                }
                return@launch
            }
            finish(key, result, failure = "Couldn't install ${skill.name}") {
                localChanges++
                _state.update { it.copy(installed = it.installed + key, message = "Installed ${skill.name}. $APPLIES_NEXT_CHAT") }
            }
        }
    }

    /** Ends the action on [key]: [onDone] for a success, otherwise a message with the last log line or the error. */
    private fun finish(key: String, result: ApiResult<HubActionResult>, failure: String, onDone: () -> Unit) {
        stopRunning(key)
        when (result) {
            is ApiResult.Success -> when (val r = result.value) {
                is HubActionResult.Done -> {
                    onDone()
                    onSkillsChanged()
                }
                // Not a failure: it may still finish, so the lists are read again and Install is free again.
                is HubActionResult.StillRunning -> {
                    _state.update { it.copy(message = r.lines.firstOrNull()) }
                    onSkillsChanged()
                }
                else -> _state.update { it.copy(message = listOfNotNull(failure, r.lines.lastOrNull { l -> l.isNotBlank() }).joinToString(": ")) }
            }
            else -> _state.update {
                it.copy(
                    message = listOfNotNull(failure, result.errorMessage).joinToString(": "),
                    sessionExpired = it.sessionExpired || result == ApiResult.SessionExpired,
                )
            }
        }
    }

    /** Opens the sheet on [scan] only while the hub page is up; otherwise the banner's "View scan" leaves it to the next visit. */
    private fun showScan(skill: HubSkill, scan: HubScan) = _state.update { s ->
        if (!hubOpen) return@update s
        val keep = s.detail?.takeIf { it.skill.installKey == skill.installKey }
        s.copy(detail = (keep ?: HubDetail(skill)).copy(scan = scan))
    }

    private fun setRunning(key: String) = _state.update { it.copy(running = it.running + key) }

    private fun stopRunning(key: String) = _state.update { it.copy(running = it.running - key) }

    private fun newBinding(): Job = SupervisorJob(viewModelScope.coroutineContext[Job])

    private companion object {
        const val SEARCH_PAUSE_MS = 400L
        const val APPLIES_NEXT_CHAT = "It applies from the next chat."
    }
}
