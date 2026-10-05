package dev.hermeskotlin.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotDetails
import dev.hermeskotlin.core.bots.BotDraft
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.bots.displayNameFor
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.bots.BotChatUnavailableException
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.SidebarMode
import dev.hermeskotlin.core.bots.SidebarModeStore
import dev.hermeskotlin.core.bots.forRoster
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.cron.isRoutineOf
import dev.hermeskotlin.core.cron.problem
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.ui.chat.ModelPickerState
import dev.hermeskotlin.ui.chat.PendingSwitch
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

data class BotsUiState(
    /** The roster in Desktop's order, hidden bots left out. */
    val bots: List<Bot> = emptyList(),
    /** Bots the user hid: still working, kept apart at the bottom. */
    val hidden: List<Bot> = emptyList(),
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
    /** What the last action said: why a chat didn't open, that a change didn't save. */
    val notice: String? = null,
) {
    /** Every bot, hidden ones too, e.g. to draw whoever a message names. */
    val all: List<Bot> get() = bots + hidden
}

/**
 * The Bots side of the sidebar: the gateway's profiles as bots (`profiles.list`), opening a bot's one
 * permanent chat through [BotChats], and the roster's own actions (pin, hide, start over). The roster
 * refreshes when the gateway says a chat changed, which covers previews and activity, with a slow poll
 * for what sends no event (a bot made, renamed or restyled elsewhere). Also remembers which side of the
 * sidebar was last showing.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BotsViewModel(
    private val api: BotsApi,
    private val chats: BotChats,
    private val connection: GatewayConnection,
    private val seenStore: SeenStore,
    private val modes: SidebarModeStore,
    private val sessions: SessionsApi,
    private val profiles: ProfilesApi,
    private val health: BotHealth,
    private val cron: CronApi,
    private val models: ModelsApi,
) : ViewModel() {

    private val _state = MutableStateFlow(BotsUiState())
    val state: StateFlow<BotsUiState> = _state.asStateFlow()

    /** Bots that can't work until something is fixed, by profile: the roster's ⚠. */
    val troubles: StateFlow<Map<String, BotTrouble>> = health.troubles

    private val _failingRoutines = MutableStateFlow<Map<String, CronJob>>(emptyMap())

    /** Each bot's routine whose last run went wrong, by profile: a quiet routine isn't always a healthy one. */
    val failingRoutines: StateFlow<Map<String, CronJob>> = _failingRoutines.asStateFlow()

    /** When the routines were last read, in epoch ms; they're read less often than the roster. */
    private var routinesReadAt = 0L

    private val _mode = MutableStateFlow(SidebarMode.Chats)
    val mode: StateFlow<SidebarMode> = _mode.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Pictures of the bots that have one, by profile name. */
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()
    private val avatarsAsked = mutableSetOf<String>()

    private val gateway = MutableStateFlow<GatewayUrl?>(null)
    private val visible = MutableStateFlow(false)

    /** Bots whose chats the gateway was asked to watch on this connection. */
    private val watched = mutableSetOf<String>()

    /** The stored session open in the chat pane, so its bot's chat counts as read. */
    private var openSession: String? = null

    init {
        // While the roster shows: read it now, then on every change the gateway reports, and slowly besides.
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
        viewModelScope.launch {
            connection.events
                .filter { it.type == "sessions.changed" && visible.value && _mode.value == SidebarMode.Bots }
                .debounce(EVENT_DEBOUNCE_MS)
                .collect { refreshNow() }
        }
        // A job changed (the launch store's jobs only; others are read on the minute): no waiting a minute.
        viewModelScope.launch {
            connection.events.filter { it.type == "cron.changed" }.debounce(EVENT_DEBOUNCE_MS).collect { refreshRoutines() }
        }
        // Chats name bots too (their messages to each other), so the roster is read once per connection
        // even while the Chats side shows. A new socket has to be asked to watch the bots again.
        viewModelScope.launch {
            combine(gateway, connection.state) { url, conn -> url != null && conn is ConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    watched.clear()
                    if (connected && _state.value.all.isEmpty()) refreshNow()
                }
        }
    }

    fun bind(url: GatewayUrl) {
        if (gateway.value == url) return
        // Another gateway's bots are other bots, whatever their names.
        if (gateway.value != null) health.reset()
        gateway.value = url
        _state.value = BotsUiState()
        _failingRoutines.value = emptyMap()
        routinesReadAt = 0L
        _avatars.value = emptyMap()
        avatarsAsked.clear()
        watched.clear()
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
        viewModelScope.launch { markOpenSeen(_state.value.all) }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Reads the routines again now, e.g. after the user fixed, paused or removed one. */
    fun refreshRoutines() {
        val url = gateway.value ?: return
        routinesReadAt = 0L
        viewModelScope.launch { readRoutines(url, _state.value.all) }
    }

    /**
     * Finds [bot]'s chat (starting it when the bot has never had one) and hands its stored id to [onOpened].
     * When that can't be done safely the row says so instead, and the user can tap again.
     */
    fun open(bot: Bot, onOpened: (storedSessionId: String) -> Unit) {
        if (_state.value.opening != null) return
        _state.update { it.copy(opening = bot.name, notice = null) }
        viewModelScope.launch {
            try {
                val id = chats.open(bot)
                openSession = id
                onOpened(id)
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotChatUnavailableException) {
                _state.update { it.copy(notice = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't open ${bot.label}'s chat. ${e.message.orEmpty()}".trim()) }
            } finally {
                _state.update { it.copy(opening = null) }
            }
        }
    }

    /** Asks the gateway again whether [bot] can work; a fix made elsewhere clears its ⚠. */
    fun checkAgain(bot: Bot) = health.recheck(bot.name)

    /** Keeps [bot] at the top of the roster, or lets it go back to its place. Desktop sees it too. */
    fun setPinned(bot: Bot, pinned: Boolean) = changeMeta(bot, "pinned", JsonPrimitive(pinned))

    /** Moves [bot] to the Hidden section, or back. It keeps working; Desktop hides it too. */
    fun setHidden(bot: Bot, hidden: Boolean) = changeMeta(bot, "hidden", JsonPrimitive(hidden))

    /**
     * Starts [bot]'s chat over: its Bot Chat is archived, which Hermes takes as retiring it (the history stays,
     * archived), and a new empty one is opened. The bot keeps its memory, skills and settings.
     */
    fun startFresh(bot: Bot, onOpened: (storedSessionId: String) -> Unit) {
        val url = gateway.value ?: return
        val chat = bot.canonicalSession ?: return open(bot, onOpened)
        _state.update { it.copy(opening = bot.name, notice = null) }
        viewModelScope.launch {
            // The row holding the title, and the live end of its lineage when compression moved it on.
            val ids = listOfNotNull(chat.id, chat.resolvedId).distinct()
            val failed = ids.map { sessions.setArchived(url, it, archived = true, profile = bot.name) }.firstOrNull { it !is ApiResult.Success }
            if (failed != null) {
                _state.update { it.copy(opening = null, notice = "Couldn't start ${bot.label}'s chat over. ${failed.errorMessage.orEmpty()}".trim()) }
                return@launch
            }
            chats.forget(bot.name)
            _state.update { it.copy(opening = null) }
            open(bot.copy(canonicalSession = null), onOpened)
        }
    }

    private val _busy = MutableStateFlow<String?>(null)

    /** What the editor is waiting on ("Making Scribe…"), or null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /**
     * Makes a bot from [draft] and opens its new chat, where it introduces itself. A bot whose machine has
     * no model it can use is made all the same, without the intro, and the roster says why.
     */
    fun create(draft: BotDraft, onDone: () -> Unit, onOpened: (Bot, String) -> Unit) {
        if (_busy.value != null) return
        _busy.value = "Making ${displayNameFor(draft.profile, draft.title)}…"
        viewModelScope.launch {
            try {
                val made = api.createBot(draft)
                refreshNow()
                onDone()
                val bot = _state.value.all.firstOrNull { it.name == made.profile } ?: Bot(name = made.profile)
                if (!made.readyToChat) {
                    _state.update { it.copy(notice = "Made ${bot.label}, but it has no model it can use yet. ${made.problem.orEmpty()}".trim()) }
                    return@launch
                }
                val stored = api.startWithIntro(made.profile)
                chats.note(listOf(bot.copy(canonicalSession = dev.hermeskotlin.core.bots.BotSession(id = stored))))
                openSession = stored
                onOpened(bot, stored)
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't make the bot. ${e.message.orEmpty()}".trim()) }
                onDone()
            } finally {
                _busy.value = null
            }
        }
    }

    private val _modelPicker = MutableStateFlow(ModelPickerState())

    /** The models a bot can be set to, for its editor's picker; a pick waiting to be confirmed is in it too. */
    val modelPicker: StateFlow<ModelPickerState> = _modelPicker.asStateFlow()

    /** Reads what [bot] can run, its profile's providers and its current model marked. */
    fun loadModels(bot: Bot) {
        _modelPicker.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val catalog = models.options(profile = bot.name)
                _modelPicker.update { it.copy(catalog = catalog, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _modelPicker.update { it.copy(loading = false, error = e.message ?: "Couldn't load the models.") }
            }
        }
    }

    /**
     * Sets the model [bot] runs, for all its chats that follow its configuration. A pick the gateway wants
     * confirmed waits in [modelPicker] until [setModel] comes again with [confirmed].
     */
    fun setModel(bot: Bot, model: ModelOption, confirmed: Boolean = false, onSaved: () -> Unit = {}) {
        _modelPicker.update { it.copy(confirm = null) }
        viewModelScope.launch {
            try {
                val warning = api.setModel(bot.name, model.id, model.provider, confirmed)
                if (warning != null) {
                    _modelPicker.update { it.copy(confirm = PendingSwitch(model, warning)) }
                    return@launch
                }
                _modelPicker.update { it.copy(catalog = it.catalog?.copy(currentModel = model.id, currentProvider = model.provider)) }
                // A model it can't use was the likeliest thing wrong with it.
                health.recheck(bot.name)
                refreshNow()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't change ${bot.label}'s model. ${e.message.orEmpty()}".trim()) }
            }
        }
    }

    fun dismissModelConfirm() = _modelPicker.update { it.copy(confirm = null) }

    /** The bot's SOUL and description, for its editor; null when they couldn't be read. */
    suspend fun details(bot: Bot): BotDetails? = runCatching { api.describe(bot.name) }.getOrNull()

    /** Saves an edit; only what changed is sent, so an edit made on Desktop meanwhile isn't undone. */
    fun save(bot: Bot, description: String?, soul: String?, look: Map<String, JsonElement?>, onDone: () -> Unit) {
        if (_busy.value != null) return
        _busy.value = "Saving…"
        viewModelScope.launch {
            try {
                api.editBot(bot.name, description, soul, look)
                health.recheck(bot.name)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't save ${bot.label}. ${e.message.orEmpty()}".trim()) }
                onDone()
            } finally {
                _busy.value = null
                refreshNow()
            }
        }
    }

    /** Copies [bot] (configuration, skills, SOUL and memory) as a new bot with the same look. */
    fun duplicate(bot: Bot) {
        if (_busy.value != null) return
        _busy.value = "Copying ${bot.label}…"
        _state.update { it.copy(notice = _busy.value) }
        viewModelScope.launch {
            try {
                val name = api.duplicate(bot, _state.value.all.map { it.name }.toSet())
                refreshNow()
                _state.update { it.copy(notice = "Made $name, a full copy of ${bot.label}.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't copy ${bot.label}. ${e.message.orEmpty()}".trim()) }
            } finally {
                _busy.value = null
            }
        }
    }

    /** Deletes [bot]'s profile for good, chats and memory included. [onDeleted] runs once it's gone. */
    fun delete(bot: Bot, onDeleted: () -> Unit) {
        val url = gateway.value ?: return
        if (_busy.value != null) return
        _busy.value = "Deleting ${bot.label}…"
        _state.update { it.copy(notice = _busy.value) }
        viewModelScope.launch {
            when (val result = profiles.delete(url, bot.name)) {
                is ApiResult.Success -> {
                    chats.forget(bot.name)
                    _state.update { it.regroup(it.all.filterNot { b -> b.name == bot.name }).copy(notice = "Deleted ${bot.label}.") }
                    onDeleted()
                }
                else -> _state.update { it.copy(notice = "Couldn't delete ${bot.label}. ${result.errorMessage.orEmpty()}".trim()) }
            }
            _busy.value = null
            refreshNow()
        }
    }

    private fun changeMeta(bot: Bot, key: String, value: JsonElement) {
        // Shown at once; the next roster read brings the gateway's version.
        _state.update { state -> state.regroup(state.all.map { if (it.name == bot.name) it.withMeta(key, value) else it }) }
        viewModelScope.launch {
            try {
                api.updateMeta(bot.name, mapOf(key to value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't change ${bot.label}. ${e.message.orEmpty()}".trim()) }
            }
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        val url = gateway.value ?: return
        try {
            val roster = api.roster()
            if (gateway.value != url) return
            chats.note(roster.bots)
            val ordered = roster.bots.forRoster()
            markOpenSeen(ordered)
            val unread = unread(url, ordered)
            _state.update { it.regroup(ordered).copy(loading = false, error = null, unsupported = false, unread = unread) }
            loadAvatars(ordered)
            watch(ordered)
            health.checkOnce(ordered.map { it.name })
            readRoutines(url, ordered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            val unsupported = UNKNOWN_METHOD.containsMatchIn(e.message)
            _state.update { it.copy(loading = false, unsupported = unsupported, error = e.message.takeUnless { unsupported }) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the bots.") }
        }
    }

    /** Finds each bot's failing routine, at most once a minute; a failed read keeps what was known. */
    private suspend fun readRoutines(url: GatewayUrl, bots: List<Bot>) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - routinesReadAt < ROUTINES_EVERY_MS) return
        routinesReadAt = now
        val jobs = (cron.jobs(url) as? ApiResult.Success)?.value ?: return
        if (gateway.value != url) return
        _failingRoutines.value = bots.mapNotNull { bot ->
            // One the user paused is set aside, not failing; one the scheduler paused over a problem says why.
            jobs.firstOrNull { it.isRoutineOf(bot.name) && it.problem != null && (!it.paused || !it.pausedReason.isNullOrBlank()) }
                ?.let { bot.name to it }
        }.toMap()
    }

    private fun BotsUiState.regroup(ordered: List<Bot>): BotsUiState {
        val (hidden, shown) = ordered.partition { it.meta.hidden }
        return copy(bots = shown, hidden = hidden)
    }

    private fun watch(bots: List<Bot>) {
        bots.filter { watched.add(it.name) }.forEach { bot ->
            viewModelScope.launch { runCatching { api.watch(bot.name) }.onFailure { watched.remove(bot.name) } }
        }
    }

    /** Read state is kept per bot profile, on its Bot Chat's stable id. */
    private suspend fun unread(url: GatewayUrl, bots: List<Bot>): Set<String> = bots.mapNotNull { bot ->
        // A chat just begun has nothing in it to read.
        if (bot.canonicalSession?.messageCount == 0) return@mapNotNull null
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

    private fun Bot.withMeta(key: String, value: JsonElement): Bot {
        val block = kotlinx.serialization.json.JsonObject(metaBlock + (key to value))
        return copy(uiMeta = kotlinx.serialization.json.JsonObject((uiMeta ?: emptyMap<String, JsonElement>()) + (dev.hermeskotlin.core.bots.BotMeta.KEY to block)))
    }

    /** Seconds on the gateway's clock, near enough for "working in the last two minutes". */
    fun nowSeconds(): Double = Clock.System.now().toEpochMilliseconds() / 1000.0

    private companion object {
        /** The roster's fallback read, for changes that send no event; chat activity arrives as events. */
        const val POLL_MS = 30_000L
        const val EVENT_DEBOUNCE_MS = 400L
        const val ROUTINES_EVERY_MS = 60_000L
        val UNKNOWN_METHOD = Regex("method not found|unknown method|no handler", RegexOption.IGNORE_CASE)
    }
}
