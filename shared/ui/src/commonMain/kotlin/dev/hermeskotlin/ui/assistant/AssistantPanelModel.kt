package dev.hermeskotlin.ui.assistant

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.ui.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Where the panel stands before there is anything to ask: still reading the saved gateway, or nobody signed in. */
enum class AssistantPhase { Loading, SignedOut, Ready }

/**
 * The screen as the panel holds it while the platform hands it over in parts: the text first, the
 * screenshot a moment later. [pending] counts the parts still on their way.
 */
data class ScreenCapture(
    val app: String? = null,
    val items: List<ScreenItem> = emptyList(),
    val screenshot: ByteArray? = null,
    val pending: Int = 0,
) {
    val context: ScreenContext get() = ScreenContext(app, items.map { it.text }, screenshot)
}

/**
 * Cuts [region] (display pixels) out of the screenshot the platform handed over, at full resolution,
 * as a JPEG; null when there's no screenshot to cut from.
 */
fun interface ScreenCropper {
    suspend fun crop(region: ScreenRegion): ByteArray?
}

/**
 * The assistant panel shown over another app: a new chat with the gateway Herald is signed in to, whose
 * first prompt carries what was on the screen. The chat is its own, not the app's open one, so calling
 * the assistant up never takes the user away from a chat running in Herald; "Open in Herald" hands it over.
 *
 * Lives as long as one panel; [close] when the platform tears it down.
 */
class AssistantPanelModel(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val profiles: ProfileStore,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    audio: AudioApi,
    recorder: VoiceRecorder,
    player: SpeechPlayer,
    appScope: CoroutineScope,
    private val cropper: ScreenCropper = ScreenCropper { null },
) {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))

    private val _phase = MutableStateFlow(AssistantPhase.Loading)
    val phase: StateFlow<AssistantPhase> = _phase.asStateFlow()

    private val _session = MutableStateFlow<ChatSession?>(null)
    val session: StateFlow<ChatSession?> = _session.asStateFlow()

    private val _screen = MutableStateFlow(ScreenCapture())
    val screen: StateFlow<ScreenCapture> = _screen.asStateFlow()

    /**
     * Whether the next prompt takes the screen along. Off until the user asks for it: most questions
     * aren't about the screen, and what's on it can be private.
     */
    private val _includeScreen = MutableStateFlow(false)
    val includeScreen: StateFlow<Boolean> = _includeScreen.asStateFlow()

    /** Whether the user is circling part of the frozen screen to ask about it. */
    private val _circling = MutableStateFlow(false)
    val circling: StateFlow<Boolean> = _circling.asStateFlow()

    /** Counts call-ups, so what happens on each one (listening) happens again on the next. */
    private val _shows = MutableStateFlow(0)
    val shows: StateFlow<Int> = _shows.asStateFlow()

    val composer = TextFieldState()
    val voice = VoiceController(audio, recorder, player, scope, appScope)

    val connectionState get() = connection.state

    private var gateway: SavedGateway? = null
    private var profile: String? = null
    private var sessionScope: CoroutineScope? = null

    init {
        scope.launch {
            val saved = gateways.current()
            if (saved == null || !auth.hasStoredSession(saved.gatewayUrl)) {
                _phase.value = AssistantPhase.SignedOut
                return@launch
            }
            gateway = saved
            profile = profiles.get(saved.gatewayUrl)
            // Already running when Herald is open; otherwise the panel brings the socket up while the user speaks.
            connection.start(saved.gatewayUrl)
            newChat()
            _phase.value = AssistantPhase.Ready
        }
    }

    /**
     * A fresh start for another call-up: a new chat, and the screen to come. [expectText] and
     * [expectScreenshot] say which parts the platform will send, so a quick question waits for them.
     */
    fun begin(expectText: Boolean, expectScreenshot: Boolean) {
        voice.cancelDictation()
        composer.clearText()
        _includeScreen.value = false
        _circling.value = false
        _screen.value = ScreenCapture(pending = listOf(expectText, expectScreenshot).count { it })
        // The last call-up's chat goes on in the sessions list; an unused one is simply kept.
        if (_session.value?.state?.value?.hasConversation == true) newChat()
        _shows.update { it + 1 }
    }

    /** Back after stepping aside (for Android's microphone prompt): the same chat and screen, listening again. */
    fun resume() = _shows.update { it + 1 }

    fun onScreenText(app: String?, items: List<ScreenItem>) = _screen.update {
        it.copy(app = app ?: it.app, items = items, pending = (it.pending - 1).coerceAtLeast(0))
    }

    /** [jpeg] null: the platform had no screenshot to give (a secure window, or the setting is off). */
    fun onScreenshot(jpeg: ByteArray?) = _screen.update {
        it.copy(screenshot = jpeg, pending = (it.pending - 1).coerceAtLeast(0))
    }

    fun setIncludeScreen(include: Boolean) {
        _includeScreen.value = include
    }

    /** Sends what was typed; the first prompt takes the screen along unless the user dropped it. */
    fun send() {
        val text = composer.text.toString().trim()
        val session = _session.value ?: return
        val first = !session.state.value.hasConversation
        if (text.isEmpty() && !(first && _includeScreen.value)) return
        composer.clearText()
        scope.launch {
            val attachments = if (first && _includeScreen.value) awaitScreen().toAttachments() else emptyList()
            // A screen alone still asks something.
            val prompt = text.ifEmpty { if (attachments.isNotEmpty()) SCREEN_ONLY_PROMPT else return@launch }
            val sent = session.send(prompt, attachments)
            // Not sent: the words go back where they were typed, and the screen stays for the retry.
            if (!sent && composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(text)
            if (sent) _includeScreen.value = false
        }
    }

    /** Freezes the screen for circling; only with a screenshot, since that is what gets circled. */
    fun startCircling() {
        if (_screen.value.screenshot == null) return
        voice.cancelDictation()
        _circling.value = true
    }

    fun cancelCircling() {
        _circling.value = false
    }

    /**
     * Asks about what the user circled, at once, as Circle to Search does: the circled part cut from the
     * full screenshot and the text inside it, with whatever was typed, else "What's this?".
     */
    fun circled(region: ScreenRegion) {
        _circling.value = false
        val session = _session.value ?: return
        val capture = _screen.value
        val typed = composer.text.toString().trim()
        composer.clearText()
        scope.launch {
            val crop = cropper.crop(region)
            val lines = capture.items.inside(region).map { it.text }
            val context = ScreenContext(capture.app, lines, crop, circled = true)
            val sent = session.send(typed.ifEmpty { CIRCLED_PROMPT }, context.toAttachments())
            if (!sent && composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(typed)
            // The whole screen has been asked about in part; offering it again would be noise.
            if (sent) _includeScreen.value = false
        }
    }

    /** Dictates into the composer and sends what was said, as an assistant does. */
    fun toggleDictation() {
        val saved = gateway ?: return
        if (voice.dictation.value.recording) return voice.finishDictation()
        voice.startDictation(saved.gatewayUrl, profile) { spoken ->
            val typed = composer.text.toString().trim()
            composer.setTextAndPlaceCursorAtEnd(listOf(typed, spoken.trim()).filter { it.isNotEmpty() }.joinToString(" "))
            send()
        }
    }

    fun stop() {
        val session = _session.value ?: return
        scope.launch { session.interrupt() }
    }

    fun answer(request: InputRequest, result: JsonObject) {
        val session = _session.value ?: return
        scope.launch { session.answer(request, result) }
    }

    /** The chat's id once it exists on the gateway, for opening it in Herald. */
    fun storedSessionId(): String? = _session.value?.state?.value?.storedSessionId?.takeIf { _session.value?.state?.value?.hasConversation == true }

    fun close() {
        voice.cancelDictation()
        _session.value?.stop()
        scope.cancel()
    }

    private fun newChat() {
        val saved = gateway ?: return
        _session.value?.stop()
        sessionScope?.cancel()
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = childScope
        _session.value = ChatSession(saved.gatewayUrl, null, null, connection, sessions, childScope, profile).also { it.start() }
    }

    /** The screen once the platform has handed it all over, or as much as came within a moment. */
    private suspend fun awaitScreen(): ScreenContext {
        withTimeoutOrNull(SCREEN_WAIT_MS) { _screen.first { it.pending == 0 } }
        return _screen.value.context
    }

    companion object {
        const val SCREEN_ONLY_PROMPT = "What's on my screen?"
        const val CIRCLED_PROMPT = "What's this?"
        private const val SCREEN_WAIT_MS = 2_000L
    }
}
