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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * screenshot a moment later. [pending] counts the parts still on their way; [callUp] is which call-up
 * they belong to, so a part read for an earlier one and arriving late is turned away.
 */
data class ScreenCapture(
    val app: String? = null,
    val items: List<ScreenItem> = emptyList(),
    val screenshot: ByteArray? = null,
    val pending: Int = 0,
    val callUp: Int = 0,
    /** The display's size in pixels, which [items] and circled regions are measured in; 0 when unknown. */
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    /**
     * The platform said the screen was coming and none of it came. That is what Android does when
     * "Use screen and app data" is off in the digital assistant settings.
     */
    val missed: Boolean = false,
) {
    val context: ScreenContext get() = ScreenContext(app, items.map { it.text }, screenshot)

    fun withText(callUp: Int, app: String?, items: List<ScreenItem>): ScreenCapture =
        if (callUp != this.callUp) this else copy(app = app ?: this.app, items = items, pending = (pending - 1).coerceAtLeast(0), missed = false)

    fun withScreenshot(callUp: Int, jpeg: ByteArray?, displayWidth: Int, displayHeight: Int): ScreenCapture =
        if (callUp != this.callUp) {
            this
        } else {
            copy(screenshot = jpeg, displayWidth = displayWidth, displayHeight = displayHeight, pending = (pending - 1).coerceAtLeast(0), missed = false)
        }

    /** Stops waiting for parts of [callUp] that never came; a part that still turns up later is taken. */
    fun givenUp(callUp: Int): ScreenCapture =
        if (callUp != this.callUp || pending == 0) this else copy(pending = 0, missed = context.isEmpty)
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

    /** The part the user circled, waiting to go with the next question. */
    private val _circledPart = MutableStateFlow<ScreenContext?>(null)
    val circledPart: StateFlow<ScreenContext?> = _circledPart.asStateFlow()

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
        scope.launch { bind() }
    }

    /** Takes up the gateway Herald is on, its picked profile and a new chat there. */
    private suspend fun bind() {
        val saved = gateways.current()
        if (saved == null || !auth.hasStoredSession(saved.gatewayUrl)) {
            gateway = null
            _phase.value = AssistantPhase.SignedOut
            return
        }
        gateway = saved
        profile = profiles.get(saved.gatewayUrl)
        // Already running when Herald is open; otherwise the panel brings the socket up while the user speaks.
        connection.start(saved.gatewayUrl)
        newChat()
        _phase.value = AssistantPhase.Ready
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
        _circledPart.value = null
        circledFull = null
        val callUp = _screen.value.callUp + 1
        _screen.value = ScreenCapture(pending = listOf(expectText, expectScreenshot).count { it }, callUp = callUp)
        // With the assistant's screen settings off, Android still says the screen is coming and never sends it.
        scope.launch {
            delay(SCREEN_GIVE_UP_MS)
            _screen.update { it.givenUp(callUp) }
            // "Add" on a screen that never came would send nothing with the question.
            if (_screen.value.let { it.callUp == callUp && it.missed }) _includeScreen.value = false
        }
        // The panel outlives a call-up, and Herald may have switched gateways since: the socket is shared, so
        // the chat must move along with it.
        val current = gateways.list.value.current
        if (_phase.value != AssistantPhase.Loading && current?.url != gateway?.url) scope.launch { bind() }
        // The last call-up's chat goes on in the sessions list; an unused one is simply kept.
        else if (_session.value?.state?.value?.hasConversation == true) newChat()
        _shows.update { it + 1 }
    }

    /** Back after stepping aside (for Android's microphone prompt): the same chat and screen, listening again. */
    fun resume() = _shows.update { it + 1 }

    private var listenedOn = -1

    /**
     * True once per call-up: whether to start listening now. The panel's content comes and goes within one
     * (circling replaces it), and coming back from circling must not start listening, which sends.
     */
    fun claimListening(show: Int): Boolean {
        if (show == listenedOn) return false
        listenedOn = show
        return true
    }

    /** The call-up the screen now arriving belongs to; take it when the platform hands a part over. */
    val callUp: Int get() = _screen.value.callUp

    fun onScreenText(callUp: Int, app: String?, items: List<ScreenItem>) = _screen.update { it.withText(callUp, app, items) }

    /**
     * [jpeg] null: the platform had no screenshot to give (a secure window, or the setting is off).
     * [displayWidth] × [displayHeight]: the full screenshot's size, the display's.
     */
    fun onScreenshot(callUp: Int, jpeg: ByteArray?, displayWidth: Int = 0, displayHeight: Int = 0) =
        _screen.update { it.withScreenshot(callUp, jpeg, displayWidth, displayHeight) }

    fun setIncludeScreen(include: Boolean) {
        _includeScreen.value = include
    }

    /**
     * Sends what was typed with what the user chose to show: a circled part, else (on the first
     * question) the whole screen if they added it.
     */
    fun send() {
        val text = composer.text.toString().trim()
        val session = _session.value ?: return
        val first = !session.state.value.hasConversation
        val circled = _circledPart.value
        val wholeScreen = circled == null && first && _includeScreen.value
        if (text.isEmpty() && circled == null && !wholeScreen) return
        composer.clearText()
        // Asking moved on from whatever the microphone last missed.
        voice.dismissDictationError()
        // Sent before its picture was cut: the question waits for the cut rather than going without it.
        val cutting = circledFull?.takeIf { it.first === circled }?.second
        scope.launch {
            val part = cutting?.await() ?: circled
            val attachments = when {
                part != null -> part.toAttachments()
                wholeScreen -> awaitScreen().toAttachments()
                else -> emptyList()
            }
            // What was shown alone still asks something.
            val prompt = text.ifEmpty { if (circled != null) CIRCLED_PROMPT else if (attachments.isNotEmpty()) SCREEN_ONLY_PROMPT else return@launch }
            val sent = session.send(prompt, attachments)
            // Not sent: the words go back where they were typed, and what was shown stays for the retry.
            if (!sent && composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(text)
            if (sent) {
                _includeScreen.value = false
                if (_circledPart.value.let { it === circled || it === part }) _circledPart.value = null
            }
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
     * Holds what the user circled for the next question: the part cut from the full screenshot and the
     * text inside it. Nothing goes out until they send; it takes the whole screen's place.
     */
    fun circled(region: ScreenRegion) {
        _circling.value = false
        _includeScreen.value = false
        val capture = _screen.value
        val lines = capture.items.inside(region).map { it.text }
        // Held at once with its text; the picture follows when cut, unless the user moved on meanwhile.
        val held = ScreenContext(capture.app, lines, null, circled = true)
        _circledPart.value = held
        val full = scope.async {
            // A failed cut still leaves the text: the question then goes without a picture, not at all.
            val crop = try {
                cropper.crop(region)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val part = ScreenContext(capture.app, lines, crop, circled = true)
            _circledPart.update { if (it === held) part else it }
            part
        }
        circledFull = held to full
    }

    fun dropCircled() {
        _circledPart.value = null
        circledFull = null
    }

    /** The circled part held without its picture yet, and the part once the picture is cut. */
    private var circledFull: Pair<ScreenContext, Deferred<ScreenContext>>? = null

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

        /** Long past a slow app's text and screenshot, which take well under a second. */
        private const val SCREEN_GIVE_UP_MS = 4_000L
    }
}
