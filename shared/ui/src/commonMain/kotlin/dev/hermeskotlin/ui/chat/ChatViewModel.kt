package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.Checkpoint
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.ControlAction
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
import dev.hermeskotlin.core.chat.canEdit
import dev.hermeskotlin.core.chat.promptNow
import dev.hermeskotlin.core.chat.regenerateTarget
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.ui.pet.PetController
import dev.hermeskotlin.ui.journey.JourneyController
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.DeviceDictation
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
import kotlinx.coroutines.flow.combine
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
    /** A pick being saved where it takes a while (a bot's own model), and why the last one wasn't. */
    val saving: Boolean = false,
    val saveError: String? = null,
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
    data object OpenCheckpoints : ChatRequest
    data object OpenControl : ChatRequest

    /** `/voice`: the screen asks for the microphone, then starts a voice chat. */
    data object StartVoice : ChatRequest

    /** The voice shortcut: the screen asks for the microphone, then dictates into the composer. */
    data object StartDictation : ChatRequest

    /** [profile] null is the gateway's launch profile. */
    data class SwitchProfile(val profile: String?) : ChatRequest
}

/**
 * Identifies what the chat screen shows: a stored session, or a new chat (`storedSessionId == null`),
 * in [profile] (null: the gateway's launch profile). With [bot] it is that bot's permanent chat. A new
 * chat opened from outside (share sheet, shortcut) brings its [draft]. A new chat started in a project
 * runs in that project's folder, [cwd].
 */
data class ChatTarget(
    val gateway: SavedGateway,
    val storedSessionId: String?,
    val title: String?,
    val nonce: Long = 0,
    val profile: String? = null,
    val bot: BotIdentity? = null,
    val draft: ComposeDraft? = null,
    val cwd: String? = null,
) {
    /**
     * The profile whose last chat this is, which the next launch reads: a bot's chat counts where the Chats
     * list was, the launch profile (null) included, not under the bot's own profile.
     */
    val rememberedIn: String? get() = if (bot != null) bot.chatsProfile else profile
}

/**
 * The bot whose permanent chat is open: its profile and the name it goes by. [chatsProfile] is the profile
 * the Chats list was on when it opened, where the app remembers to come back to this chat.
 */
data class BotIdentity(val name: String, val label: String, val chatsProfile: String? = null)

/**
 * Shows the [ChatSession] the app-wide [ChatHost] has open; opening another target replaces it.
 * Remembers the open chat in [LastChatStore] so the next launch returns to it: a stored session once
 * it has messages, or nothing while a new chat is still empty. Each chat keeps its own unsent text in
 * [DraftStore] and its picked files in memory, so switching chats loses neither.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(
    private val connection: GatewayConnection,
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
    private val bots: BotsApi,
    liveCalls: LiveCalls,
    voiceKeepAlive: VoiceKeepAlive,
    deviceDictation: DeviceDictation,
) : ViewModel(), ChatActions {

    /** Dictation and voice chat for the open chat. */
    val voice = VoiceController(audioApi, recorder, player, viewModelScope, appScope, voiceKeepAlive, deviceDictation, liveCalls)

    /** The profile's pet and its gallery. */
    val pets = PetController(petApi, viewModelScope)

    /** What the profile's agent has learned (`/journey`). */
    val journey = JourneyController(journeyApi, viewModelScope)

    /** Tokens and cost of the open chat. */
    val usage = UsageController(sessions, viewModelScope)

    /** Background processes the agent started in the open chat. */
    val processes = ProcessesController(viewModelScope)

    /** The folder snapshots Hermes took before the agent changed files in the open chat. */
    val checkpoints = CheckpointsController(viewModelScope)

    /** Runs goal, loop and heartbeat actions for the open chat's panel. */
    val control = SessionControlController(viewModelScope)

    override val composer = TextFieldState()
    val connectionState: StateFlow<ConnectionState> = connection.state

    override val runningSend: StateFlow<RunningSend> = settings.settings
        .map { it?.runningSend ?: RunningSend.Steer }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value?.runningSend ?: RunningSend.Steer)

    private var target: ChatTarget? = null
    private val session = MutableStateFlow<ChatSession?>(null)

    init {
        // A chat opened under the checkpoints sheet shows its own checkpoints, not the last chat's.
        viewModelScope.launch { session.collect { checkpoints.follow(it) } }
        viewModelScope.launch { session.collect { control.follow(it) } }
    }

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

    private val _comments = MutableStateFlow<List<PendingComment>>(emptyList())

    /** Comments on parts of the chat, waiting in the composer for the next send. */
    val comments: StateFlow<List<PendingComment>> = _comments.asStateFlow()

    /** Comments left in chats that aren't open, by [trayKey], like [trays]. */
    private val commentTrays = mutableMapOf<String, List<PendingComment>>()
    private var lastCommentId = 0L

    /** The chat whose draft the composer holds; null while it's being restored, so nothing is saved over it. */
    private var draftOf: ChatTarget? = null

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    private val _editing = MutableStateFlow<String?>(null)

    /** The prompt being edited in the composer; the next send replaces it and everything after it. */
    val editing: StateFlow<String?> = _editing.asStateFlow()

    /** What the composer held when the edit started, given back when it ends. */
    private var typedBeforeEdit = ""

    /** The stored row of the prompt being edited, which outlasts its key. */
    private var editingRowId: Long? = null

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
                .map { chat ->
                    chat.storedSessionId?.takeIf { chat.hasConversation }?.let { id ->
                        val bot = target?.bot
                        LastChat(id, chat.title, bot = bot?.name, botLabel = bot?.label)
                    }
                }
                // Distinct before dropping nulls, so returning to the same chat after a new one saves it again.
                .distinctUntilChanged()
                .filterNotNull()
                .collect { last ->
                    target?.let { lastChats.set(it.gateway.gatewayUrl, last, it.rememberedIn) }
                }
        }
        viewModelScope.launch {
            combine(state, _editing) { chat, key -> key?.takeIf { chat.historyLoaded }?.let { it to chat.promptNow(it, editingRowId) } }
                .filterNotNull()
                .collect { (key, now) ->
                    // A reload gives a prompt sent from here its stored key; it's still the same row.
                    if (now == null) editGone() else if (now != key) _editing.value = now
                }
        }
        viewModelScope.launch {
            snapshotFlow { composer.text.toString() }
                .debounce(DRAFT_SAVE_DEBOUNCE_MS)
                // An edit isn't the chat's draft: the text it put aside is, and that's what a restart brings back.
                .collect { text -> draftOf?.let { saveDraft(it, draftChat(it), if (_editing.value != null) typedBeforeEdit else text) } }
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
        val comments = _comments.value
        if (comments.isEmpty()) commentTrays.remove(trayKey(open, chat)) else commentTrays[trayKey(open, chat)] = comments
        viewModelScope.launch { saveDraft(open, chat, text) }
    }

    /**
     * Brings back [target]'s unsent text, files and comments. A draft brought from outside (a share, a
     * shortcut) into a new chat starts it clean instead: nothing is left from an earlier new chat. Into a
     * stored chat (a share to a bot) it comes after what the user had there, which stays. [typed] is the
     * composer's text just before, for when the same chat reopens before its saved draft is written.
     */
    private fun restoreDraft(target: ChatTarget, typed: String?) {
        val outside = target.draft != null
        val clean = outside && target.storedSessionId == null
        _attachments.value = trays.remove(trayKey(target, target.storedSessionId)).takeUnless { clean }.orEmpty()
        _comments.value = commentTrays.remove(trayKey(target, target.storedSessionId)).takeUnless { clean }.orEmpty()
        viewModelScope.launch {
            val saved = when {
                clean -> null
                typed != null -> typed
                else -> drafts.get(target.gateway.gatewayUrl, target.storedSessionId, target.profile)
            }
            if (this@ChatViewModel.target != target) return@launch
            val shared = target.draft?.text?.takeIf { it.isNotBlank() && !clean }
            when {
                shared != null -> composer.setTextAndPlaceCursorAtEnd(listOfNotNull(saved?.takeIf { it.isNotBlank() }, shared).joinToString("\n"))
                saved != null && composer.text.isEmpty() -> composer.setTextAndPlaceCursorAtEnd(saved)
            }
            draftOf = target
        }
    }

    fun open(target: ChatTarget) {
        if (this.target == target) {
            // An expired session or sign-out closes the host's chat; the same chat reopened after signing
            // back in needs a fresh session, not the stopped one this view model still holds.
            if (session.value !== host.session.value) {
                session.value = host.open(target.gateway.gatewayUrl, target.storedSessionId, target.title, target.profile, target.cwd)
            }
            return
        }
        // An edit belongs to its chat; what was typed before it is that chat's draft.
        cancelEdit()
        val sameChat = draftOf?.let(::draftChat)?.takeIf { it == target.storedSessionId } != null
        val typed = composer.text.toString().takeIf { sameChat }
        stashDraft()
        this.target = target
        // A voice chat belongs to the chat it started in.
        voice.stopAll()
        composer.clearText()
        _attachmentError.value = null
        restoreDraft(target, typed)
        target.draft?.let(::takeDraft)
        if (target.storedSessionId == null) viewModelScope.launch { lastChats.set(target.gateway.gatewayUrl, null, target.profile) }
        session.value = host.open(target.gateway.gatewayUrl, target.storedSessionId, target.title, target.profile, target.cwd)
        // The catalog marks the previous chat's model; a new chat must show the profile default instead.
        _picker.update { it.copy(catalog = null, confirm = null) }
        if (connectionState.value is ConnectionState.Connected) {
            loadModels()
            pets.bind(target.profile)
        }
    }

    /**
     * Fills the composer with what came from outside, to look over before sending: shared text and files,
     * or dictation started by the voice shortcut. Into a stored chat, the text waits for that chat's own
     * draft and goes after it ([restoreDraft]); the files join its tray.
     */
    private fun takeDraft(draft: ComposeDraft) {
        if (target?.storedSessionId == null) draft.text?.takeIf { it.isNotBlank() }?.let(composer::setTextAndPlaceCursorAtEnd)
        draft.notice?.let(::showAttachmentError)
        // After the notice: when the files overfill a tray that already held some, the tray limit is what to tell.
        if (draft.attachments.isNotEmpty()) addAttachments(draft.attachments)
        if (draft.dictate) viewModelScope.launch { _requests.send(ChatRequest.StartDictation) }
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
        val engine = (settings.settings.value ?: AppSettings()).dictationEngine
        val typed = composer.text.toString()
        // The words so far show in the composer as they're said, unless the person edits it meanwhile.
        var shown = typed
        fun withSpoken(base: String, spoken: String) = if (base.isBlank()) spoken else "${base.trimEnd()} $spoken"
        voice.startDictation(
            target.gateway.gatewayUrl,
            target.profile,
            engine,
            onPartial = { partial ->
                if (composer.text.toString() == shown) {
                    shown = withSpoken(typed, partial)
                    composer.setTextAndPlaceCursorAtEnd(shown)
                }
            },
            // Cancelled (another chat, the app went away): the half-said words go, what was typed stays.
            onCancelled = { if (shown != typed && composer.text.toString() == shown) composer.setTextAndPlaceCursorAtEnd(typed) },
        ) { text ->
            val current = composer.text.toString()
            composer.setTextAndPlaceCursorAtEnd(withSpoken(if (current == shown) typed else current, text))
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

    /** Shows the checkpoints sheet (from the chat menu, or `/rollback`). */
    fun openCheckpoints() {
        viewModelScope.launch { _requests.send(ChatRequest.OpenCheckpoints) }
    }

    /** Shows the goal and loops sheet (from the chat menu, or the strip above the composer). */
    fun openControl() {
        control.clearError()
        viewModelScope.launch { _requests.send(ChatRequest.OpenControl) }
    }

    override fun runControl(action: ControlAction, text: String?, index: Int?) = control.run(session.value, action, text, index)

    fun loadCheckpoints() = checkpoints.load(session.value)

    fun checkpointDiff(checkpoint: Checkpoint) = checkpoints.loadDiff(session.value, checkpoint)

    fun restoreCheckpoint(checkpoint: Checkpoint) = checkpoints.restore(session.value, checkpoint)

    fun loadUsage() {
        val target = target ?: return
        usage.load(target.gateway.gatewayUrl, state.value.storedSessionId, target.profile, session.value)
    }

    /**
     * Refreshes the catalog for the open chat (its current model marked). [withPrices] (when the picker opens) also
     * fetches prices the gateway left out, a few seconds later; see [ModelsApi.missingPrices].
     */
    fun loadModels(withPrices: Boolean = false) {
        // A load still running belongs to whatever chat was open when it started.
        loadJob?.cancel()
        _picker.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                // Without a live session the catalog marks the default of the chat's profile.
                val runtimeSessionId = session.value?.state?.value?.runtimeSessionId
                val catalog = models.options(runtimeSessionId, target?.profile)
                _picker.update { it.copy(catalog = catalog, loading = false) }
                if (!withPrices) return@launch
                // Prices the gateway left out follow a few seconds later; the list works without them.
                val prices = try {
                    models.missingPrices(catalog, runtimeSessionId, target?.profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                prices?.let { found -> _picker.update { it.copy(catalog = it.catalog?.withPrices(found)) } }
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
        // A Bot Chat follows its bot's configuration: a model picked in it is the bot's, never pinned to the
        // chat alone, which would leave the bot and its chat running different models (#129460).
        target?.bot?.let { bot -> return setBotModel(chat, bot, model, confirmed) }
        viewModelScope.launch {
            val result = chat.setModel(model.id, model.provider, confirmed)
            if (result is ModelSwitch.NeedsConfirmation) _picker.update { it.copy(confirm = PendingSwitch(model, result.message)) }
        }
    }

    private fun setBotModel(chat: ChatSession, bot: BotIdentity, model: ModelOption, confirmed: Boolean) {
        viewModelScope.launch {
            try {
                val warning = bots.setModel(bot.name, model.id, model.provider, confirmed)
                if (warning != null) {
                    _picker.update { it.copy(confirm = PendingSwitch(model, warning)) }
                } else {
                    chat.showModel(model.id, model.provider)
                    _picker.update { it.copy(catalog = it.catalog?.copy(currentModel = model.id, currentProvider = model.provider)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat.showModel(null, null, error = "Couldn't change ${bot.label}'s model. ${e.message.orEmpty()}".trim())
            }
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

    override fun addComment(source: CommentSource, anchor: SelectionAnchor): Long {
        val comment = newComment(++lastCommentId, source, anchor)
        _comments.update { it + comment }
        return comment.id
    }

    override fun removeComment(id: Long) = _comments.update { tray -> tray.filterNot { it.id == id } }

    override fun explain(source: CommentSource, anchor: SelectionAnchor) {
        val chat = session.value
        val idle = chat != null && !state.value.running && connectionState.value is ConnectionState.Connected
        // One tap asks right away, unless it would sweep up a message being put together.
        if (idle && _comments.value.isEmpty() && composer.text.isBlank() && _attachments.value.isEmpty()) {
            val comment = newComment(++lastCommentId, source, anchor, note = EXPLAIN_NOTE)
            viewModelScope.launch {
                if (chat.submit(formatReview(listOf(comment), ""), emptyList(), queue = false) == SendOutcome.NotSent) {
                    _comments.update { listOf(comment) + it }
                }
            }
        } else {
            _comments.update { it + newComment(++lastCommentId, source, anchor, note = EXPLAIN_NOTE) }
        }
    }

    override fun askAside(source: CommentSource, anchor: SelectionAnchor) {
        val quote = anchor.text.replace(Regex("""\s+"""), " ").trim().let { if (it.length > ASIDE_QUOTE_MAX) it.take(ASIDE_QUOTE_MAX).trimEnd() + "…" else it }
        composer.setTextAndPlaceCursorAtEnd("/btw About “$quote” in ${source.label}: ")
    }

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
                // A bot keeps one chat forever, so a fresh start there is a fresh context, as on Desktop.
                SlashRoute.NewChat -> if (target?.bot != null) chat.compress(arg) else _requests.send(ChatRequest.NewChat)
                // Like Desktop: bare opens the picker, `/model <name>` is for the gateway to parse.
                SlashRoute.PickModel -> if (arg.isEmpty()) _requests.send(ChatRequest.PickModel) else onGateway()
                SlashRoute.BrowseSessions -> if (arg.isEmpty()) _requests.send(ChatRequest.BrowseSessions) else resume(chat, arg)
                SlashRoute.Stop -> chat.stopEverything()
                SlashRoute.Help -> chat.showCommandOutput("/help", helpText(catalog()))
                SlashRoute.Compress -> chat.compress(arg)
                SlashRoute.Status -> chat.status()
                SlashRoute.Aside -> chat.askAside(arg)
                SlashRoute.Steer -> if (arg.isEmpty()) {
                    chat.showCommandOutput("/steer", "Usage: /steer <note>. The running reply reads it after its current step, without stopping.")
                } else if (chat.steer(arg) == SendOutcome.NotSent) {
                    giveBack("/steer $arg")
                }
                SlashRoute.Reasoning -> when (chat.reasoning(arg)) {
                    // The gateway's display words drive this app's own Thinking toggle too.
                    "show" -> settings.update { it.copy(showReasoning = true) }
                    "hide" -> settings.update { it.copy(showReasoning = false) }
                }
                SlashRoute.Yolo -> chat.toggleYolo()
                // Bare `/title` reports the title, which the gateway's command does.
                SlashRoute.Title -> when {
                    arg.isEmpty() -> onGateway()
                    // Its title is what makes it the bot's chat; renamed, the bot would start a new one.
                    target?.bot != null -> chat.showCommandOutput("/title", "A bot's chat keeps its name, so the bot can find it.", failed = true)
                    else -> chat.retitle(arg)
                }
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
                SlashRoute.Rollback -> if (arg.isEmpty()) _requests.send(ChatRequest.OpenCheckpoints) else onGateway()
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
     * Sends what's in the composer. Mid-turn it goes the way [mode] says, or the "While a reply is running"
     * setting when none was picked: steered into the running turn, queued for the next, or sent once the
     * turn is stopped. A steer can't carry files, so with attachments it queues instead.
     */
    override fun send(mode: RunningSend?) {
        val chat = session.value ?: return
        val text = composer.text.toString()
        val attachments = _attachments.value
        val comments = _comments.value
        if (text.isBlank() && attachments.isEmpty() && comments.isEmpty()) return
        _editing.value?.let { key -> return sendEdit(chat, key, text, comments) }
        val command = SlashCommand.parse(text.trim())
        // Commands run at once either way; they never become a turn to queue. Waiting comments stay for the next prompt.
        if (command != null && attachments.isEmpty()) {
            if (command.name.isEmpty()) return
            composer.clearText()
            runCommand(chat, command)
            return
        }
        composer.clearText()
        _attachments.value = emptyList()
        _comments.value = emptyList()
        val outgoing = if (comments.isEmpty()) text else formatReview(comments, text)
        val running = sendModeFor(state.value.running, mode, settings.settings.value?.runningSend, attachments.isNotEmpty())
        viewModelScope.launch {
            val outcome = when (running) {
                null -> chat.submit(outgoing, attachments)
                RunningSend.Steer -> chat.steer(outgoing)
                RunningSend.Queue -> chat.submit(outgoing, attachments, queue = true)
                RunningSend.StopAndSend -> {
                    val result = chat.stopAndSubmit(outgoing, attachments)
                    // The stop drops what was queued behind the task; hand it back rather than lose it.
                    result.dropped.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { giveBack(it.joinToString("\n\n")) }
                    result.outcome
                }
            }
            // Give everything back if it never reached the gateway, so nothing typed or picked is lost. One
            // that may have arrived keeps its bubble to resend from instead, so it isn't in two places.
            if (outcome == SendOutcome.NotSent) {
                giveBack(text)
                _attachments.update { attachments + it }
                _comments.update { comments + it }
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
            composer.setTextAndPlaceCursorAtEnd(listOf(takeComments(dropped), typed).filter { it.isNotBlank() }.joinToString("\n\n"))
        }
    }

    override fun editLastPrompt(key: String) {
        val chat = session.value ?: return
        val state = state.value
        // Asked for in a dialog that may have stayed open: /undo takes whatever turn is last now.
        if (!state.canChangeChat(connectionState.value is ConnectionState.Connected)) return
        if (state.messages.lastOrNull { it is ChatMessage.User }?.key != key) return
        val undo = SlashCommand.parse("/undo") ?: return
        viewModelScope.launch {
            val text = takeComments(listOf(chat.runCommand(undo) ?: return@launch))
            // Whatever was being typed stays, after the prompt that comes back.
            val typed = composer.text.toString()
            if (text.isBlank()) return@launch
            composer.setTextAndPlaceCursorAtEnd(if (typed.isBlank()) text else "$text\n\n$typed")
        }
    }

    override fun branchFrom(key: String) {
        val chat = session.value ?: return
        if (!state.value.canChangeChat(connectionState.value is ConnectionState.Connected)) return
        if (state.value.messages.none { it.key == key }) return
        viewModelScope.launch {
            // Counted from the first row, so the pages not scrolled back to yet are read first.
            if (!chat.loadAllHistory()) return@launch
            val messages = chat.state.value.messages
            val index = messages.indexOfFirst { it.key == key }
            if (index < 0) return@launch
            // The gateway keeps the first N user and assistant rows that have text, so count those.
            val count = messages.take(index + 1).count {
                (it is ChatMessage.User && it.text.isNotBlank()) || (it is ChatMessage.Assistant && it.text.isNotBlank())
            }
            chat.branch(count)?.let { (id, title) -> _requests.send(ChatRequest.OpenChat(id, title)) }
        }
    }

    override fun regenerate(key: String) {
        val chat = session.value ?: return
        val state = state.value
        // Asked for in a dialog that may have stayed open while the chat moved on.
        if (!canRewind(state)) return
        val prompt = state.regenerateTarget(key) ?: return
        val text = prompt.sentText ?: return
        viewModelScope.launch { chat.rewind(prompt.key, text) }
    }

    override fun startEdit(key: String) {
        val state = state.value
        if (!canRewind(state) || !state.canEdit(key)) return
        val prompt = state.messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return
        if (_editing.value == null) typedBeforeEdit = composer.text.toString()
        editingRowId = prompt.rowId
        _editing.value = key
        composer.setTextAndPlaceCursorAtEnd(prompt.text)
    }

    override fun cancelEdit() {
        if (_editing.value == null) return
        _editing.value = null
        composer.setTextAndPlaceCursorAtEnd(typedBeforeEdit)
        typedBeforeEdit = ""
    }

    /**
     * The prompt being edited went (another client, or a regenerate above it, cut the chat): there's nothing left to
     * replace. What was typed for it stays, ahead of the text it put aside, to send as a new message or drop.
     */
    private fun editGone() {
        val edited = composer.text.toString()
        _editing.value = null
        composer.setTextAndPlaceCursorAtEnd(listOf(edited, typedBeforeEdit).filter { it.isNotBlank() }.joinToString("\n\n"))
        typedBeforeEdit = ""
    }

    /**
     * Sends the edit of prompt [key]: it and everything after it go, and [text] (with any [comments]) is sent
     * in its place. Files waiting in the composer stay for the next send. Turned down, the edit is back as it was.
     */
    private fun sendEdit(chat: ChatSession, key: String, text: String, comments: List<PendingComment>) {
        if (text.isBlank() && comments.isEmpty()) return
        val outgoing = if (comments.isEmpty()) text else formatReview(comments, text)
        _comments.value = emptyList()
        cancelEdit()
        viewModelScope.launch {
            if (chat.rewind(key, outgoing) != SendOutcome.NotSent) return@launch
            // The prompt is back where it was if the gateway turned the edit down; carry on editing it.
            _comments.update { comments + it }
            if (state.value.messages.any { it.key == key }) {
                typedBeforeEdit = composer.text.toString()
                _editing.value = key
                composer.setTextAndPlaceCursorAtEnd(text)
            } else {
                giveBack(text)
            }
        }
    }

    /** Regenerate and edit change the stored transcript, so only while it [can change][canChangeChat]. */
    private fun canRewind(state: ChatState) = state.canChangeChat(connectionState.value is ConnectionState.Connected)

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

    /**
     * Puts [text] back in the composer, ahead of anything typed since, which stays. Comments it carries go
     * back to the tray as cards, not as their markup.
     */
    private fun giveBack(text: String) {
        val typed = takeComments(listOf(text))
        if (typed.isBlank()) return
        composer.setTextAndPlaceCursorAtEnd(if (composer.text.isBlank()) typed else "$typed\n\n${composer.text}")
    }

    /**
     * Moves the comments in sent [texts] back to the tray as cards, ahead of those waiting, and returns what
     * was typed around them, so no prompt comes back to the composer as its markup.
     */
    private fun takeComments(texts: List<String>): String {
        val (back, typed) = unsend(texts) { ++lastCommentId }
        if (back.isNotEmpty()) _comments.update { back + it }
        return typed
    }

    override fun retry() {
        session.value?.retry()
    }

    /**
     * Tries the gateway again now instead of waiting out the backoff. Only while it's waiting: during an attempt
     * the wake would stay buffered and skip the wait after some later drop.
     */
    fun retryConnection() {
        if (connectionState.value is ConnectionState.Reconnecting) connection.retry()
    }

    override fun loadOlder() {
        val chat = session.value ?: return
        viewModelScope.launch { chat.loadOlder() }
    }

    override fun dismissError() {
        session.value?.dismissError()
    }

    override fun dismissNotice(key: String) {
        session.value?.dismissNotice(key)
    }

    override fun skipSpeech() = voice.skipSpeech()

    override fun stopVoiceChat() = voice.stopChat()

    override fun toggleVoiceMute() = voice.toggleMute()

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

        /** What Explain asks of the agent about the selection. */
        const val EXPLAIN_NOTE = "Explain this in more detail."

        /** How much of the selection a `/btw` question quotes. */
        const val ASIDE_QUOTE_MAX = 160
    }
}
