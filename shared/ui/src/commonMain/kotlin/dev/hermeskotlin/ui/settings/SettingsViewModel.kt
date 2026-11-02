package dev.hermeskotlin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.ConnectionCheck
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.StageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.plugins.NextPromptApi
import kotlinx.coroutines.CancellationException
import dev.hermeskotlin.core.push.PushPluginReply
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.push.PushStatus
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.WallpaperStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who is signed in where, for the Settings account and about sections. */
data class GatewayInfo(val userLabel: String? = null, val version: String? = null)

/** Where the Next Prompt plugin, which Suggested replies needs, stands on the gateway. */
sealed interface SuggestionsPlugin {
    data object Checking : SuggestionsPlugin

    /** The gateway couldn't be asked. */
    data object Unknown : SuggestionsPlugin
    data object Missing : SuggestionsPlugin
    data object Installing : SuggestionsPlugin

    /** Installed, but the gateway serves its routes only once it restarts. */
    data object NeedsRestart : SuggestionsPlugin
    data class Installed(val version: String?) : SuggestionsPlugin {
        /** Before 0.3.1 a gateway serving several profiles kept suggestions where only one could read them. */
        val outdated: Boolean get() = version != null && !PushPluginReply(version = version).versionAtLeast(NextPromptApi.PLUGIN_VERSION)
    }
    data class Failed(val message: String) : SuggestionsPlugin
}

/** "Check connection": the stages done so far, and whether it's still going. */
data class ConnectionCheckState(val results: Map<CheckStage, StageResult> = emptyMap(), val running: Boolean = false)

class SettingsViewModel(
    private val store: SettingsStore,
    private val auth: AuthApi,
    private val probe: GatewayProbe,
    private val connectionCheck: ConnectionCheck,
    private val push: PushSetup,
    private val wallpapers: WallpaperStore,
    private val profilesApi: ProfilesApi,
    private val sessionsApi: SessionsApi,
    private val nextPrompt: NextPromptApi,
) : ViewModel() {

    /** The chat background's encoded image, or null when there's none (or it isn't read yet). */
    val wallpaper: StateFlow<ByteArray?> =
        wallpapers.wallpaper.map { it?.image }.stateIn(viewModelScope, SharingStarted.Eagerly, wallpapers.wallpaper.value?.image)

    fun setWallpaper(image: ByteArray) = wallpapers.set(image)

    fun removeWallpaper() = wallpapers.clear()

    private val _check = MutableStateFlow(ConnectionCheckState())
    val check: StateFlow<ConnectionCheckState> = _check.asStateFlow()
    private var checkJob: Job? = null

    /** Tests server access, sign-in and the live connection one after another. */
    fun runConnectionCheck() {
        val url = bound?.gatewayUrl ?: return
        checkJob?.cancel()
        _check.value = ConnectionCheckState(running = true)
        checkJob = viewModelScope.launch {
            val report = connectionCheck.run(url) { partial -> _check.value = ConnectionCheckState(partial.results, running = true) }
            _check.value = ConnectionCheckState(report.results, running = false)
        }
    }

    val settings: StateFlow<AppSettings> =
        store.settings.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, store.settings.value ?: AppSettings())

    private val _gateway = MutableStateFlow(GatewayInfo())
    val gateway: StateFlow<GatewayInfo> = _gateway.asStateFlow()

    private var bound: SavedGateway? = null
    private var boundProfile: String? = null

    private val _suggestions = MutableStateFlow<SuggestionsPlugin>(SuggestionsPlugin.Checking)

    /** Whether the gateway has the Next Prompt plugin that Suggested replies needs, for the profile the chats use. */
    val suggestions: StateFlow<SuggestionsPlugin> = _suggestions.asStateFlow()
    private var suggestionsJob: Job? = null

    /** [profile] is the one the chats use; Suggested replies looks for its plugin there. */
    fun bind(gateway: SavedGateway, profile: String? = null) {
        if (bound == gateway && boundProfile == profile) return
        val sameGateway = bound == gateway
        bound = gateway
        boundProfile = profile
        checkSuggestions()
        if (sameGateway) return
        _gateway.value = GatewayInfo()
        viewModelScope.launch {
            val me = auth.me(gateway.gatewayUrl) as? ApiResult.Success ?: return@launch
            _gateway.update { it.copy(userLabel = me.value.label) }
        }
        viewModelScope.launch {
            val status = probe.probe(gateway.gatewayUrl) as? ProbeResult.Reachable ?: return@launch
            _gateway.update { it.copy(version = status.status.version) }
        }
    }

    suspend fun callProfiles(gateway: SavedGateway) = profilesApi.roster(gateway.gatewayUrl)

    suspend fun callSessions(gateway: SavedGateway, offset: Int) =
        sessionsApi.list(gateway.gatewayUrl, offset = offset)

    fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    /** Looks again whether the gateway has the plugin and serves it (after a restart, say). */
    fun checkSuggestions() {
        val url = bound?.gatewayUrl ?: return
        val profile = boundProfile
        // An install keeps going on the gateway either way; it looks again for what's bound once it's done.
        if (_suggestions.value == SuggestionsPlugin.Installing) return
        suggestionsJob?.cancel()
        _suggestions.value = SuggestionsPlugin.Checking
        suggestionsJob = viewModelScope.launch { _suggestions.value = lookUpSuggestions(url, profile) }
    }

    private suspend fun lookUpSuggestions(url: GatewayUrl, profile: String?): SuggestionsPlugin {
        val found = nextPrompt.plugin(url, profile) as? ApiResult.Success ?: return SuggestionsPlugin.Unknown
        val plugin = found.value ?: return SuggestionsPlugin.Missing
        // Listed but not served: a gateway before Hermes's hot mount only adds a plugin's routes when it starts.
        if (!nextPrompt.served(url, profile)) return SuggestionsPlugin.NeedsRestart
        return SuggestionsPlugin.Installed(plugin.version)
    }

    /** The user said yes to putting the Next Prompt plugin on the gateway. */
    fun installSuggestions() {
        val url = bound?.gatewayUrl ?: return
        val profile = boundProfile
        if (_suggestions.value == SuggestionsPlugin.Installing) return
        suggestionsJob?.cancel()
        _suggestions.value = SuggestionsPlugin.Installing
        suggestionsJob = viewModelScope.launch {
            val failure = try {
                nextPrompt.install(url, profile)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "The gateway didn't install it."
            }
            val now = lookUpSuggestions(url, profile)
            _suggestions.value = when {
                now is SuggestionsPlugin.Installed || now == SuggestionsPlugin.NeedsRestart -> now
                failure != null -> SuggestionsPlugin.Failed(failure)
                else -> SuggestionsPlugin.Failed("Installed, but the gateway doesn't list it yet. Restart the gateway, then look again.")
            }
            // Settings moved to another gateway or profile meanwhile.
            if (bound?.gatewayUrl != url || boundProfile != profile) checkSuggestions()
        }
    }

    /** Notifications anywhere: where its setup stands on this gateway. */
    val pushStatus: StateFlow<PushStatus> = push.status

    private val _pushTest = MutableStateFlow<Boolean?>(null)
    /** The last "Send a test": true when the gateway sent it, false when it couldn't, null before or while sending. */
    val pushTest: StateFlow<Boolean?> = _pushTest.asStateFlow()

    // Off the main thread: the first time, the phone's push keys are made and wrapped in the keystore.
    fun setPushAnywhere(on: Boolean) {
        _pushTest.value = null
        viewModelScope.launch(Dispatchers.Default) { if (on) push.enable() else push.disable() }
    }

    /** The user asked for the newer plugin Notifications anywhere offers. */
    fun updatePushPlugin() {
        viewModelScope.launch(Dispatchers.Default) { push.updatePlugin() }
    }

    fun sendPushTest() {
        _pushTest.value = null
        viewModelScope.launch(Dispatchers.Default) { _pushTest.value = push.test() }
    }
}
