package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.config.ConfigApi
import dev.hermeskotlin.core.config.ConfigEntry
import dev.hermeskotlin.core.config.ConfigSchema
import dev.hermeskotlin.core.config.ConfigSection
import dev.hermeskotlin.core.config.at
import dev.hermeskotlin.core.config.clearsToolsets
import dev.hermeskotlin.core.config.configPatch
import dev.hermeskotlin.core.config.sameConfigValue
import dev.hermeskotlin.core.config.with
import dev.hermeskotlin.core.config.without
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import dev.hermeskotlin.ui.resources.*

/** Where the Config page is: its sections, one section, every schema category, or the YAML file. */
sealed interface ConfigPlace {
    data object Overview : ConfigPlace
    data object AllSettings : ConfigPlace
    data class Section(val section: ConfigSection) : ConfigPlace
    data object Yaml : ConfigPlace
}

/** The config.yaml editor; its text lives in [ConfigViewModel.yaml]. */
data class YamlState(
    val path: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    /** The text as loaded, to tell whether anything was changed. */
    val loaded: String? = null,
)

data class ConfigUiState(
    val schema: ConfigSchema? = null,
    val values: JsonObject? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val view: ConfigPlace = ConfigPlace.Overview,
    /** Keys with a save in flight. */
    val saving: Set<String> = emptySet(),
    /** A change waiting for a yes: emptying the toolsets. */
    val confirm: Pair<ConfigEntry, JsonElement>? = null,
    /** Leaving the file with changes not saved waits for a yes. */
    val confirmDiscard: Boolean = false,
    val yaml: YamlState? = null,
    val message: Said? = null,
    val sessionExpired: Boolean = false,
)

/**
 * Hermes's own settings for one profile. A change shows at once, is saved as a patch holding just that key,
 * and is put back with the reason if the gateway refuses it. The whole file can be edited as YAML too.
 */
class ConfigViewModel(private val api: ConfigApi) : ViewModel(), ConfigActions {

    val query = TextFieldState()
    val yaml = TextFieldState()

    private val _state = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var profile: String? = null

    /** Parent of every call for the bound gateway and profile; cancelled on a switch so a late answer can't land in the next profile's page. */
    private var binding: Job = newBinding()

    /** Counts reloads of the values, so only the newest one's answer lands. */
    private var reloads = 0

    fun bind(gateway: SavedGateway, profile: String?) {
        if (this.gateway == gateway && this.profile == profile) {
            val state = _state.value
            when {
                // Opened again after a load that failed: try once more rather than show the old error.
                state.schema == null || state.values == null -> if (!state.loading) refresh()
                // Settings may have changed elsewhere since.
                state.saving.isEmpty() -> viewModelScope.launch(binding) { reloadValues() }
            }
            return
        }
        this.gateway = gateway
        this.profile = profile
        binding.cancel()
        binding = newBinding()
        _state.value = ConfigUiState()
        query.clearText()
        refresh()
    }

    /** Loads the schema and the values together; the page needs both. */
    override fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        _state.update { it.copy(loading = true, error = null) }
        val reload = ++reloads
        viewModelScope.launch(binding) {
            val schema = async { api.schema(url, profile) }
            val values = async { api.values(url, profile) }
            val s = schema.await()
            val v = values.await()
            _state.update { state ->
                if (s is ApiResult.Success && v is ApiResult.Success) {
                    // A later read, or a save in flight, holds newer values than this one.
                    val fresh = state.values == null || (reload == reloads && state.saving.isEmpty())
                    state.copy(schema = s.value, values = if (fresh) v.value else state.values, loading = false)
                } else {
                    val failed = if (s !is ApiResult.Success) s else v
                    state.copy(
                        loading = false,
                        error = failed.errorMessage,
                        // With the page already showing, the error page doesn't: say so in the banner.
                        message = if (state.schema != null && state.values != null) said(Res.string.sessions_config_refresh_failed, detail = failed.errorMessage) else state.message,
                        sessionExpired = failed == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    override fun open(view: ConfigPlace) {
        _state.update { it.copy(view = view) }
        if (view == ConfigPlace.Yaml) loadYaml()
    }

    /** One step up: a category back to All settings, everything else to the sections. The file asks first if it was changed. */
    override fun back(): Boolean {
        val view = _state.value.view
        val loaded = _state.value.yaml?.loaded
        if (view == ConfigPlace.Yaml && loaded != null && yaml.text.toString() != loaded) {
            _state.update { it.copy(confirmDiscard = true) }
            return true
        }
        return leave()
    }

    override fun discardYaml() {
        _state.update { it.copy(confirmDiscard = false) }
        leave()
    }

    override fun keepEditing() = _state.update { it.copy(confirmDiscard = false) }

    private fun leave(): Boolean {
        val view = _state.value.view
        val up = when {
            view is ConfigPlace.Section && view.section.category != null -> ConfigPlace.AllSettings
            view == ConfigPlace.Overview -> return false
            else -> ConfigPlace.Overview
        }
        _state.update { it.copy(view = up, yaml = if (view == ConfigPlace.Yaml) null else it.yaml) }
        return true
    }

    override fun set(entry: ConfigEntry, value: JsonElement) {
        if (sameConfigValue(entry.value, value)) return
        if (clearsToolsets(entry, value)) {
            _state.update { it.copy(confirm = entry to value) }
            return
        }
        save(entry, value)
    }

    override fun confirmChange() {
        val (entry, value) = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        save(entry, value)
    }

    override fun cancelChange() = _state.update { it.copy(confirm = null) }

    private fun save(entry: ConfigEntry, value: JsonElement) {
        val url = gateway?.gatewayUrl ?: return
        // What the page holds now: the entry may be from before a reload.
        val before = _state.value.values?.at(entry.key)
        _state.update { it.copy(values = it.values?.with(entry.key, value), saving = it.saving + entry.key) }
        viewModelScope.launch(binding) {
            val result = api.save(url, profile, configPatch(entry.key, value))
            _state.update { state ->
                if (result is ApiResult.Success) {
                    state.copy(saving = state.saving - entry.key)
                } else {
                    state.copy(
                        // Put back what was there, unless a later change has replaced this one.
                        values = state.values?.takeIf { it.at(entry.key) != value }
                            ?: state.values?.let { if (before == null) it.without(entry.key) else it.with(entry.key, before) },
                        saving = state.saving - entry.key,
                        message = said(Res.string.sessions_config_save_failed, entry.label, detail = result.errorMessage),
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
            // The gateway may have settled more than the one key (a new model brings its provider), so read back once quiet.
            if (result is ApiResult.Success && _state.value.saving.isEmpty()) reloadValues()
        }
    }

    private suspend fun reloadValues() {
        val url = gateway?.gatewayUrl ?: return
        val reload = ++reloads
        val result = api.values(url, profile)
        // A later reload read the file after this one did, maybe after another save: this answer is stale.
        if (result is ApiResult.Success && reload == reloads) _state.update { if (it.saving.isEmpty()) it.copy(values = result.value) else it }
    }

    private fun loadYaml() {
        val url = gateway?.gatewayUrl ?: return
        _state.update { it.copy(yaml = YamlState()) }
        viewModelScope.launch(binding) {
            val result = api.raw(url, profile)
            if (result is ApiResult.Success) {
                // Opens at the top, where a reader begins.
                yaml.edit {
                    replace(0, length, result.value.yaml)
                    selection = TextRange.Zero
                }
            }
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(yaml = YamlState(path = result.value.path, loading = false, loaded = result.value.yaml))
                    else -> state.copy(
                        yaml = YamlState(loading = false, error = result.errorMessage),
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    /** Replaces the file with the editor's text; the page's values are read again afterwards. */
    override fun saveYaml() {
        val url = gateway?.gatewayUrl ?: return
        val current = _state.value.yaml ?: return
        if (current.loading || current.saving) return
        val text = yaml.text.toString()
        _state.update { it.copy(yaml = current.copy(saving = true, error = null)) }
        viewModelScope.launch(binding) {
            val result = api.saveRaw(url, profile, text)
            _state.update { state ->
                val editor = state.yaml ?: return@update state
                if (result is ApiResult.Success) {
                    state.copy(yaml = editor.copy(saving = false, loaded = text), message = said(Res.string.sessions_config_yaml_saved))
                } else {
                    state.copy(yaml = editor.copy(saving = false, error = result.errorMessage), sessionExpired = result == ApiResult.SessionExpired)
                }
            }
            if (result is ApiResult.Success) reloadValues()
        }
    }

    override fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Clears the expiry flag once a screen has acted on it; the view model outlives the screen. */
    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }

    private fun newBinding(): Job = SupervisorJob(viewModelScope.coroutineContext[Job])
}
