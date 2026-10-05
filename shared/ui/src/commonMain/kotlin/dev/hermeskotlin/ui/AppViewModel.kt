package dev.hermeskotlin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import dev.hermeskotlin.ui.chat.BotIdentity
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.ui.chat.ChatTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

sealed interface Route {
    data object Loading : Route
    data object Connect : Route
    data class SignIn(val gateway: SavedGateway, val notice: String? = null) : Route

    /** The signed-in home: one chat (stored, or new when `target.storedSessionId` is null) with the sessions sidebar beside it. */
    data class Chat(val target: ChatTarget) : Route {
        val gateway: SavedGateway get() = target.gateway
    }
}

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
) : ViewModel() {

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _chatsProfile = MutableStateFlow<String?>(null)

    /**
     * The profile picked for chats (null: the launch profile). A bot's chat runs in the bot's own profile
     * without changing this, so the chat list and the next new chat stay where they were.
     */
    val chatsProfile: StateFlow<String?> = _chatsProfile.asStateFlow()

    private var newChatCount = 0L

    init {
        viewModelScope.launch {
            val saved = gateways.current()
            _route.value = when {
                saved == null -> Route.Connect
                auth.hasStoredSession(saved.gatewayUrl) -> home(saved).also { connection.start(saved.gatewayUrl) }
                else -> Route.SignIn(saved)
            }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.SessionExpired) onSessionExpired()
            }
        }
        // A tapped notification names its chat; open it once signed in (it waits through Loading).
        viewModelScope.launch {
            combine(links.pending, _route) { link, route -> link?.takeIf { route is Route.Chat } }
                .filterNotNull()
                .collect { link ->
                    links.consume(link)
                    val open = (_route.value as? Route.Chat)?.target?.storedSessionId
                    if (open != link.storedSessionId) openSession(link.storedSessionId, link.title ?: "Chat")
                }
        }
    }

    fun onGatewayChosen(gateway: SavedGateway) {
        viewModelScope.launch {
            gateways.save(gateway)
            _route.value = Route.SignIn(gateway)
        }
    }

    fun onSignedIn(gateway: SavedGateway) {
        connection.start(gateway.gatewayUrl)
        viewModelScope.launch { _route.value = home(gateway) }
    }

    fun openSession(sessionId: String, title: String) {
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(ChatTarget(gateway, sessionId, title, profile = currentProfile()))
    }

    fun newChat() {
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(newChatTarget(gateway, currentProfile()))
    }

    /** Opens [bot]'s permanent chat, the stored session [storedSessionId], in the bot's own profile. */
    fun openBotChat(bot: Bot, storedSessionId: String) {
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(botChatTarget(gateway, storedSessionId, bot.name, bot.label))
    }

    private fun botChatTarget(gateway: SavedGateway, storedSessionId: String, name: String, label: String) =
        ChatTarget(gateway, storedSessionId, label, profile = name, bot = BotIdentity(name, label, chatsProfile = currentProfile()))

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
        val gateway = signedInGateway() ?: return
        if (profile == currentProfile()) return
        viewModelScope.launch {
            profiles.set(gateway.gatewayUrl, profile)
            _route.value = home(gateway)
        }
    }

    /** The dashboard rejected our cookies (socket or REST): back to sign-in with a notice. */
    fun onSessionExpired() {
        val gateway = signedInGateway() ?: return
        connection.stop()
        host.close()
        _route.value = Route.SignIn(gateway, notice = "Your session expired. Sign in again.")
    }

    fun signOut() {
        val gateway = currentGateway() ?: return
        connection.stop()
        host.close()
        viewModelScope.launch {
            auth.signOut(gateway.gatewayUrl)
            _route.value = Route.SignIn(gateway)
        }
    }

    fun changeGateway() {
        val gateway = currentGateway()
        connection.stop()
        host.close()
        viewModelScope.launch {
            gateway?.let { auth.signOut(it.gatewayUrl) }
            gateways.clear()
            _route.value = Route.Connect
        }
    }

    /** System back. Returns false when there is nowhere to go back to (let the platform close the app). */
    fun back(): Boolean = when (_route.value) {
        is Route.SignIn -> {
            changeGateway()
            true
        }
        else -> false
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

    private fun signedInGateway(): SavedGateway? = (_route.value as? Route.Chat)?.gateway

    private fun currentProfile(): String? = _chatsProfile.value

    private fun currentGateway(): SavedGateway? = (_route.value as? Route.SignIn)?.gateway ?: signedInGateway()
}
