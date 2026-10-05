package dev.hermeskotlin.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotChatUnavailableException
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.SidebarMode
import dev.hermeskotlin.core.bots.SidebarModeStore
import dev.hermeskotlin.core.bots.forRoster
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BotsUiState(
    /** The roster in Desktop's order, hidden bots left out. */
    val bots: List<Bot> = emptyList(),
    /** Nothing loaded yet. */
    val loading: Boolean = true,
    /** The roster itself failed to load. */
    val error: String? = null,
    /** The gateway predates Bot Mode's roster call. */
    val unsupported: Boolean = false,
    /** Bots whose chat has something the user hasn't seen. */
    val unread: Set<String> = emptySet(),
    /** The bot whose chat is being found or started. */
    val opening: String? = null,
    /** Why the last tapped bot's chat didn't open. */
    val openError: String? = null,
)

/**
 * The Bots side of the sidebar: the gateway's profiles as bots (`profiles.list`), refreshed every few
 * seconds while it's on screen like Desktop's roster, and opening a bot's one permanent chat through
 * [BotChats]. Also remembers which side of the sidebar was last showing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModel(
    private val api: BotsApi,
    private val chats: BotChats,
    private val connection: GatewayConnection,
    private val seenStore: SeenStore,
    private val modes: SidebarModeStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BotsUiState())
    val state: StateFlow<BotsUiState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(SidebarMode.Chats)
    val mode: StateFlow<SidebarMode> = _mode.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Pictures of the bots that have one, by profile name. */
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()
    private val avatarsAsked = mutableSetOf<String>()

    private val gateway = MutableStateFlow<GatewayUrl?>(null)
    private val visible = MutableStateFlow(false)

    /** The stored session open in the chat pane, so its bot's chat counts as read. */
    private var openSession: String? = null

    init {
        // Poll while the roster is on screen and the socket is up; a reconnect refreshes at once.
        viewModelScope.launch {
            combine(gateway, visible, _mode, connection.state) { url, shown, mode, conn ->
                url != null && shown && mode == SidebarMode.Bots && conn is ConnectionState.Connected
            }.distinctUntilChanged().collectLatest { live ->
                while (live) {
                    refreshNow()
                    delay(POLL_MS)
                }
            }
        }
    }

    fun bind(url: GatewayUrl) {
        if (gateway.value == url) return
        gateway.value = url
        _state.value = BotsUiState()
        _avatars.value = emptyMap()
        avatarsAsked.clear()
        viewModelScope.launch { _mode.value = modes.get(url) }
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun setMode(mode: SidebarMode) {
        _mode.value = mode
        val url = gateway.value ?: return
        viewModelScope.launch { modes.set(url, mode) }
    }

    /** The chat pane now shows [storedSessionId]; a bot's chat open there is read. */
    fun setOpenSession(storedSessionId: String?) {
        openSession = storedSessionId
        viewModelScope.launch { markOpenSeen(_state.value.bots) }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    fun dismissOpenError() = _state.update { it.copy(openError = null) }

    /**
     * Finds [bot]'s chat (starting it when the bot has never had one) and hands its stored id to [onOpened].
     * When that can't be done safely the row says so instead, and the user can tap again.
     */
    fun open(bot: Bot, onOpened: (storedSessionId: String) -> Unit) {
        if (_state.value.opening != null) return
        _state.update { it.copy(opening = bot.name, openError = null) }
        viewModelScope.launch {
            try {
                val id = chats.open(bot)
                openSession = id
                onOpened(id)
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotChatUnavailableException) {
                _state.update { it.copy(openError = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(openError = "Couldn't open ${bot.label}'s chat. ${e.message.orEmpty()}".trim()) }
            } finally {
                _state.update { it.copy(opening = null) }
            }
        }
    }

    private suspend fun refreshNow() {
        val url = gateway.value ?: return
        try {
            val roster = api.roster()
            if (gateway.value != url) return
            chats.note(roster.bots)
            val bots = roster.bots.forRoster()
            markOpenSeen(bots)
            val unread = unread(url, bots)
            _state.update { it.copy(bots = bots, loading = false, error = null, unsupported = false, unread = unread) }
            loadAvatars(bots)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            val unsupported = UNKNOWN_METHOD.containsMatchIn(e.message)
            _state.update { it.copy(loading = false, unsupported = unsupported, error = e.message.takeUnless { unsupported }) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the bots.") }
        }
    }

    /** Read state is kept per bot profile, on its Bot Chat's stable id. */
    private suspend fun unread(url: GatewayUrl, bots: List<Bot>): Set<String> = bots.mapNotNull { bot ->
        val chat = bot.chatSummary() ?: return@mapNotNull null
        bot.name.takeIf { seenStore.seen(url, bot.name).first().isUnread(chat) }
    }.toSet()

    private suspend fun markOpenSeen(bots: List<Bot>) {
        val url = gateway.value ?: return
        val open = openSession ?: return
        val bot = bots.firstOrNull { it.canonicalSession?.let { chat -> open == chat.id || open == chat.openId } == true } ?: return
        val chat = bot.chatSummary() ?: return
        seenStore.markSeen(url, chat, bot.name)
        _state.update { it.copy(unread = it.unread - bot.name) }
    }

    private fun loadAvatars(bots: List<Bot>) {
        val wanted = bots.filter { it.hasAvatar && avatarsAsked.add(it.name) }
        // A picture taken away on another client: draw the shape again.
        _avatars.update { current -> current.filterKeys { name -> bots.any { it.name == name && it.hasAvatar } } }
        wanted.forEach { bot ->
            viewModelScope.launch {
                val bytes = runCatching { api.avatar(bot.name) }.getOrNull()
                if (bytes == null) avatarsAsked.remove(bot.name) else _avatars.update { it + (bot.name to bytes) }
            }
        }
    }

    private fun Bot.chatSummary(): SessionSummary? {
        val chat = canonicalSession ?: return null
        val id = chat.id ?: return null
        return SessionSummary(id = id, lastActive = chat.lastActive, startedAt = chat.startedAt)
    }

    /** Seconds on the gateway's clock, near enough for "working in the last two minutes". */
    fun nowSeconds(): Double = Clock.System.now().toEpochMilliseconds() / 1000.0

    private companion object {
        /** Desktop's roster poll; the gateway answers from a cache while nothing changed. */
        const val POLL_MS = 5_000L
        val UNKNOWN_METHOD = Regex("method not found|unknown method|no handler", RegexOption.IGNORE_CASE)
    }
}
