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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/** Identifies what the chat screen shows: a stored session, or a new chat (`storedSessionId == null`). */
data class ChatTarget(val gateway: SavedGateway, val storedSessionId: String?, val title: String?, val nonce: Long = 0)

/**
 * Shows the [ChatSession] the app-wide [ChatHost] has open; opening another target replaces it.
 * Remembers the open chat in [LastChatStore] so the next launch returns to it: a stored session once
 * it has messages, or nothing while a new chat is still empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    connection: GatewayConnection,
    private val host: ChatHost,
    private val lastChats: LastChatStore,
    private val models: ModelsApi,
    private val media: MediaApi,
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

    init {
        // The catalog names the default model, which a new chat shows until it has its own.
        viewModelScope.launch {
            connection.state.collect { if (it is ConnectionState.Connected && _picker.value.catalog == null) loadModels() }
        }
        viewModelScope.launch {
            state
                .map { chat -> chat.storedSessionId?.takeIf { chat.messages.isNotEmpty() }?.let { LastChat(it, chat.title) } }
                // Distinct before dropping nulls, so returning to the same chat after a new one saves it again.
                .distinctUntilChanged()
                .filterNotNull()
                .collect { last -> target?.let { lastChats.set(it.gateway.gatewayUrl, last) } }
        }
    }

    fun open(target: ChatTarget) {
        if (this.target == target) return
        this.target = target
        composer.clearText()
        _attachments.value = emptyList()
        _attachmentError.value = null
        if (target.storedSessionId == null) viewModelScope.launch { lastChats.set(target.gateway.gatewayUrl, null) }
        session.value = host.open(target.gateway.gatewayUrl, target.storedSessionId, target.title)
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
                val catalog = models.options(session.value?.state?.value?.runtimeSessionId)
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

    fun send() {
        val chat = session.value ?: return
        val text = composer.text.toString()
        val attachments = _attachments.value
        if (text.isBlank() && attachments.isEmpty()) return
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

    private companion object {
        /** Full-size gateway photos are a few hundred KB each; keep a screenful or two. */
        const val MAX_CACHED_MEDIA = 24
    }
}
