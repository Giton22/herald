package dev.hermeskotlin.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The chats other than the one on screen: the Sessions badge, the banner over the chat, Next and the buzz. Reads
 * [sessions]' list (its rows, the gateway and profile it is bound to, and each read of it) and the live statuses;
 * with "Alerts for other chats" off ([setOtherChats]) nothing here watches, polls or reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherChatsViewModel(
    /** The sidebar's list, shared with it: its rows are the other chats, and coming back may read it again. */
    private val sessions: SessionsViewModel,
    private val api: SessionsApi,
    private val connection: GatewayConnection,
    attention: AttentionTracker,
) : ViewModel() {

    /** Stamps when the list was read and when the watch was left. */
    internal var clock: Clock = Clock.System

    private val waiting = attention.waiting
    private val running = attention.running
    private val requestsByChat = attention.requestsByChat

    /** Whether the chat is on screen with the app in the foreground ([setChatShown]). */
    private val chatShown = MutableStateFlow(false)

    /** Every id the chat on screen goes by: it never counts as another chat. */
    private val openIds = MutableStateFlow<Set<String>>(emptySet())

    /** The bots' chats, which the list doesn't have (they're hidden sessions); waiting and running are filled in here. */
    private val botChats = MutableStateFlow<List<OtherChatRow>>(emptyList())

    /**
     * The list has been read since the chat came on screen, the gateway or profile changed, or the link
     * came back; until then the watch only takes stock, so what the read brings in isn't news.
     */
    private val freshList = MutableStateFlow(false)

    private val watch = OtherChatsWatch()

    /**
     * The recent list as last read, for the other chats. It stands while the archive shows or a list is being
     * read again: neither means the chats went away, and taking them out would drop what was news about them.
     */
    private val recentRows = MutableStateFlow<List<SessionSummary>>(emptyList())

    private val _others = MutableStateFlow(OtherChats())

    /** The chats other than the one on screen, as last worked out while the chat showed. */
    val others: StateFlow<OtherChats> = _others.asStateFlow()

    private val _badge = MutableStateFlow(SessionsBadge())

    /** What the Sessions button shows: chats waiting on the user and replies that are news, not every unread chat. */
    val badge: StateFlow<SessionsBadge> = _badge.asStateFlow()

    private val _banner = MutableStateFlow<OtherChatEvent?>(null)

    /** What newly happened in another chat, for the banner over the chat; null when there's nothing to say. */
    val banner: StateFlow<OtherChatEvent?> = _banner.asStateFlow()

    private val _otherChatEvents = MutableSharedFlow<OtherChatEvent>(extraBufferCapacity = 4)

    /** Each thing that newly happens in another chat, once, e.g. to buzz when one starts waiting. */
    val otherChatEvents: SharedFlow<OtherChatEvent> = _otherChatEvents.asSharedFlow()

    /** The chat is on screen with the app in the foreground, as [setChatShown] last said. */
    private var chatOnScreen = false

    /** "Alerts for other chats" is on ([setOtherChats]): off, nothing here watches, polls or reads. */
    private var otherChatsOn = false

    /** The banner is wanted; off, no banner is kept and no reply's text is read for one. */
    private var bannerOn = true

    /** When the list was last read from the gateway (epoch ms), so coming back soon after needn't read it again. */
    private var listReadAt: Long? = null

    /** The read filling in the banner's preview, if one is under way. */
    private var previewJob: Job? = null

    /** The recent chats read while the sidebar shows another list, if one is under way. */
    private var recentJob: Job? = null

    /**
     * The user's settings for other chats: [enabled] is the switch for the whole feature, [banner] the strip over
     * the chat. Turned off, what was watched is forgotten, so turning it on again starts afresh.
     */
    fun setOtherChats(enabled: Boolean, banner: Boolean) {
        bannerOn = banner
        if (!banner) {
            previewJob?.cancel()
            _banner.value = null
        }
        if (enabled == otherChatsOn) return
        otherChatsOn = enabled
        watchIfShown()
        // After the watch stopped, which marks when it was left: off, there's no time away to count from.
        if (!enabled) {
            watch.clear()
            _others.value = OtherChats()
            _badge.value = SessionsBadge()
            _banner.value = null
            previewJob?.cancel()
            recentJob?.cancel()
        }
    }

    /**
     * The chat is on screen with the app in the foreground, or no longer is. Only then (and with
     * [setOtherChats] on) are the other chats watched: that collects the live statuses, so the gateway's live
     * list is polled while the chat shows, not only while the sidebar does (and not at all in the background).
     * Coming back takes stock afresh, so what happened while away shows on the badge but never as a burst of banners.
     */
    fun setChatShown(shown: Boolean) {
        chatOnScreen = shown
        watchIfShown()
    }

    private fun watchIfShown() {
        val shown = chatOnScreen && otherChatsOn
        if (chatShown.value == shown) return
        if (shown) watch.reset() else watch.left(clock.now().toEpochMilliseconds() / 1000.0)
        freshList.value = false
        // The banner stays: a rotation passes through here, and its own timer takes away one gone stale meanwhile.
        chatShown.value = shown
        if (!shown) return
        // A list read a moment ago (back from Settings, a plugin page) still holds; an older one is read again,
        // and the watch waits for that read before anything counts as new.
        val readAt = listReadAt
        val connected = connection.state.value is ConnectionState.Connected
        // Only with Recent showing: then each change on the gateway read the recent chats meanwhile. Another list
        // (the archive) read only itself, and the recent chats wait for this read.
        val recent = sessions.state.value.filter == SessionListFilter.Recent
        if (connected && recent && readAt != null && clock.now().toEpochMilliseconds() - readAt < LIST_FRESH_MS) freshList.value = true else sessions.refreshQuietly()
    }

    /**
     * The chat on screen now goes by [ids] (a bot's chat by both of its ids); its banner, if any, goes. It leaves
     * the other chats at once, not after they settle, so the badge and Next don't send the user back to it.
     */
    fun setOpenChat(ids: Set<String>) {
        openIds.value = ids
        _banner.update { event -> event?.takeUnless { it.chatId in ids } }
        val others = OtherChats(_others.value.chats.filterKeys { it !in ids })
        _others.value = others
        _badge.value = watch.badge(others)
    }

    /** The bots' chats, from the roster, for the badge and the banner. */
    fun setBotChats(rows: List<OtherChatRow>) {
        botChats.value = rows
    }

    /**
     * Takes away the banner for [event]. Matched by chat and time, not by value: the preview fills in after the
     * banner shows, so a caller may still hold the event from before it did.
     */
    fun dismissBanner(event: OtherChatEvent) {
        _banner.update { if (it != null && it.chatId == event.chatId && it.atMillis == event.atMillis) null else it }
    }

    /** The next other chat needing the user: requests oldest first, then replies oldest first; null when none does. */
    fun nextChat(): OtherChat? = watch.queue(_others.value).firstOrNull { it.id !in openIds.value }

    /**
     * How far the listed chat [id] had been read, taken before it counts as read now, as a sidebar tap does,
     * for opening it from elsewhere (the banner, Next). Null when it isn't listed or has nothing new.
     */
    fun openListed(id: String): Double? {
        val state = sessions.state.value
        // The recent rows too: the sidebar may show the archive, and the chat is in the recent list.
        val session = (state.sessions + state.projectSessions.orEmpty() + recentRows.value).firstOrNull { it.id == id } ?: return null
        val readUpTo = sessions.readUpTo(session)
        sessions.markSeen(session)
        return readUpTo
    }

    /**
     * Keeps up with the sidebar's list: another gateway or profile starts afresh, each read of it counts as the
     * list read, the list going stale loses that, and the recent rows are kept as last read.
     */
    private fun followList() {
        viewModelScope.launch {
            var last: ListBinding? = null
            sessions.binding.collect { binding ->
                if (binding == null || binding == last) return@collect
                val newGateway = last?.gateway != binding.gateway
                last = binding
                // Another gateway or profile: its chats are other chats, and what was said about the last ones goes.
                watch.clear()
                freshList.value = false
                listReadAt = null
                _others.value = OtherChats()
                _badge.value = SessionsBadge()
                _banner.value = null
                previewJob?.cancel()
                recentJob?.cancel()
                recentRows.value = emptyList()
                // Bots are the gateway's, not the profile's: the roster doesn't change with the profile, so nothing would
                // hand them back after a profile switch. Another gateway's roster replaces them as it loads.
                if (newGateway) botChats.value = emptyList()
            }
        }
        viewModelScope.launch {
            sessions.listReads.collect { read ->
                when (read) {
                    is ListRead.Asked -> readRecentAlongside(read)
                    is ListRead.Done -> {
                        // Only the recent list speaks for the other chats: the archive read while the chat is hidden
                        // reads no recent rows, so coming back must read them again.
                        if (read.filter == SessionListFilter.Recent) listReadAt = clock.now().toEpochMilliseconds()
                        freshList.value = true
                    }
                    // Said by the same look at the link that reads the list again once it's back.
                    ListRead.Stale -> {
                        freshList.value = false
                        listReadAt = null
                    }
                }
            }
        }
        viewModelScope.launch {
            // Only the recent list, once read: the archive showing, or the list emptied while it is read again
            // (a filter changed back), shouldn't make every chat leave and come back.
            sessions.state.collect { state ->
                if (state.filter == SessionListFilter.Recent && !state.loading) {
                    recentRows.value = (state.sessions + state.projectSessions.orEmpty()).distinctBy { it.id }
                }
            }
        }
    }

    /**
     * While the sidebar shows another list (the archive), the recent chats are read too, on the same triggers as
     * the list (a change on the gateway, a reconnect, the chat coming back), so a reply in one still reaches the
     * badge, the banner and Next. Only while the chat shows: nothing is read for this in the background. Only the
     * plain recent list; a picked project's chats wait for Recent to show again. The sidebar's state is untouched.
     */
    private fun readRecentAlongside(read: ListRead.Asked) {
        recentJob?.cancel()
        if (read.filter == SessionListFilter.Recent || !chatShown.value) return
        val url = read.gateway
        val profile = read.profile
        recentJob = viewModelScope.launch {
            val limit = recentRows.value.size.coerceIn(SessionsApi.PAGE_SIZE, 100)
            val result = api.list(url, limit = limit, filter = SessionListFilter.Recent, profile = profile)
            if (result !is ApiResult.Success || sessions.binding.value?.let { it.gateway.gatewayUrl to it.profile } != url to profile) return@launch
            // Recent may have come back meanwhile; then its own read speaks for the recent chats.
            if (sessions.state.value.filter == SessionListFilter.Recent) return@launch
            recentRows.value = result.value.sessions
        }
    }

    /** Whether a reconnect (or coming back) has settled: connected, the list read again, and a moment for the live statuses. */
    private val settled = combine(
        chatShown,
        connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged(),
        sessions.binding.map { binding -> binding?.let { it.gateway.gatewayUrl to it.profile } }.distinctUntilChanged(),
    ) { shown, connected, _ ->
        shown && connected
    }.flatMapLatest { live ->
        flow {
            emit(false)
            if (!live) return@flow
            freshList.first { it }
            delay(SETTLE_MS)
            emit(true)
        }
    }

    // After everything the watch reads is set up.
    init {
        followList()
        watchOtherChats()
    }

    @OptIn(FlowPreview::class)
    private fun watchOtherChats() {
        viewModelScope.launch {
            chatShown.flatMapLatest { shown ->
                if (!shown) return@flatMapLatest emptyFlow()
                val listed = combine(recentRows, sessions.seen, waiting, running, requestsByChat) { rows, seen, waiting, running, requests ->
                    Listed(
                        rows.map { s ->
                            OtherChatRow(
                                OtherChat(
                                    s.id, s.displayTitle, waiting[s.id], seen?.isUnread(s) == true, running[s.id] == true, s.activityAt,
                                    requestId = requests[s.id]?.id,
                                ),
                            )
                        },
                        waiting,
                        running,
                        requests,
                    )
                }
                combine(listed, botChats, openIds, settled) { listed, bots, open, settled ->
                    val botRows = bots.map { row ->
                        row.copy(
                            chat = row.chat.copy(
                                waiting = row.ids.firstNotNullOfOrNull { listed.waiting[it] },
                                running = row.ids.any { listed.running[it] == true },
                                requestId = row.ids.firstNotNullOfOrNull { listed.requests[it]?.id },
                            ),
                        )
                    }
                    OtherChats.of(listed.rows + botRows, open) to settled
                }
                // The list and the live statuses move separately (a turn's end bumps the list before the poll
                // says it stopped): let a change settle, so a turn just starting doesn't read as a reply.
                .debounce(OTHERS_SETTLE_MS)
            }.collect { (others, settled) ->
                _others.value = others
                val event = watch.update(others, clock.now().toEpochMilliseconds(), settled)
                _badge.value = watch.badge(others)
                // A banner for something since dealt with (answered, read, archived) goes.
                _banner.update { shown -> shown?.takeIf { others.stillTrue(it) } }
                if (event != null) {
                    previewJob?.cancel()
                    // What is asked is already here; a reply's text is read from the gateway just after.
                    val shown = if (event.waiting) event.copy(preview = previewOf(requestsByChat.value[event.chatId])) else event
                    _otherChatEvents.tryEmit(shown)
                    // Without the banner, the event only buzzes: nothing is kept, and no reply's text is read.
                    if (bannerOn) {
                        _banner.value = shown
                        if (!shown.waiting) fillReplyPreview(shown)
                    }
                }
            }
        }
    }

    /**
     * Reads the newest row of the chat [event] is about and puts the start of its reply into the banner, if that
     * banner still shows. A Bot Chat lives in its bot's profile. Failing, the banner keeps just its title.
     */
    private fun fillReplyPreview(event: OtherChatEvent) {
        val binding = sessions.binding.value ?: return
        val url = binding.gateway.gatewayUrl
        val profile = event.bot ?: binding.profile
        // One read at a time: a newer banner replaces the one this was for, and another gateway's banner
        // must never get the last one's words (two banners can share a chat id and a millisecond).
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val page = api.messages(url, event.chatId, limit = PREVIEW_ROWS, profile = profile) as? ApiResult.Success ?: return@launch
            val reply = page.value.messages.lastOrNull { it.role == "assistant" && !it.isHidden && it.text.isNotBlank() }
            val preview = previewOf(reply?.text) ?: return@launch
            _banner.update { shown -> if (shown == event) shown.copy(preview = preview) else shown }
        }
    }

    private class Listed(
        val rows: List<OtherChatRow>,
        val waiting: Map<String, Waiting>,
        val running: Map<String, Boolean>,
        val requests: Map<String, InputRequest>,
    )

    private companion object {
        /** Rows read for a reply's preview: the newest few, as tool rows can follow the reply's text. */
        const val PREVIEW_ROWS = 6

        /** After a reconnect's list read, how long the live statuses get to catch up before changes count as news. */
        const val SETTLE_MS = 3_000L

        /** A list read this recently still holds when the chat comes back on screen; an older one is read again. */
        const val LIST_FRESH_MS = 30_000L

        /** How long the other chats must hold still before a change in them counts. */
        const val OTHERS_SETTLE_MS = 1_500L
    }
}
