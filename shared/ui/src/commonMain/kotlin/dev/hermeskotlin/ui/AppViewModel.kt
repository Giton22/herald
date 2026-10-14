package dev.hermeskotlin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.cache.OfflineCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import dev.hermeskotlin.ui.chat.BotIdentity
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.DraftStart
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.GatewayList
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.inbox.InboxStore
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.ui.chat.ChatTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Route {
    data object Loading : Route

    /** Enter a gateway address. [canCancel] when another gateway is saved to go back to. */
    data class Connect(val canCancel: Boolean = false) : Route

    /** Sign in to [gateway]. [adding] when it was just entered on [Connect], which Back returns to. */
    data class SignIn(val gateway: SavedGateway, val notice: String? = null, val adding: Boolean = false) : Route

    /** The signed-in home: one chat (stored, or new when `target.storedSessionId` is null) with the sessions sidebar beside it. */
    data class Chat(val target: ChatTarget) : Route {
        val gateway: SavedGateway get() = target.gateway
    }
}

/** The saved gateways to switch between, and which of them still hold a session (switch without signing in). */
data class GatewayChoices(val list: GatewayList = GatewayList(), val signedIn: Set<String> = emptySet())

/** Top-level flow: pick gateway → sign in → chat, reopening where the user left off. Owns the gateway connection lifecycle. */
class AppViewModel(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
    private val lastChats: LastChatStore,
    private val host: ChatHost,
    private val profiles: ProfileStore,
    private val links: ChatLinks,
    private val botChats: BotChats,
    private val push: PushSetup,
    private val access: AccessTokens,
    private val settings: SettingsStore,
    private val sessions: SessionsApi,
    private val botsApi: BotsApi,
    /** The chats saved on this device, which go with signing out. */
    private val cache: OfflineCache? = null,
    /** Where the user last looked in each chat, so one opened from a notification starts at its new messages. */
    private val seen: SeenStore? = null,
    /** Each gateway's Inbox kept on the phone, which goes with the gateway. */
    private val inbox: InboxStore? = null,
) : ViewModel() {

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _chatsProfile = MutableStateFlow<String?>(null)

    /**
     * The profile picked for chats (null: the launch profile). A bot's chat runs in the bot's own profile
     * without changing this, so the chat list and the next new chat stay where they were.
     */
    val chatsProfile: StateFlow<String?> = _chatsProfile.asStateFlow()

    // Bumped when a sign-in or sign-out changes which gateways hold a session; the list alone doesn't show it.
    private val sessionsChanged = MutableStateFlow(0)

    val gatewayChoices: StateFlow<GatewayChoices> = combine(gateways.list, sessionsChanged) { list, _ ->
        GatewayChoices(list, list.gateways.filter { auth.hasStoredSession(it.gatewayUrl) }.mapTo(mutableSetOf()) { it.url })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GatewayChoices())

    private var newChatCount = 0L
    private var shownCallTarget: ChatTarget? = null

    /** Present each call once; rotation and unread-position updates keep later UI state. */
    internal fun consumeCallNavigation(target: ChatTarget): Boolean {
        if (target.draft?.start != DraftStart.VoiceChat || shownCallTarget?.copy(readUpTo = null) == target.copy(readUpTo = null)) return false
        shownCallTarget = target
        return true
    }

    private var callBotTarget: ChatTarget? = null
    private var initialCallBotChat: String? = null

    /** Keep the pre-call roster from redirecting a resolved call; later bot chat moves still follow. */
    internal fun shouldFollowBotChat(target: ChatTarget, canonical: BotSession?): Boolean {
        if (target.draft?.start != DraftStart.VoiceChat) return true
        if (callBotTarget?.copy(readUpTo = null) != target.copy(readUpTo = null)) {
            callBotTarget = target
            initialCallBotChat = null
        }
        if (initialCallBotChat == null) initialCallBotChat = canonical?.openId
        return canonical?.openId != initialCallBotChat && canonical?.isChat(target.storedSessionId) != true
    }

    private var callJob: Job? = null
    private var callGeneration = 0L
    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()
    private val _openingCall = MutableStateFlow(false)
    val openingCall: StateFlow<Boolean> = _openingCall.asStateFlow()
    fun dismissCallError() { _callError.value = null }
    /** Cancels target resolution before any voice request is handed to the chat. */
    fun cancelPendingCall() {
        callGeneration++
        callJob?.cancel()
        _openingCall.value = false
        dismissCallError()
    }

    init {
        viewModelScope.launch {
            // Before anything connects: drops tokens entered for an address that was tested but never signed in to.
            forgetUnusedAccessTokens()
            val list = gateways.all()
            val saved = list.current ?: list.primary?.also { gateways.select(it.url) }
            _route.value = when {
                saved == null -> Route.Connect()
                auth.hasStoredSession(saved.gatewayUrl) -> home(saved).also { connection.start(saved.gatewayUrl) }
                else -> Route.SignIn(saved)
            }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.SessionExpired) onSessionExpired()
            }
        }
        viewModelScope.launch {
            _route.collect { cancelPendingCall() }
        }
        // A tapped notification names its chat; open it once signed in (it waits through Loading).
        viewModelScope.launch {
            combine(links.pending, _route) { link, route -> link?.takeIf { route is Route.Chat } }
                .filterNotNull()
                .collect { link ->
                    links.consume(link)
                    cancelPendingCall()
                    if (link.draft?.start == DraftStart.VoiceChat) {
                        openDefaultCall()
                        return@collect
                    }
                    val open = (_route.value as? Route.Chat)?.target?.storedSessionId
                    val bot = link.bot
                    val stored = link.storedSessionId
                    when {
                        bot != null -> openBotLink(bot, link.title, stored, link.draft, stored?.let { lastLook(it, profile = bot) })
                        stored == null -> newChat(link.draft)
                        open != stored -> openSession(stored, link.title ?: "Chat", lastLook(stored, currentProfile()))
                        // Usually the notification is for the chat left open: the reply came in while away.
                        // It stays open, with the line placed from the last look.
                        else -> (_route.value as? Route.Chat)?.let { route ->
                            val readUpTo = lastLook(stored, route.target.profile)
                            _route.value = Route.Chat(route.target.copy(readUpTo = readUpTo))
                        }
                    }
                }
        }
    }

    /** Resolve before handing voice to the existing chat-opening flow; never call a fallback destination. */
    private fun openDefaultCall() {
        val route = _route.value as? Route.Chat ?: return
        dismissCallError()
        val generation = ++callGeneration
        _openingCall.value = true
        callJob = viewModelScope.launch {
            try {
                val target = settings.settings.filterNotNull().first().callTargets[route.gateway.url] ?: CallTarget()
                require(target.valid) { "Choose a valid Default Call Target in Settings." }
                val (bot, id) = if (target.profile != null) {
                    withTimeout(15_000) {
                        connection.state.first { it is ConnectionState.Connected }
                        if (_route.value !== route || generation != callGeneration) throw CancellationException()
                        val bot = botsApi.roster().bots.firstOrNull { it.name == target.profile }
                            ?: error("This bot is unavailable. Choose a Default Call Target in Settings.")
                        bot to botChats.open(bot)
                    }
                } else null to target.sessionId
                if (id != null) {
                    val page = sessions.messages(route.gateway.gatewayUrl, id, limit = 1, profile = target.profile)
                    check(page is ApiResult.Success) { "Couldn't open the Default Call Target. ${page.errorMessage.orEmpty()}" }
                }
                if (_route.value !== route || generation != callGeneration) return@launch
                val profile = target.profile ?: currentProfile().takeIf { target.sessionId == null }
                val chat = newChatTarget(route.gateway, profile).copy(
                    storedSessionId = id, title = bot?.label ?: if (id == null) "New chat" else target.label,
                    draft = ComposeDraft(start = DraftStart.VoiceChat),
                    bot = bot?.let { BotIdentity(it.name, it.label, chatsProfile = currentProfile()) },
                )
                _route.value = Route.Chat(chat)
            } catch (_: TimeoutCancellationException) {
                if (_route.value === route && generation == callGeneration) _callError.value = "Couldn’t reach the bot. Try again once connected."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_route.value === route && generation == callGeneration) _callError.value = e.message ?: "Couldn't open the Default Call Target. Try again."
            } finally {
                if (generation == callGeneration) _openingCall.value = false
            }
        }
    }

    /** An address entered on [Route.Connect]: a saved gateway that still holds a session opens right away. */
    fun onGatewayChosen(gateway: SavedGateway) {
        cancelPendingCall()
        viewModelScope.launch {
            val saved = gateways.all().find(gateway.url)
            if (saved != null && auth.hasStoredSession(saved.gatewayUrl)) open(saved)
            else _route.value = Route.SignIn(saved ?: gateway, adding = true)
        }
    }

    /** Saves [gateway] (adding it, if new) as the current one and opens its chats. */
    fun onSignedIn(gateway: SavedGateway) {
        cancelPendingCall()
        viewModelScope.launch {
            if (signedInGateway()?.url != gateway.url) host.close()
            val saved = gateways.save(gateway)
            sessionsChanged.update { it + 1 }
            connection.start(saved.gatewayUrl)
            _route.value = home(saved)
        }
    }

    /** Leaves for [Route.Connect] to enter another gateway; the current one stays saved and signed in. */
    fun addGateway() {
        cancelPendingCall()
        viewModelScope.launch { _route.value = Route.Connect(canCancel = gateways.current() != null) }
    }

    /** Back from adding a gateway to the current one. */
    fun cancelAddGateway() {
        cancelPendingCall()
        viewModelScope.launch {
            val current = gateways.current() ?: return@launch
            _route.value = if (auth.hasStoredSession(current.gatewayUrl)) home(current).also { connection.start(current.gatewayUrl) }
            else Route.SignIn(current)
        }
    }

    /** Makes [gateway] the current one: its chats when it still holds a session, else its sign-in. */
    fun switchGateway(gateway: SavedGateway) {
        cancelPendingCall()
        if (gateway.url == signedInGateway()?.url) return
        viewModelScope.launch { open(gateway) }
    }

    fun setPrimaryGateway(gateway: SavedGateway) {
        viewModelScope.launch { gateways.setPrimary(gateway.url) }
    }

    fun renameGateway(gateway: SavedGateway, name: String) {
        viewModelScope.launch { gateways.rename(gateway.url, name) }
    }

    /** Signs out of [gateway] and forgets it; removing the current one moves to the primary (or to [Route.Connect]). */
    fun removeGateway(gateway: SavedGateway) {
        if (gateway.url == currentGateway()?.url) cancelPendingCall()
        viewModelScope.launch {
            // First, so the chats go even if what follows waits on an unreachable gateway and the app is closed.
            cache?.forgetGateway(gateway.gatewayUrl)
            val wasCurrent = currentGateway()?.url == gateway.url || gateways.current()?.url == gateway.url
            if (wasCurrent) {
                // While the socket is still up: the gateway forgets this phone's push identity.
                push.forget()
                connection.stop()
                host.close()
            }
            auth.signOut(gateway.gatewayUrl)
            // Again: a list read that was under way may have saved itself since.
            cache?.forgetGateway(gateway.gatewayUrl)
            gateways.remove(gateway.url)
            inbox?.forget(gateway.gatewayUrl.value)
            forgetUnusedAccessTokens()
            sessionsChanged.update { it + 1 }
            if (wasCurrent) {
                val next = gateways.all().primary
                if (next == null) _route.value = Route.Connect() else open(next)
            }
        }
    }

    /** An Access token belongs to a host: it stays while a saved gateway uses that host, and goes with the last one. */
    private suspend fun forgetUnusedAccessTokens() {
        access.retainOnly(gateways.all().gateways.map { it.gatewayUrl.host })
    }

    /** Opens a stored chat; [readUpTo] is how far it had been read, when it has new messages since. */
    fun openSession(sessionId: String, title: String, readUpTo: Double? = null) {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(ChatTarget(gateway, sessionId, title, profile = currentProfile(), readUpTo = readUpTo))
    }

    /**
     * A new chat in the picked profile; [draft] fills its composer (shared from another app, a shortcut).
     * [cwd] is the folder it runs in, when it starts in a project.
     */
    fun newChat(draft: ComposeDraft? = null, cwd: String? = null) {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(newChatTarget(gateway, currentProfile()).copy(draft = draft, cwd = cwd))
    }

    /** Opens [bot]'s conversation [storedSessionId] that isn't its Bot Chat, in the bot's profile. */
    fun openBotSession(bot: Bot, storedSessionId: String, title: String) {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(ChatTarget(gateway, storedSessionId, title, profile = bot.name))
    }

    /** A new throwaway chat with [bot], apart from its permanent one. */
    fun newBotChat(bot: Bot) {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(newChatTarget(gateway, bot.name))
    }

    /**
     * Opens [bot]'s permanent chat, the stored session [storedSessionId], in the bot's own profile; [readUpTo] is
     * how far it had been read, when it has new messages since.
     */
    fun openBotChat(bot: Bot, storedSessionId: String, readUpTo: Double? = null) {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(botChatTarget(gateway, storedSessionId, bot.name, bot.label).copy(readUpTo = readUpTo))
    }

    /**
     * A bot's chat asked for from outside (notification, shortcut, link). With a known [storedSessionId] it
     * opens at once and is then checked against the gateway; without one it's looked up first, safely.
     * [draft] (a share to the bot) goes into its composer, after what the user had typed there.
     */
    private fun openBotLink(bot: String, label: String?, storedSessionId: String?, draft: ComposeDraft? = null, readUpTo: Double? = null) {
        val gateway = signedInGateway() ?: return
        val name = label?.takeIf { it.isNotBlank() } ?: Bot(name = bot).label
        if (storedSessionId != null) {
            _route.value = Route.Chat(botChatTarget(gateway, storedSessionId, bot, name, draft).copy(readUpTo = readUpTo).also(::followBotChat))
            return
        }
        viewModelScope.launch {
            connection.state.first { it is ConnectionState.Connected }
            val id = runCatching { botChats.open(Bot(name = bot)) }.getOrNull()
            when {
                id != null -> _route.value = Route.Chat(botChatTarget(gateway, id, bot, name, draft))
                // A share to a bot that's gone (an old shortcut) isn't lost: it waits in a new chat instead,
                // which says so, since a send from there goes to the profile's own assistant, not the bot.
                draft != null -> newChat(draft.withNotice("Couldn't open $name's chat, so the share is in a new chat."))
            }
        }
    }

    private fun botChatTarget(gateway: SavedGateway, storedSessionId: String, name: String, label: String, draft: ComposeDraft? = null) =
        ChatTarget(gateway, storedSessionId, label, profile = name, bot = BotIdentity(name, label, chatsProfile = currentProfile()), draft = draft)

    /**
     * A bot's chat reopened from a past launch may have moved on since (`/compress` continues it in a new
     * session), so ask the gateway where it lives now and follow it. The remembered id counts as proof the
     * bot has a chat, so an unsure answer never starts a second one; it just leaves the chat as it was.
     */
    private fun followBotChat(target: ChatTarget) {
        val bot = target.bot ?: return
        val stored = target.storedSessionId ?: return
        viewModelScope.launch {
            connection.state.first { it is ConnectionState.Connected }
            val live = try {
                botChats.open(Bot(name = bot.name, canonicalSession = BotSession(id = stored)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (live != stored && (_route.value as? Route.Chat)?.target == target) {
                _route.value = Route.Chat(target.copy(storedSessionId = live))
            }
        }
    }

    /**
     * Switches to [profile] (null: the launch profile) and back to where the user left off in it,
     * since its chats, memory and model are its own.
     */
    fun switchProfile(profile: String?) {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        if (profile == currentProfile()) return
        viewModelScope.launch {
            profiles.set(gateway.gatewayUrl, profile)
            _route.value = home(gateway)
        }
    }

    /** The dashboard rejected our cookies (socket or REST): back to sign-in with a notice. */
    fun onSessionExpired() {
        cancelPendingCall()
        val gateway = signedInGateway() ?: return
        connection.stop()
        host.close()
        _route.value = Route.SignIn(gateway, notice = "Your session expired. Sign in again.")
    }

    /** Signs out of the current gateway; it stays saved, so signing in again (or switching away) is one step. */
    fun signOut() {
        cancelPendingCall()
        val gateway = currentGateway() ?: return
        viewModelScope.launch {
            // First, so the chats go even if what follows waits on an unreachable gateway and the app is closed.
            cache?.forgetGateway(gateway.gatewayUrl)
            // While the socket is still up: the gateway forgets this phone's push identity.
            push.forget()
            connection.stop()
            host.close()
            auth.signOut(gateway.gatewayUrl)
            // Again: a list read that was under way may have saved itself since.
            cache?.forgetGateway(gateway.gatewayUrl)
            sessionsChanged.update { it + 1 }
            _route.value = Route.SignIn(gateway)
        }
    }

    /** System back. Returns false when there is nowhere to go back to (let the platform close the app). */
    fun back(): Boolean {
        val route = _route.value
        when {
            route is Route.SignIn && route.adding -> addGateway()
            route is Route.Connect && route.canCancel -> cancelAddGateway()
            else -> return false
        }
        return true
    }

    private suspend fun open(gateway: SavedGateway) {
        connection.stop()
        host.close()
        gateways.select(gateway.url)
        _route.value = if (auth.hasStoredSession(gateway.gatewayUrl)) home(gateway).also { connection.start(gateway.gatewayUrl) }
        else Route.SignIn(gateway)
    }

    /** The last chat the user had open on [gateway] in its picked profile, or a fresh one. */
    private suspend fun home(gateway: SavedGateway): Route.Chat {
        val profile = profiles.get(gateway.gatewayUrl)
        _chatsProfile.value = profile
        val last = lastChats.get(gateway.gatewayUrl, profile)
        val bot = last?.bot
        val target = when {
            last == null -> newChatTarget(gateway, profile)
            bot != null -> botChatTarget(gateway, last.sessionId, bot, last.botLabel ?: bot).also(::followBotChat)
            else -> ChatTarget(gateway, last.sessionId, last.title, profile = profile)
        }
        return Route.Chat(target)
    }

    // The nonce makes every new chat a fresh target, even right after another empty one.
    private fun newChatTarget(gateway: SavedGateway, profile: String?) =
        ChatTarget(gateway, storedSessionId = null, title = null, nonce = ++newChatCount, profile = profile)

    /**
     * How far the user had read [sessionId] in [profile], for a chat opened from a notification. A link there
     * means something came in, so the "New messages" line goes above whatever is newer than this; when
     * nothing is, there's no line.
     */
    private suspend fun lastLook(sessionId: String, profile: String?): Double? {
        val gateway = signedInGateway() ?: return null
        return seen?.lastLook(gateway.gatewayUrl, sessionId, profile)
    }

    private fun signedInGateway(): SavedGateway? = (_route.value as? Route.Chat)?.gateway

    private fun currentProfile(): String? = _chatsProfile.value

    private fun currentGateway(): SavedGateway? = (_route.value as? Route.SignIn)?.gateway ?: signedInGateway()
}
