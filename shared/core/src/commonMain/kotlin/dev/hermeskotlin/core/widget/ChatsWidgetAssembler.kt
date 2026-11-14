package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.sessions.SeenChats
import dev.hermeskotlin.core.sessions.SessionSummary

/**
 * The Chats widget's ranking and row derivation (D9, `docs/WIDGETS-SPEC.md`), a pure function over one
 * page of the Recent list plus the live maps — so a warm refresh and a cold one differ only in which
 * inputs are empty.
 */
object ChatsWidgetAssembler {

    /** Rows kept in the snapshot. */
    const val CAP = 12

    /**
     * @param sessions one `SessionsApi.list` page (`SessionListFilter.Recent`, limit 50); archived and
     *   machine-originated rows are already excluded by the filter.
     * @param seen this gateway/profile's read tracking; its `isUnread` semantics decide the badge, never
     *   the raw gateway flag alone.
     * @param waiting `AttentionTracker.waiting` by stored id (empty cold); the only source of waiting labels.
     * @param runningChats `AttentionTracker.running` by stored id (empty cold); never `SessionSummary.isActive`.
     * @param botChatIds every stored id a live or saved bot roster's `canonicalSession` answers
     *   (`BotSession.isChat`); empty only when no roster was saved, leaving the title rule below.
     * @param threadIds stored ids the `ThreadsRepository` knows as threads (empty cold).
     * @param savedCopy true when the rows came from `OfflineCache`, not the live gateway.
     */
    fun assemble(
        gatewayUrl: String,
        profile: String?,
        sessions: List<SessionSummary>,
        seen: SeenChats?,
        waiting: Map<String, Waiting>,
        runningChats: Map<String, Boolean>,
        botChatIds: Set<String>,
        threadIds: Set<String>,
        savedCopy: Boolean,
        nowSeconds: Long,
    ): ChatsWidgetSnapshot {
        val rows = sessions
            .filterNot { session -> isBotChat(session, botChatIds) }
            .sortedWith(ROW_ORDER)
            .take(CAP)
            .map { session ->
                ChatWidgetRow(
                    storedSessionId = session.id,
                    title = session.displayTitle,
                    subtitle = session.preview?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() },
                    waitingLabel = waiting.firstNotNullOfOrNull { (id, label) -> label.takeIf { session.isChat(id) } }?.label,
                    running = runningChats.any { (id, running) -> running && session.isChat(id) },
                    unread = seen?.isUnread(session) ?: session.unread,
                    pinned = session.pinned,
                    thread = session.id in threadIds,
                    activityEpochSeconds = session.activityAt,
                    savedCopy = savedCopy,
                )
            }
        return ChatsWidgetSnapshot(gatewayUrl = gatewayUrl, profile = profile, rows = rows, savedAtEpochSeconds = nowSeconds)
    }

    /** Bot chats belong to Now, not this list. Roster IDs recognize retitled chats; the title is the fallback. */
    private fun isBotChat(session: SessionSummary, botChatIds: Set<String>): Boolean =
        botChatIds.any { session.isChat(it) } || session.displayTitle == BOT_CHAT_TITLE

    private val ROW_ORDER = compareByDescending<SessionSummary> { it.pinned }
        .thenByDescending { it.activityAt ?: Double.NEGATIVE_INFINITY }
        .thenBy { it.id }

    private const val BOT_CHAT_TITLE = "Bot Chat"
}
