package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.models.ModelCatalog
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.chat.ModelSwitch
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.slash.SlashCatalog
import dev.hermeskotlin.core.slash.SlashCommand
import dev.hermeskotlin.core.slash.SlashKind
import dev.hermeskotlin.core.slash.SlashRoute
import dev.hermeskotlin.core.slash.SlashSuggestion
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The model sheet: the catalog once loaded, and a pricey pick waiting for a yes. */
data class ModelPickerState(
    val catalog: ModelCatalog? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val confirm: PendingSwitch? = null,
)

data class PendingSwitch(val model: ModelOption, val message: String)

/** Something a slash command asks of the screen around the chat. */
sealed interface ChatRequest {
    data object NewChat : ChatRequest
    data object PickModel : ChatRequest
    data object BrowseSessions : ChatRequest
    data class OpenChat(val storedSessionId: String, val title: String?) : ChatRequest

    /** [profile] null is the gateway's launch profile. */
    data class SwitchProfile(val profile: String?) : ChatRequest
}

/**
 * Identifies what the chat screen shows: a stored session, or a new chat (`storedSessionId == null`),
 * in [profile] (null: the gateway's launch profile).
 */
data class ChatTarget(
    val gateway: SavedGateway,
    val storedSessionId: String?,
    val title: String?,
    val nonce: Long = 0,
    val profile: String? = null,
)

/**
 * Shows the [ChatSession] the app-wide [ChatHost] has open; opening another target replaces it.
 * Remembers the open chat in [LastChatStore] so the next launch returns to it: a stored session once
 * it has messages, or nothing while a new chat is still empty.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(
    connection: GatewayConnection,
    private val host: ChatHost,
    private val lastChats: LastChatStore,
    private val models: ModelsApi,
    private val media: MediaApi,
    private val slashApi: SlashApi,
    private val sessions: SessionsApi,
    private val profiles: ProfilesApi,
    private val settings: SettingsStore,
) : ViewModel() {

    val composer = TextFieldState()
    val connectionState: StateFlow<ConnectionState> = connection.state

    private var target: ChatTarget? = null
    private val session = MutableStateFlow<ChatSession?>(null)

    val state: StateFlow<ChatState> = session
        .flatMapLatest { it?.state ?: flowOf(ChatState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatState())

    private val _picker = MutableStateFlow(ModelPickerState())
    private var loadJob: Job? = null
    val picker: StateFlow<ModelPickerState> = _picker.asStateFlow()

    private val _attachments = MutableStateFlow<List<OutgoingAttachment>>(emptyList())

    /** Files waiting in the composer for the next send. */
    val attachments: StateFlow<List<OutgoingAttachment>> = _attachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    private val _requests = Channel<ChatRequest>(Channel.BUFFERED)

    /** Commands the screen answers: a new chat, the model sheet, the sessions list. */
    val requests: Flow<ChatRequest> = _requests.receiveAsFlow()

    /** The commands and skills of the open chat's profile; fetched once per chat, on the first `/`. */
    private var catalog: SlashCatalog? = null
    private var catalogFor: ChatTarget? = null

    /** Rows for the `/` list above the composer; empty hides it. */
    val suggestions: StateFlow<List<SlashSuggestion>> = snapshotFlow { composer.text.toString() }
        .map { it.takeIf(::isSlashQuery) }
        .distinctUntilChanged()
        .debounce { if (it == null || it == "/") 0 else COMPLETION_DEBOUNCE_MS }
        .mapLatest { query -> query?.let { completions(it) }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // The catalog names the default model, which a new chat shows until it has its own.
        viewModelScope.launch {
            connection.state.collect { if (it is ConnectionState.Connected && _picker.value.catalog == null) loadModels() }
        }
        viewModelScope.launch {
            state
                .map { chat -> chat.storedSessionId?.takeIf { chat.hasConversation }?.let { LastChat(it, chat.title) } }
                // Distinct before dropping nulls, so returning to the same chat after a new one saves it again.
                .distinctUntilChanged()
                .filterNotNull()
                .collect { last -> target?.let { lastChats.set(it.gateway.gatewayUrl, last, it.profile) } }
        }
    }

    fun open(target: ChatTarget) {
        if (this.target == target) return
        this.target = target
        composer.clearText()
        _attachments.value = emptyList()
        _attachmentError.value = null
        if (target.storedSessionId == null) viewModelScope.launch { lastChats.set(target.gateway.gatewayUrl, null, target.profile) }
        session.value = host.open(target.gateway.gatewayUrl, target.storedSessionId, target.title, target.profile)
        // The catalog marks the previous chat's model; a new chat must show the profile default instead.
        _picker.update { it.copy(catalog = null, confirm = null) }
        if (connectionState.value is ConnectionState.Connected) loadModels()
    }

    /** Refreshes the catalog for the open chat (its current model marked). */
    fun loadModels() {
        // A load still running belongs to whatever chat was open when it started.
        loadJob?.cancel()
        _picker.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                // Without a live session the catalog marks the default of the chat's profile.
                val catalog = models.options(session.value?.state?.value?.runtimeSessionId, target?.profile)
                _picker.update { it.copy(catalog = catalog, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _picker.update { it.copy(loading = false, error = e.message ?: "Couldn't load the models.") }
            }
        }
    }

    fun selectModel(model: ModelOption, confirmed: Boolean = false) {
        val chat = session.value ?: return
        _picker.update { it.copy(confirm = null) }
        viewModelScope.launch {
            val result = chat.setModel(model.id, model.provider, confirmed)
            if (result is ModelSwitch.NeedsConfirmation) _picker.update { it.copy(confirm = PendingSwitch(model, result.message)) }
        }
    }

    fun dismissConfirm() = _picker.update { it.copy(confirm = null) }

    fun setReasoningEffort(wire: String) {
        val chat = session.value ?: return
        viewModelScope.launch { chat.setReasoningEffort(wire) }
    }

    fun setFast(on: Boolean) {
        val chat = session.value ?: return
        viewModelScope.launch { chat.setFast(on) }
    }

    private val mediaCache = mutableMapOf<String, ByteArray>()

    /**
     * The bytes behind a picture or file the chat shows: a gateway path (`@image:` in a stored prompt,
     * `MEDIA:` in a reply), a web URL or a `data:` URL. Cached per source; null when it can't be had.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun loadMedia(source: String): ByteArray? {
        mediaCache[source]?.let { return it }
        val result = when {
            source.startsWith("data:") -> runCatching { Base64.decode(source.substringAfter("base64,")) }.getOrNull()
            source.startsWith("http://") || source.startsWith("https://") -> (media.remote(source) as? ApiResult.Success)?.value
            else -> {
                val gateway = target?.gateway?.gatewayUrl ?: return null
                (media.file(gateway, source.removePrefix("file://")) as? ApiResult.Success)?.value
            }
        } ?: return null
        if (mediaCache.size >= MAX_CACHED_MEDIA) mediaCache.remove(mediaCache.keys.first())
        mediaCache[source] = result
        return result
    }

    /** Adds picked files to the composer tray, up to [OutgoingAttachment.MAX_COUNT]. */
    fun addAttachments(picked: List<OutgoingAttachment>) {
        val room = OutgoingAttachment.MAX_COUNT - _attachments.value.size
        if (picked.size > room) showAttachmentError("Up to ${OutgoingAttachment.MAX_COUNT} attachments per message.")
        _attachments.update { it + picked.take(room.coerceAtLeast(0)) }
    }

    fun removeAttachment(id: String) = _attachments.update { tray -> tray.filterNot { it.id == id } }

    fun showAttachmentError(message: String) = _attachmentError.update { message }

    fun dismissAttachmentError() = _attachmentError.update { null }

    /** Puts a picked row in the composer: a command gets a space for its argument, an option is the whole line. */
    fun pickSuggestion(suggestion: SlashSuggestion) {
        composer.setTextAndPlaceCursorAtEnd(if (suggestion.kind == SlashKind.Option) suggestion.text else "${suggestion.text} ")
    }

    private fun isSlashQuery(text: String): Boolean = '\n' !in text && SlashCommand.looksLikeCommand(text) || text == "/"

    private suspend fun completions(query: String): List<SlashSuggestion> = try {
        val runtimeId = session.value?.state?.value?.runtimeSessionId
        val catalog = catalog()
        if (query == "/") {
            catalog?.suggestions.orEmpty().filterNot { SlashRoute.hidden(it.text.removePrefix("/"), catalog) }
        } else {
            slashApi.complete(query, runtimeId).filter { row ->
                if (row.kind == SlashKind.Option) row.text.trim() != query.trim()
                else !SlashRoute.hidden(row.text.removePrefix("/").substringBefore(' '), catalog)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun catalog(): SlashCatalog? {
        val target = target ?: return null
        if (catalogFor == target) catalog?.let { return it }
        return try {
            slashApi.catalog(session.value?.state?.value?.runtimeSessionId, target.profile).also {
                catalog = it
                catalogFor = target
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** A command typed in the composer: answered here when this app has its own control for it, else run on the gateway. */
    private fun runCommand(chat: ChatSession, command: SlashCommand) {
        viewModelScope.launch {
            val arg = command.arg
            suspend fun onGateway() = chat.runCommand(command)?.let { text ->
                if (composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(text)
            }
            when (val route = SlashRoute.of(command.name, catalog())) {
                SlashRoute.NewChat -> _requests.send(ChatRequest.NewChat)
                // Like Desktop: bare opens the picker, `/model <name>` is for the gateway to parse.
                SlashRoute.PickModel -> if (arg.isEmpty()) _requests.send(ChatRequest.PickModel) else onGateway()
                SlashRoute.BrowseSessions -> if (arg.isEmpty()) _requests.send(ChatRequest.BrowseSessions) else resume(chat, arg)
                SlashRoute.Stop -> chat.stopEverything()
                SlashRoute.Help -> chat.showCommandOutput("/help", helpText(catalog()))
                SlashRoute.Compress -> chat.compress(arg)
                SlashRoute.Status -> chat.status()
                SlashRoute.Aside -> chat.askAside(arg)
                SlashRoute.Reasoning -> when (chat.reasoning(arg)) {
                    // The gateway's display words drive this app's own Thinking toggle too.
                    "show" -> settings.update { it.copy(showReasoning = true) }
                    "hide" -> settings.update { it.copy(showReasoning = false) }
                }
                SlashRoute.Yolo -> chat.toggleYolo()
                // Bare `/title` reports the title, which the gateway's command does.
                SlashRoute.Title -> if (arg.isEmpty()) onGateway() else chat.retitle(arg)
                SlashRoute.Branch -> {
                    val count = arg.toIntOrNull()
                    if (arg.isNotEmpty() && (count == null || count < 1)) {
                        chat.showCommandOutput("/branch", "Usage: /branch [how many messages to keep]", failed = true)
                    } else {
                        chat.branch(count)?.let { (id, title) -> _requests.send(ChatRequest.OpenChat(id, title)) }
                    }
                }
                SlashRoute.Profile -> profile(chat, arg)
                SlashRoute.Handoff -> chat.handoff(arg)
                is SlashRoute.Unavailable -> chat.showCommandOutput("/${command.name}", route.message, failed = true)
                SlashRoute.Gateway -> onGateway()
            }
        }
    }

    /** `/resume <words>`: opens the best match among this profile's chats. */
    private suspend fun resume(chat: ChatSession, query: String) {
        val target = target ?: return
        when (val result = sessions.search(target.gateway.gatewayUrl, query, limit = 1, profile = target.profile)) {
            is ApiResult.Success -> result.value.firstOrNull()?.let { _requests.send(ChatRequest.OpenChat(it.id, it.displayTitle)) }
                ?: chat.showCommandOutput("/resume", "No chat matches “$query”.", failed = true)
            else -> chat.showCommandOutput("/resume", result.errorMessage ?: "Couldn't search the chats.", failed = true)
        }
    }

    /** `/profile [name]`: names the current profile, or switches to another (its chats, memory and model). */
    private suspend fun profile(chat: ChatSession, name: String) {
        val target = target ?: return
        val roster = (profiles.roster(target.gateway.gatewayUrl) as? ApiResult.Success)?.value
            ?: return chat.showCommandOutput("/profile", "Couldn't load the gateway's profiles.", failed = true)
        val current = roster.profiles.find { it.name == (target.profile ?: roster.launch) }
        if (name.isEmpty()) {
            val others = roster.profiles.filter { it != current }.map { it.name }
            chat.showCommandOutput(
                "/profile",
                "Profile: ${current?.label ?: target.profile ?: roster.launch}" +
                    if (others.isEmpty()) "" else "\nAlso on this gateway: ${others.joinToString(", ")}",
            )
            return
        }
        val match = roster.profiles.find { it.name.equals(name, ignoreCase = true) }
            ?: return chat.showCommandOutput(
                "/profile",
                "No profile named “$name”. This gateway has: ${roster.profiles.joinToString(", ") { it.name }}",
                failed = true,
            )
        if (match == current) return chat.showCommandOutput("/profile", "Already in ${match.label}.")
        _requests.send(ChatRequest.SwitchProfile(match.name.takeIf { it != roster.launch }))
    }

    private fun helpText(catalog: SlashCatalog?): String {
        val rows = catalog?.suggestions.orEmpty().filterNot { SlashRoute.hidden(it.text.removePrefix("/"), catalog) }
        if (rows.isEmpty()) return "Couldn't load the command list. Type / to try again."
        return rows.groupBy { it.group ?: "Commands" }.entries.joinToString("\n\n") { (group, items) ->
            group + "\n" + items.joinToString("\n") { row -> if (row.description.isBlank()) row.text else "${row.text}  ${row.description}" }
        }
    }

    fun send() {
        val chat = session.value ?: return
        val text = composer.text.toString()
        val attachments = _attachments.value
        if (text.isBlank() && attachments.isEmpty()) return
        val command = SlashCommand.parse(text.trim())
        if (command != null && attachments.isEmpty()) {
            if (command.name.isEmpty()) return
            composer.clearText()
            runCommand(chat, command)
            return
        }
        composer.clearText()
        _attachments.value = emptyList()
        viewModelScope.launch {
            // Give everything back if it never reached the gateway, so nothing typed or picked is lost.
            if (!chat.send(text, attachments)) {
                if (composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(text)
                _attachments.update { attachments + it }
            }
        }
    }

    fun interrupt() {
        val chat = session.value ?: return
        viewModelScope.launch { chat.interrupt() }
    }

    fun answer(request: InputRequest, result: JsonObject) {
        val chat = session.value ?: return
        viewModelScope.launch { chat.answer(request, result) }
    }

    fun retry() = session.value?.retry()

    fun dismissError() = session.value?.dismissError()

    fun showTitle(title: String?) = session.value?.showTitle(title)

    private companion object {
        /** Full-size gateway photos are a few hundred KB each; keep a screenful or two. */
        const val MAX_CACHED_MEDIA = 24

        /** Typing pauses this long before asking the gateway for matches. */
        const val COMPLETION_DEBOUNCE_MS = 120L
    }
}
