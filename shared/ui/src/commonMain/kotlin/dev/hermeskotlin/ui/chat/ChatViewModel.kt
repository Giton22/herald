package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.DraftStore
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
import dev.hermeskotlin.core.chat.SendOutcome
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.ui.pet.PetController
import dev.hermeskotlin.ui.journey.JourneyController
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.ui.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
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
    data object OpenPets : ChatRequest
    data object OpenJourney : ChatRequest
    data object OpenUsage : ChatRequest
    data object OpenProcesses : ChatRequest

    /** `/voice`: the screen asks for the microphone, then starts a voice chat. */
    data object StartVoice : ChatRequest

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
 * it has messages, or nothing while a new chat is still empty. Each chat keeps its own unsent text in
 * [DraftStore] and its picked files in memory, so switching chats loses neither.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(
    connection: GatewayConnection,
    private val host: ChatHost,
    private val lastChats: LastChatStore,
    private val drafts: DraftStore,
    private val models: ModelsApi,
    private val media: MediaApi,
    private val slashApi: SlashApi,
    private val sessions: SessionsApi,
    private val profiles: ProfilesApi,
    private val settings: SettingsStore,
    petApi: PetApi,
    journeyApi: JourneyApi,
    audioApi: AudioApi,
    recorder: VoiceRecorder,
    player: SpeechPlayer,
    appScope: CoroutineScope,
) : ViewModel(), ChatActions {

    /** Dictation and voice chat for the open chat. */
    val voice = VoiceController(audioApi, recorder, player, viewModelScope, appScope)

    /** The profile's pet and its gallery. */
    val pets = PetController(petApi, viewModelScope)

    /** What the profile's agent has learned (`/journey`). */
    val journey = JourneyController(journeyApi, viewModelScope)

    /** Tokens and cost of the open chat. */
    val usage = UsageController(sessions, viewModelScope)

    /** Background processes the agent started in the open chat. */
    val processes = ProcessesController(viewModelScope)

    override val composer = TextFieldState()
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

    /** Files picked in chats that aren't open, by [trayKey]; they live as long as the app does. */
    private val trays = mutableMapOf<String, List<OutgoingAttachment>>()

    /** The chat whose draft the composer holds; null while it's being restored, so nothing is saved over it. */
    private var draftOf: ChatTarget? = null

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
            connection.state.collect {
                if (it !is ConnectionState.Connected) return@collect
                if (_picker.value.catalog == null) loadModels()
                // Another client may have changed the pet while this one was away.
                target?.let { open -> pets.bind(open.profile) }
            }
        }
        viewModelScope.launch {
            state
                .map { chat -> chat.storedSessionId?.takeIf { chat.hasConversation }?.let { LastChat(it, chat.title) } }
                // Distinct before dropping nulls, so returning to the same chat after a new one saves it again.
                .distinctUntilChanged()
                .filterNotNull()
                .collect { last -> target?.let { lastChats.set(it.gateway.gatewayUrl, last, it.profile) } }
        }
        viewModelScope.launch {
            snapshotFlow { composer.text.toString() }
                .debounce(DRAFT_SAVE_DEBOUNCE_MS)
                .collect { text -> draftOf?.let { saveDraft(it, draftChat(it), text) } }
        }
    }

    /** The chat a draft belongs to: its stored session, which a new chat gets with its first reply. */
    private fun draftChat(target: ChatTarget): String? = state.value.storedSessionId ?: target.storedSessionId

    private fun trayKey(target: ChatTarget, chat: String?) =
        "${target.gateway.gatewayUrl}#${target.profile.orEmpty()}#${chat ?: DraftStore.NEW_CHAT}"

    /**
     * Saves [text] as the draft of [target]'s chat. Once a new chat has its stored session, the draft
     * typed before it moves there, so it doesn't come back in the next new chat.
     */
    private suspend fun saveDraft(target: ChatTarget, chat: String?, text: String) {
        if (chat != null && target.storedSessionId == null) drafts.set(target.gateway.gatewayUrl, null, "", target.profile)
        drafts.set(target.gateway.gatewayUrl, chat, text, target.profile)
    }

    /** Puts the open chat's text and files aside before another chat takes the composer. */
    private fun stashDraft() {
        val open = draftOf ?: return
        draftOf = null
        val text = composer.text.toString()
        val chat = draftChat(open)
        val tray = _attachments.value
        if (tray.isEmpty()) trays.remove(trayKey(open, chat)) else trays[trayKey(open, chat)] = tray
        viewModelScope.launch { saveDraft(open, chat, text) }
    }

    private fun restoreDraft(target: ChatTarget) {
        _attachments.value = trays.remove(trayKey(target, target.storedSessionId)).orEmpty()
        viewModelScope.launch {
            val text = drafts.get(target.gateway.gatewayUrl, target.storedSessionId, target.profile)
            if (this@ChatViewModel.target != target) return@launch
            if (text != null && composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(text)
            draftOf = target
        }
    }

    fun open(target: ChatTarget) {
        if (this.target == target) return
        stashDraft()
        this.target = target
        // A voice chat belongs to the chat it started in.
        voice.stopAll()
        composer.clearText()
        restoreDraft(target)
        _attachmentError.value = null
        if (target.storedSessionId == null) viewModelScope.launch { lastChats.set(target.gateway.gatewayUrl, null, target.profile) }
        session.value = host.open(target.gateway.gatewayUrl, target.storedSessionId, target.title, target.profile)
        // The catalog marks the previous chat's model; a new chat must show the profile default instead.
        _picker.update { it.copy(catalog = null, confirm = null) }
        if (connectionState.value is ConnectionState.Connected) {
            loadModels()
            pets.bind(target.profile)
        }
    }

    fun startVoiceChat() {
        val chat = session.value ?: return
        val target = target ?: return
        val pause = (settings.settings.value ?: AppSettings()).voicePause
        voice.startChat(chat, target.gateway.gatewayUrl, target.profile, pause.millis)
    }

    /** Starts dictating into the composer, or finishes the dictation in progress. */
    fun toggleDictation() {
        val target = target ?: return
        if (voice.dictation.value.recording) return voice.finishDictation()
        voice.startDictation(target.gateway.gatewayUrl, target.profile) { text ->
            val current = composer.text.toString()
            composer.setTextAndPlaceCursorAtEnd(if (current.isBlank()) text else "${current.trimEnd()} $text")
        }
    }

    /** A pet just adopted should show, even where this phone had hidden it. */
    fun showPet() {
        if (settings.settings.value?.showPet == false) settings.update { it.copy(showPet = true) }
    }

    /** Opens the journey for the open chat's profile. */
    fun loadJourney() {
        val target = target ?: return
        journey.load(target.gateway.gatewayUrl, target.profile)
    }

    /** Shows the usage sheet (from the chat menu). */
    fun openUsage() {
        viewModelScope.launch { _requests.send(ChatRequest.OpenUsage) }
    }

    /** Shows the background processes sheet (from the chat menu). */
    fun openProcesses() {
        viewModelScope.launch { _requests.send(ChatRequest.OpenProcesses) }
    }

    fun watchProcesses() = processes.start(session.value)

    fun killProcess(process: BackgroundProcess) = processes.kill(session.value, process)

    fun loadUsage() {
        val target = target ?: return
        usage.load(target.gateway.gatewayUrl, state.value.storedSessionId, target.profile, session.value)
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
    override suspend fun loadMedia(source: String): ByteArray? {
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

    override fun removeAttachment(id: String) = _attachments.update { tray -> tray.filterNot { it.id == id } }

    fun showAttachmentError(message: String) = _attachmentError.update { message }

    override fun dismissAttachmentError() = _attachmentError.update { null }

    /** Puts a picked row in the composer: a command gets a space for its argument, an option is the whole line. */
    override fun pickSuggestion(suggestion: SlashSuggestion) {
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
                SlashRoute.Skin -> skin(chat, arg)
                SlashRoute.Journey -> _requests.send(ChatRequest.OpenJourney)
                SlashRoute.Pet -> pet(chat, arg)
                SlashRoute.Voice -> if (arg.lowercase() in setOf("off", "stop")) voice.stopChat() else _requests.send(ChatRequest.StartVoice)
                // `/usage reset` and the like are the gateway's to run.
                SlashRoute.Usage -> if (arg.isEmpty()) _requests.send(ChatRequest.OpenUsage) else onGateway()
                is SlashRoute.Unavailable -> chat.showCommandOutput("/${command.name}", route.message, failed = true)
                SlashRoute.Gateway -> onGateway()
            }
        }
    }

    /** `/skin [light|dark|black|system]`: this app's theme; bare, it steps to the next one. */
    private fun skin(chat: ChatSession, arg: String) {
        val current = settings.settings.value ?: AppSettings()
        val next = when (arg.lowercase()) {
            "" -> when (current.theme) {
                ThemeMode.System -> current.copy(theme = ThemeMode.Light)
                ThemeMode.Light -> current.copy(theme = ThemeMode.Dark)
                ThemeMode.Dark -> current.copy(theme = ThemeMode.System)
            }
            "light" -> current.copy(theme = ThemeMode.Light)
            "dark" -> current.copy(theme = ThemeMode.Dark, pureBlack = false)
            "black", "oled", "amoled" -> current.copy(theme = ThemeMode.Dark, pureBlack = true)
            "system", "auto", "default" -> current.copy(theme = ThemeMode.System)
            else -> return chat.showCommandOutput("/skin", "Themes here: light, dark, black or system.", failed = true)
        }
        settings.update { next }
        val name = when (next.theme) {
            ThemeMode.System -> "matches the system"
            ThemeMode.Light -> "light"
            ThemeMode.Dark -> if (next.pureBlack) "black" else "dark"
        }
        chat.showCommandOutput("/skin", "Theme: $name.")
    }

    /** `/pet [name|off]`: bare opens the gallery; a name adopts that pet; `off` puts it away. */
    private suspend fun pet(chat: ChatSession, arg: String) {
        when (arg.lowercase()) {
            "", "list", "gallery", "browse", "all" -> _requests.send(ChatRequest.OpenPets)
            "off", "hide", "disable", "none" -> try {
                pets.hide()
                chat.showCommandOutput("/pet", "Pet put away. Bring one back with /pet.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat.showCommandOutput("/pet", e.message ?: "Couldn't put the pet away.", failed = true)
            }
            else -> try {
                val name = pets.adopt(arg)
                if (name == null) {
                    chat.showCommandOutput("/pet", "No pet called “$arg”. Type /pet to browse them.", failed = true)
                } else {
                    if (settings.settings.value?.showPet == false) settings.update { it.copy(showPet = true) }
                    chat.showCommandOutput("/pet", "Adopted $name.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat.showCommandOutput("/pet", e.message ?: "Couldn't adopt $arg.", failed = true)
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

    /**
     * Sends what's in the composer. Mid-turn it corrects the running turn, unless [queue] holds it for
     * the next one.
     */
    override fun send(queue: Boolean) {
        val chat = session.value ?: return
        val text = composer.text.toString()
        val attachments = _attachments.value
        if (text.isBlank() && attachments.isEmpty()) return
        val command = SlashCommand.parse(text.trim())
        // Commands run at once either way; they never become a turn to queue.
        if (command != null && attachments.isEmpty()) {
            if (command.name.isEmpty()) return
            composer.clearText()
            runCommand(chat, command)
            return
        }
        composer.clearText()
        _attachments.value = emptyList()
        viewModelScope.launch {
            // Give everything back if it never reached the gateway, so nothing typed or picked is lost. One
            // that may have arrived keeps its bubble to resend from instead, so it isn't in two places.
            if (chat.submit(text, attachments, queue = queue) == SendOutcome.NotSent) {
                giveBack(text)
                _attachments.update { attachments + it }
            }
        }
    }

    override fun interrupt() {
        val chat = session.value ?: return
        viewModelScope.launch {
            // Stop drops what was queued behind the task; hand it back rather than lose it.
            val dropped = chat.interrupt().filter { it.isNotBlank() }
            if (dropped.isEmpty()) return@launch
            val typed = composer.text.toString()
            composer.setTextAndPlaceCursorAtEnd((dropped + typed).filter { it.isNotBlank() }.joinToString("\n\n"))
        }
    }

    override fun editLastPrompt() {
        val chat = session.value ?: return
        val undo = SlashCommand.parse("/undo") ?: return
        viewModelScope.launch {
            val text = chat.runCommand(undo) ?: return@launch
            // Whatever was being typed stays, after the prompt that comes back.
            val typed = composer.text.toString()
            composer.setTextAndPlaceCursorAtEnd(if (typed.isBlank()) text else "$text\n\n$typed")
        }
    }

    override fun branchFrom(key: String) {
        val chat = session.value ?: return
        val messages = state.value.messages
        val index = messages.indexOfFirst { it.key == key }
        if (index < 0) return
        // The gateway keeps the first N user and assistant rows that have text, so count those.
        val count = messages.take(index + 1).count {
            (it is ChatMessage.User && it.text.isNotBlank()) || (it is ChatMessage.Assistant && it.text.isNotBlank())
        }
        viewModelScope.launch {
            chat.branch(count)?.let { (id, title) -> _requests.send(ChatRequest.OpenChat(id, title)) }
        }
    }

    override fun stopSubagent(subagentId: String) {
        val chat = session.value ?: return
        viewModelScope.launch { chat.stopSubagent(subagentId) }
    }

    override fun answer(request: InputRequest, result: JsonObject) {
        val chat = session.value ?: return
        viewModelScope.launch { chat.answer(request, result) }
    }

    override fun checkDelivery(key: String) {
        val chat = session.value ?: return
        viewModelScope.launch { chat.checkDelivery(key) }
    }

    override fun resend(key: String) {
        val chat = session.value ?: return
        viewModelScope.launch {
            // Not sent again after all: the text waits in the composer like any failed send.
            chat.resend(key)?.let(::giveBack)
        }
    }

    override fun editMessage(key: String) {
        session.value?.takeBack(key)?.let(::giveBack)
    }

    /** Puts [text] back in the composer, ahead of anything typed since, which stays. */
    private fun giveBack(text: String) {
        composer.setTextAndPlaceCursorAtEnd(if (composer.text.isBlank()) text else "$text\n\n${composer.text}")
    }

    override fun retry() {
        session.value?.retry()
    }

    override fun dismissError() {
        session.value?.dismissError()
    }

    override fun skipSpeech() = voice.skipSpeech()

    override fun stopVoiceChat() = voice.stopChat()

    override fun dismissVoiceChatError() = voice.dismissChatError()

    override fun dismissDictationError() = voice.dismissDictationError()

    fun showTitle(title: String?) = session.value?.showTitle(title)

    private companion object {
        /** Full-size gateway photos are a few hundred KB each; keep a screenful or two. */
        const val MAX_CACHED_MEDIA = 24

        /** Typing pauses this long before asking the gateway for matches. */
        const val COMPLETION_DEBOUNCE_MS = 120L

        /** Typing pauses this long before the draft is written to storage. */
        const val DRAFT_SAVE_DEBOUNCE_MS = 400L
    }
}
