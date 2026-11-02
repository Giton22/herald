package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.forRoster
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.toolInProgress
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.wearlink.DefaultCallTarget
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.speakableText
import dev.hermeskotlin.core.voice.speechChunks
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WatchAccent
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchChat
import dev.hermeskotlin.wearlink.WatchBot
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import dev.hermeskotlin.wearlink.clipForWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Clock

/** How the relay reaches the watch: the platform's Data Layer, or a fake in tests. */
interface WatchOutbox {
    /** Sends a message on [path] to the watch [node]; false when it didn't go. */
    suspend fun send(node: String, path: String, data: ByteArray): Boolean

    /** Sends [data] on a channel at [path] to [node], for what's too large for a message; false when it didn't go. */
    suspend fun stream(node: String, path: String, data: ByteArray): Boolean
}

/**
 * Kyllene's side of the watch companion: what the watch asks goes to Hermes from here, over the phone's gateway
 * connection and sign-in, and the turn goes back to the watch as it runs ([ReplyUpdate]s), then read aloud in the
 * gateway's voice. The watch's chat runs in its own [ChatSession], never the one open in the app, so talking
 * on the watch doesn't move the phone off its chat; [ChatHost.sideChats] keeps its replies out of the phone's
 * notifications, since the watch shows them itself.
 *
 * One ask is followed at a time: a new one takes over the updates and the voice, and its words reach the
 * running turn the way a prompt sent mid-turn does.
 */
@OptIn(ExperimentalAtomicApi::class)
class WatchRelay(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val profiles: ProfileStore,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val audio: AudioApi,
    private val host: ChatHost,
    appScope: CoroutineScope,
    private val outbox: WatchOutbox,
    private val bots: BotsApi,
    private val botChats: BotChats,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))

    private val _busy = MutableStateFlow(false)

    /** A watch ask is being answered or read aloud, or a call is on; the app keeps its connection up meanwhile. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // Set at once, not through a flow: busy has to be true before the caller returns (see ask). Atomic, as the
    // watch's messages arrive on their own thread. Calls are counted: a new one starts before the last has ended.
    private val askingNow = AtomicBoolean(false)
    private val calls = AtomicInt(0)
    private val lists = AtomicInt(0)

    /** A live call is on, from the watch or answered on the phone: nothing rings over it. */
    val onCall: Boolean get() = calls.load() > 0
    private var asking: Boolean
        get() = askingNow.load()
        set(value) {
            askingNow.store(value)
            refreshBusy()
        }

    /** Busy while asking, calling or listing; re-check writes made on another thread. */
    private fun refreshBusy() {
        while (true) {
            val wanted = askingNow.load() || calls.load() > 0 || lists.load() > 0
            _busy.value = wanted
            if (wanted == (askingNow.load() || calls.load() > 0 || lists.load() > 0)) return
        }
    }

    /** What each profile of the current gateway said of its live voice, and when: the watch's chat and the ringer ask for different ones. */
    private class LiveVoice(val on: Boolean, val at: Long)
    private val liveVoices = AtomicReference<Map<Pair<GatewayUrl, String?>, LiveVoice>>(emptyMap())

    private val _session = MutableStateFlow<ChatSession?>(null)

    /** The watch's chat, for answering its approvals and questions from the phone. */
    val session: StateFlow<ChatSession?> = _session.asStateFlow()

    private var sessionGateway: GatewayUrl? = null
    private var sessionProfile: String? = null
    private var sessionScope: CoroutineScope? = null
    private var turn: Job? = null
    private var turnAskId: String? = null
    private val turnLock = Mutex()
    private var heldChat: String? = null
    private var releaseLater: Job? = null

    /**
     * An ask is on its way (a recording still being read off its channel): busy now, while Android still lets
     * the app keep itself up, so nothing lets the connection go before [ask] or [expectedNothing].
     */
    fun expect() {
        asking = true
    }

    /** What [expect] waited for never came: not busy, unless a turn is still being answered. */
    fun expectedNothing() {
        scope.launch {
            turnLock.withLock { if (turn?.isActive != true) asking = false }
        }
    }

    /** A typed or transcribed ask from the watch [node]; [recording] is its audio when it was spoken. */
    fun ask(node: String, ask: WatchAsk, recording: ByteArray? = null) {
        // Busy at once, before the caller returns: that is when Android still lets the app keep itself up.
        asking = true
        scope.launch {
            turnLock.withLock {
                // Joined, so the old turn's last update can't land after the new one's first.
                turn?.cancelAndJoin()
                releaseLater?.cancel()
                turnAskId = ask.id
                asking = true
                turn = scope.launch {
                    try {
                        answer(node, ask, recording)
                    } finally {
                        if (turnAskId == ask.id) {
                            asking = false
                            releaseHeldChatLater()
                        }
                    }
                }
            }
        }
    }

    /** The watch asked to stop: the running turn ends and the voice goes quiet. */
    fun stop(askId: String) {
        scope.launch {
            val stopped = turnLock.withLock {
                if (askId != turnAskId) return@withLock false
                turn?.cancel()
                asking = false
                true
            }
            if (stopped) _session.value?.takeIf { it.state.value.running }?.interrupt()
        }
    }

    fun status(node: String, botProfile: String? = null, requestId: String? = null, owner: WatchScope? = null, accent: WatchAccent? = null) {
        scope.launch {
            val (status, gateway) = currentStatus(botProfile, owner)
            // Due or not is decided with what's sent: decided after, another ask's check could land during the
            // send and leave this stale answer last on the watch.
            val profile = gateway?.let { if (owner != null && owner.gateway == it.value) owner.profile else botProfile ?: profiles.get(it) }
            val due = gateway != null && !liveVoiceFresh(gateway, profile)
            // Told at once with what's known: a gateway that's slow or out of reach (off the tailnet) would hold
            // the answer up to the request's timeout. A fresh check, when due, follows if it changed anything.
            outbox.sendSafely(node, WearPaths.STATUS, WearJson.encode(status.copy(requestId = requestId, accent = accent)))
            if (due && gateway != null) {
                val known = status.liveVoice
                val on = checkLiveVoice(gateway, profile, known)
                // Told as the phone stands now, not as before the check: signed out or switched meanwhile, this
                // gateway's answer is no longer the watch's, and its next ask tells it how things stand.
                val (now, nowGateway) = currentStatus(botProfile, owner)
                val nowProfile = if (owner != null && owner.gateway == gateway.value) owner.profile else botProfile ?: profiles.get(gateway)
                if (on != known && nowGateway == gateway && nowProfile == profile && (now.liveVoice == null || now.liveVoice == on)) {
                    outbox.sendSafely(node, WearPaths.STATUS, WearJson.encode(now.copy(liveVoice = on, requestId = requestId)))
                }
            }
        }
    }

    /**
     * Tells the watch [node] a new [accent] unasked, with how the phone stands. The live voice isn't checked:
     * the watch's own status asks do that, and an accent change shouldn't reach the gateway.
     */
    fun accent(node: String, accent: WatchAccent) {
        scope.launch { outbox.sendSafely(node, WearPaths.STATUS, WearJson.encode(currentStatus(null, null).first.copy(accent = accent))) }
    }

    /** How the phone stands, and the gateway it's signed in to; its live voice null when not asked yet. */
    private suspend fun currentStatus(botProfile: String?, owner: WatchScope?): Pair<PhoneStatus, GatewayUrl?> {
        val saved = gateways.current()
        val state = connection.state.value
        val signedIn = saved != null && auth.hasStoredSession(saved.gatewayUrl) && state !is ConnectionState.SessionExpired
        // Idle counts: Kyllene connects when asked, it just holds no connection while in the background.
        val reachable = state is ConnectionState.Connected || state is ConnectionState.Idle
        val gateway = saved?.gatewayUrl?.takeIf { signedIn }
        val profile = gateway?.let { if (owner != null && owner.gateway == it.value) owner.profile else botProfile ?: profiles.get(it) }
        val live = if (gateway == null) false else liveVoices.load()[gateway to profile]?.on
        return PhoneStatus(signedIn, signedIn && reachable, failed = signedIn && state is ConnectionState.Failed,
            liveVoice = live, botChats = true, botProfile = botProfile, defaultCalls = true, profileSync = true,
            activeScope = gateway?.let { WatchScope(it.value, profiles.get(it)) },
            scope = gateway?.let { WatchScope(it.value, profile) }) to gateway
    }

    /** Whether [gateway]'s live voice was asked lately: the watch asks how the phone stands often, the gateway every few minutes. */
    private fun liveVoiceFresh(gateway: GatewayUrl, profile: String?): Boolean =
        liveVoices.load()[gateway to profile]?.let { Clock.System.now().toEpochMilliseconds() - it.at < LIVE_VOICE_CHECK_MS } == true

    /** Whether the gateway talks live (GPT-Live); [known] is what it said last. */
    private suspend fun checkLiveVoice(gateway: GatewayUrl, profile: String?, known: Boolean?): Boolean {
        val result = audio.voiceLiveStatus(gateway, profile)
        // A failed check keeps what was known: an older gateway answers 404, a dropped one nothing.
        val on = when (result) {
            is ApiResult.Success -> result.value.live
            else -> known ?: false
        }
        // Not over the gateway switched to meanwhile: the one slot is for the current one.
        if (gateways.current()?.gatewayUrl == gateway) liveVoiceIs(gateway, on, profile)
        return on
    }

    /**
     * Whether the signed-in gateway was last found to talk live, without asking it: for deciding at once whether the
     * watch rings. Not yet asked (just switched to), it doesn't, until [checkLiveVoice] finds out.
     */
    val talksLive: Boolean
        get() = gateways.list.value.current?.gatewayUrl?.let { liveVoices.load()[it to null]?.on } == true

    /** Asks the gateway whether it talks live, unless that was asked lately, so [talksLive] is fresh for the next call. */
    fun checkLiveVoice() {
        scope.launch {
            val saved = gateways.current()
            // Another gateway's answer, or none signed in: it doesn't talk live until it says so.
            liveVoices.updateAndFetch { all -> all.filterKeys { it.first == saved?.gatewayUrl } }
            if (saved == null || !auth.hasStoredSession(saved.gatewayUrl)) {
                liveVoices.store(emptyMap())
            } else if (!liveVoiceFresh(saved.gatewayUrl, null)) {
                checkLiveVoice(saved.gatewayUrl, null, liveVoices.load()[saved.gatewayUrl to null]?.on)
            }
        }
    }

    /** What a call just found out of [gateway]'s live voice, so the watch is told it next, not minutes on. */
    fun liveVoiceIs(gateway: GatewayUrl, on: Boolean, profile: String?) {
        val found = LiveVoice(on, Clock.System.now().toEpochMilliseconds())
        liveVoices.updateAndFetch { all -> all.filterKeys { it.first == gateway } + ((gateway to profile) to found) }
    }

    /**
     * The watch's chat [chatId] (null: a new one) for a live call from the watch, once the gateway is connected and
     * its history loaded (the call opens with it); else what went wrong, for the watch to say.
     */
    suspend fun chatForCall(chatId: String?, botProfile: String? = null, defaultRequestId: String? = null, node: String? = null, scope: WatchScope? = null): CallChat {
        val saved = gateways.current()
        if (saved == null || !auth.hasStoredSession(saved.gatewayUrl) || connection.state.value is ConnectionState.SessionExpired) {
            return CallChat.Failed(SIGNED_OUT)
        }
        val gateway = saved.gatewayUrl
        if (scope != null && (scope.gateway != gateway.value || botProfile != null && scope.profile != botProfile)) {
            return CallChat.Failed("Conversation changed. Choose it again on your watch.")
        }
        val default = turnLock.withLock {
            if (defaultRequestId == null) null else defaults[node]?.takeIf { it.id == defaultRequestId }
        }
        if (defaultRequestId != null && (default == null || !defaultCallCurrent(node.orEmpty(), defaultRequestId) ||
                default.resolved.chatId != chatId || default.resolved.botProfile != botProfile ||
                scope != null && scope != default.resolved.scope)) {
            return CallChat.Failed("Default Call Target changed. Tap Call again.")
        }
        val profile = if (default != null) default.profile else if (scope != null) scope.profile else botProfile ?: profiles.get(gateway)
        connection.start(gateway)
        if (withTimeoutOrNull(CONNECT_MS) { connection.state.first { it is ConnectionState.Connected } } == null) {
            connection.retry()
            return CallChat.Failed("Kyllene can't reach your gateway right now.")
        }
        val session = turnLock.withLock {
            // Another call on, from the phone or the watch: one chat is held at a time, and taking it would cut that
            // call off mid-word. (Each call is counted before it gets here, this one too.)
            if (calls.load() > 1) return CallChat.Failed(ON_ANOTHER_CALL)
            if (default != null && !defaultCurrent(default)) return CallChat.Failed("Default Call Target changed. Tap Call again.")
            val storedId = try { if (botProfile == null) chatId else openBot(botProfile) } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return CallChat.Failed(e.message ?: "Couldn't open the bot's conversation.")
            }
            // A call takes over from an ask still being answered: one voice at a time.
            turn?.cancelAndJoin()
            sessionFor(gateway, profile, storedId)
        }
        withTimeoutOrNull(CONNECT_MS) { session.state.first { it.historyLoaded } }
        if (default != null && !defaultCurrent(default)) return CallChat.Failed("Default Call Target changed. Tap Call again.")
        return CallChat.Ready(session, gateway, profile)
    }

    private data class DefaultResolution(val id: String, val gateway: GatewayUrl, val target: CallTarget,
        val profile: String?, val resolved: DefaultCallTarget)
    private val latestDefault = AtomicReference<Map<String, String>>(emptyMap())
    private val defaults = mutableMapOf<String?, DefaultResolution>()

    /** Unscoped saved sessions belong to the launch profile; fresh calls follow the selected profile. */
    private suspend fun defaultProfile(target: CallTarget, gateway: GatewayUrl): String? =
        target.profile ?: if (target.sessionId == null) profiles.get(gateway) else null

    private suspend fun defaultCurrent(value: DefaultResolution): Boolean =
        gateways.current()?.gatewayUrl == value.gateway &&
            (settings.settings.value?.callTargets?.get(value.gateway.value) ?: CallTarget()) == value.target &&
            defaultProfile(value.target, value.gateway) == value.profile

    suspend fun defaultCallCurrent(node: String, requestId: String): Boolean {
        val value = turnLock.withLock { defaults[node]?.takeIf { it.id == requestId } } ?: return false
        return latestDefault.load()[node] == requestId && defaultCurrent(value)
    }

    /** Resolve before the watch opens its microphone; keep the connection up for this bounded request. */
    fun defaultCall(node: String, requestId: String) {
        latestDefault.updateAndFetch { it + (node to requestId) }
        lists.incrementAndFetch()
        refreshBusy()
        scope.launch {
            val result = try {
                withTimeout(CONNECT_MS) {
                    val saved = gateways.current() ?: error(SIGNED_OUT)
                    check(auth.hasStoredSession(saved.gatewayUrl)) { SIGNED_OUT }
                    val prefs = settings.settings.filterNotNull().first()
                    val target = prefs.callTargets[saved.url] ?: CallTarget()
                    require(target.valid) { "Invalid Default Call Target. Choose it again on your phone." }
                    val profile = defaultProfile(target, saved.gatewayUrl)
                    connection.start(saved.gatewayUrl)
                    connection.state.first { it is ConnectionState.Connected }
                    val bot = target.profile?.let { name -> bots.roster().bots.firstOrNull { it.name == name }
                        ?: error("That bot no longer exists. Choose another target on your phone.") }
                    val chatId = if (bot != null) botChats.open(bot) else target.sessionId
                    if (chatId != null) check(sessions.messages(saved.gatewayUrl, chatId, limit = 1, profile = profile) is ApiResult.Success) {
                        "Couldn't open Default Call Target. Check it on your phone."
                    }
                    // Not live (or an older gateway that can't say): the watch asks there by voice instead.
                    val live = audio.voiceLiveStatus(saved.gatewayUrl, profile).let { it is ApiResult.Success && it.value.live }
                    val resolved = DefaultCallTarget(requestId, chatId, target.profile, bot?.label ?: target.label,
                        scope = WatchScope(saved.gatewayUrl.value, profile), live = live)
                    val value = DefaultResolution(requestId, saved.gatewayUrl, target, profile, resolved)
                    check(defaultCurrent(value)) { "Default Call Target changed. Tap Call again." }
                    turnLock.withLock {
                        check(latestDefault.load()[node] == requestId) { "A newer call replaced this request." }
                        defaults[node] = value
                    }
                    resolved
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                // A connection that gave up stays down unless told to try again, as the other waits here do.
                if (connection.state.value !is ConnectionState.Connected) connection.retry()
                DefaultCallTarget(requestId, error = "Couldn't reach Default Call Target. Try again.")
            } catch (e: Exception) {
                DefaultCallTarget(requestId, error = e.message ?: "Couldn't open Default Call Target.")
            } finally {
                lists.updateAndFetch { (it - 1).coerceAtLeast(0) }
                refreshBusy()
            }
            outbox.sendSafely(node, WearPaths.DEFAULT_CALL, WearJson.encode(result))
        }
    }

    /** A live call from the watch is on in [session]'s chat: the connection stays up, and its turns aren't notified. */
    fun callStarted() {
        calls.incrementAndFetch()
        refreshBusy()
        scope.launch { turnLock.withLock { releaseLater?.cancel() } }
    }

    /** The call's chat got its id (a new chat, with the first request), or kept it. */
    fun callChat(storedId: String) {
        scope.launch { turnLock.withLock { if (heldChat != storedId) holdChat(storedId) } }
    }

    fun callEnded() {
        val left = calls.updateAndFetch { (it - 1).coerceAtLeast(0) }
        refreshBusy()
        if (left == 0) scope.launch { turnLock.withLock { releaseHeldChatLater() } }
    }

    /** Separate recent chats and bots for the watch [node]; listing holds the connection until the reply goes. */
    fun chats(node: String, requestId: String? = null) {
        lists.incrementAndFetch()
        refreshBusy()
        scope.launch {
            try {
                outbox.sendSafely(node, WearPaths.CHATS, WearJson.encode(readChats().copy(requestId = requestId)))
            } finally {
                lists.updateAndFetch { (it - 1).coerceAtLeast(0) }
                refreshBusy()
            }
        }
    }

    private suspend fun readChats(): ChatList {
        val saved = gateways.current() ?: return ChatList(error = SIGNED_OUT, botError = SIGNED_OUT)
        if (!auth.hasStoredSession(saved.gatewayUrl)) return ChatList(error = SIGNED_OUT, botError = SIGNED_OUT)
        val profile = profiles.get(saved.gatewayUrl)
        val result = sessions.list(saved.gatewayUrl, limit = CHAT_LIST_SIZE, profile = profile)
        val chats = if (result is ApiResult.Success) result.value.sessions
            .filter { !it.archived && it.title != BotsApi.BOT_CHAT_TITLE }
            .map { WatchChat(it.id, it.displayTitle.take(MAX_TITLE), it.activityAt) } else emptyList()
        var botError: String? = null
        val rows = try {
            connection.start(saved.gatewayUrl)
            if (withTimeoutOrNull(CONNECT_MS) { connection.state.first { it is ConnectionState.Connected } } == null) {
                connection.retry()
                error("Kyllene can't reach your gateway right now.")
            }
            val roster = bots.roster().bots
            botChats.note(roster)
            roster.filter { !it.meta.hidden }.forRoster().map { WatchBot(it.name, it.label.take(MAX_TITLE), it.canonicalSession?.activityAt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            botError = e.message ?: "Couldn't read your bots."
            emptyList()
        }
        return ChatList(chats, if (result is ApiResult.Success) null else result.errorMessage ?: "Couldn't read your chats.", rows, botError, scope = WatchScope(saved.gatewayUrl.value, profile))
    }

    private suspend fun openBot(profile: String): String {
        val roster = bots.roster().bots
        botChats.note(roster)
        val bot = roster.firstOrNull { it.name == profile } ?: error("This bot is no longer available. Choose another conversation.")
        return botChats.open(bot)
    }

    fun close() {
        _session.value?.let { closeSession(it) }
        heldChat?.let(host::releaseSideChat)
        scope.cancel()
    }

    private suspend fun answer(node: String, ask: WatchAsk, recording: ByteArray?) {
        var seq = 0
        var last = ReplyUpdate(ask.id, ReplyPhase.Sending, chatId = ask.chatId)
        suspend fun tell(update: ReplyUpdate) {
            last = update.copy(seq = ++seq)
            outbox.sendSafely(node, WearPaths.REPLY, WearJson.encode(last))
        }
        suspend fun fail(message: String) = tell(last.copy(phase = ReplyPhase.Failed, error = message, activity = null))

        val saved = gateways.current()
        if (saved == null || !auth.hasStoredSession(saved.gatewayUrl) || connection.state.value is ConnectionState.SessionExpired) {
            return fail(SIGNED_OUT)
        }
        val gateway = saved.gatewayUrl
        val botProfile = ask.botProfile
        val owner = ask.scope
        if (owner != null && (owner.gateway != gateway.value || botProfile != null && owner.profile != botProfile)) {
            return fail("Conversation changed. Choose it again on your watch.")
        }
        val profile = if (owner != null) owner.profile else botProfile ?: profiles.get(gateway)
        last = last.copy(scope = WatchScope(gateway.value, profile))
        connection.start(gateway)

        val storedId = if (botProfile == null) ask.chatId else try {
            if (withTimeoutOrNull(CONNECT_MS) { connection.state.first { it is ConnectionState.Connected } } == null) {
                connection.retry()
                return fail("Kyllene can't reach your gateway right now.")
            }
            openBot(botProfile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(e.message ?: "Couldn't open the bot's conversation.")
        }

        var text = ask.text.trim()
        if (recording != null) {
            tell(last.copy(phase = ReplyPhase.Transcribing))
            val heard = when (val result = audio.transcribe(gateway, recording, ask.audioType ?: "audio/wav", profile)) {
                is ApiResult.Success -> result.value.trim()
                else -> return fail(result.errorMessage ?: "Couldn't write down what you said.")
            }
            if (heard.isEmpty()) return fail(NOTHING_HEARD)
            text = heard
            last = last.copy(heard = heard)
        }
        if (text.isEmpty()) return fail(NOTHING_HEARD)

        tell(last.copy(phase = ReplyPhase.Sending))
        if (withTimeoutOrNull(CONNECT_MS) { connection.state.first { it is ConnectionState.Connected } } == null) {
            connection.retry()
            return fail("Kyllene can't reach your gateway right now.")
        }
        val session = sessionFor(gateway, profile, storedId)
        // A chat picked on the watch loads its history first; sent before it, the reply would be found among the old.
        withTimeoutOrNull(CONNECT_MS) { session.state.first { it.historyLoaded } }
        storedId?.let(::holdChat)
        if (!session.send(text)) return fail(session.state.value.error ?: "Couldn't send that.")
        session.state.value.storedSessionId?.let(::holdChat)

        // The reply as it streams, a few times a second at most: each update is a whole message to the watch.
        // Its own job, joined, so its last tell can't race the final one below for the next seq.
        val streaming = scope.launch {
            session.state.map(::progressOf).distinctUntilChanged().conflate().collect { progress ->
                tell(
                    last.copy(
                        phase = progress.phase,
                        chatId = progress.chatId,
                        title = progress.title,
                        text = progress.text,
                        activity = progress.activity,
                        model = progress.model,
                        contextPercent = progress.contextPercent,
                    ),
                )
                delay(UPDATE_EVERY_MS)
            }
        }
        try {
            withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
            session.state.first { !it.running }
        } finally {
            withContext(NonCancellable) { streaming.cancelAndJoin() }
        }

        val state = session.state.value
        state.storedSessionId?.let(::holdChat)
        val reply = replyAfter(state)
        val done = last.copy(
            chatId = state.storedSessionId ?: last.chatId,
            title = state.title,
            activity = null,
            model = modelOf(state) ?: last.model,
            contextPercent = contextPercentOf(state) ?: last.contextPercent,
        )
        if (reply?.outcome == TurnOutcome.Error) {
            return tell(done.copy(phase = ReplyPhase.Failed, text = clipForWatch(reply.text), error = reply.error ?: "The turn failed."))
        }
        val replyText = reply?.text.orEmpty()
        val spoken = if (ask.speak && reply?.outcome != TurnOutcome.Interrupted) speakableText(replyText) else ""
        tell(done.copy(phase = ReplyPhase.Done, text = clipForWatch(replyText), speech = if (spoken.isEmpty()) ReplySpeech.Off else ReplySpeech.Coming))
        if (spoken.isNotEmpty() && !speak(node, ask.id, spoken, gateway, profile)) {
            tell(last.copy(speech = ReplySpeech.Unavailable))
        }
    }

    /**
     * Voices [text] in the gateway's voice, clip by clip, each sent as soon as it's made so the first plays
     * while the rest are made. False when not even the first could be made or sent: the watch reads it itself.
     */
    private suspend fun speak(node: String, askId: String, text: String, gateway: GatewayUrl, profile: String?): Boolean {
        // Not waited on: the first clip shouldn't wait for it. Let go even when the watch stops the voice
        // (the turn cancelled), after the lease itself has gone out.
        val lease = scope.launch { audio.ttsLease(gateway, true, profile) }
        try {
            val chunks = speechChunks(text)
            chunks.forEachIndexed { index, chunk ->
                val clip = when (val result = audio.speak(gateway, chunk, profile)) {
                    is ApiResult.Success -> result.value
                    else -> return index > 0
                }
                if (!outbox.streamSafely(node, WearPaths.speechPath(askId, index, chunks.size, clip.mimeType), clip.bytes)) return index > 0
            }
            return true
        } finally {
            scope.launch {
                lease.join()
                audio.ttsLease(gateway, false, profile)
            }
        }
    }

    /**
     * Keeps the watch's chat [storedId] out of the phone's watching while the watch tells its turn. Let go a little
     * after the turn ([releaseHeldChatLater]), so a turn run there later, from the desktop or the phone, is told again.
     */
    private fun holdChat(storedId: String) {
        heldChat?.takeIf { it != storedId }?.let(host::releaseSideChat)
        heldChat = storedId
        host.holdSideChat(storedId)
    }

    /** Let go after a while: the watcher may still be about to see the turn's end, which the watch already told. */
    private fun releaseHeldChatLater() {
        val held = heldChat ?: return
        releaseLater?.cancel()
        releaseLater = scope.launch {
            delay(RELEASE_AFTER_MS)
            turnLock.withLock {
                if (heldChat == held && turn?.isActive != true && calls.load() == 0) {
                    host.releaseSideChat(held)
                    heldChat = null
                }
            }
        }
    }

    /** The watch's chat [chatId] (null: a new one), kept from the last ask when it's the same. */
    private fun sessionFor(gateway: GatewayUrl, profile: String?, chatId: String?): ChatSession {
        val current = _session.value
        if (current != null && chatId != null && sessionGateway == gateway && sessionProfile == profile &&
            current.state.value.storedSessionId == chatId
        ) {
            return current
        }
        current?.let { closeSession(it) }
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = childScope
        sessionGateway = gateway
        sessionProfile = profile
        return ChatSession(gateway, chatId, null, connection, sessions, childScope, profile, startInstruction = host.startInstruction).also {
            it.start()
            _session.value = it
        }
    }

    private fun closeSession(session: ChatSession) {
        session.stop()
        sessionScope?.cancel()
        sessionScope = null
        _session.update { if (it === session) null else it }
    }

    companion object {
        const val SIGNED_OUT = "Sign in to Kyllene on your phone first."
        const val ON_ANOTHER_CALL = "You're already on a call with Hermes. Hang up there first."
        const val NOTHING_HEARD = "I didn't catch that."
        private const val CHAT_LIST_SIZE = 15
        private const val MAX_TITLE = 80
        private const val CONNECT_MS = 10_000L
        private const val TURN_START_MS = 15_000L
        private const val UPDATE_EVERY_MS = 400L
        private const val RELEASE_AFTER_MS = 30_000L
        private const val LIVE_VOICE_CHECK_MS = 5 * 60_000L
    }
}

/** The watch's chat for a live call: [Ready] with what the call runs in, or [Failed] with why not. */
sealed interface CallChat {
    data class Ready(val session: ChatSession, val gateway: GatewayUrl, val profile: String?) : CallChat
    data class Failed(val message: String) : CallChat
}

/** The turn as the watch shows it while it runs. */
internal data class TurnProgress(
    val phase: ReplyPhase,
    val chatId: String?,
    val title: String?,
    val text: String,
    val activity: String?,
    val model: String? = null,
    val contextPercent: Int? = null,
)

/** The chat's model by the name people know it, for the watch's edge. */
fun modelOf(state: ChatState): String? = state.model?.takeIf { it.isNotBlank() }?.let(::displayModelName)

/** How full the context window is, 0–100: the gateway's own figure, else worked out from the tokens. */
fun contextPercentOf(state: ChatState): Int? {
    val usage = state.usage ?: return null
    usage.contextPercent?.let { return it.coerceIn(0, 100) }
    val used = usage.contextUsed ?: return null
    val max = usage.contextMax?.takeIf { it > 0 } ?: return null
    return (used * 100 / max).toInt().coerceIn(0, 100)
}

/** Where the turn for the last prompt stands in [state]. */
internal fun progressOf(state: ChatState): TurnProgress {
    val reply = replyAfter(state)
    val waiting = state.inputRequests.isNotEmpty()
    val tool = reply?.tools?.lastOrNull { it.running }
    val activity = when {
        waiting -> "Waiting for you on your phone"
        tool != null -> toolInProgress(tool.name)
        else -> state.status?.takeIf { it.isNotBlank() }
    }
    val phase = when {
        waiting -> ReplyPhase.WaitingOnPhone
        state.running || reply != null -> ReplyPhase.Working
        else -> ReplyPhase.Sending
    }
    return TurnProgress(phase, state.storedSessionId, state.title, clipForWatch(reply?.text.orEmpty()), activity, modelOf(state), contextPercentOf(state))
}

/**
 * The reply to the last prompt: the last answer after it. Found from the end, not by position, since the history
 * reloads at the turn's end and may then start at another row.
 */
internal fun replyAfter(state: ChatState): ChatMessage.Assistant? {
    val prompt = state.messages.indexOfLast { it is ChatMessage.User }
    if (prompt < 0) return null
    return state.messages.drop(prompt + 1).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
}

/** Catches what a send to the watch may throw, so one lost message doesn't end the turn's updates. */
internal suspend fun WatchOutbox.sendSafely(node: String, path: String, data: ByteArray): Boolean = safely { send(node, path, data) }

internal suspend fun WatchOutbox.streamSafely(node: String, path: String, data: ByteArray): Boolean = safely { stream(node, path, data) }

private inline fun safely(block: () -> Boolean): Boolean = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}
