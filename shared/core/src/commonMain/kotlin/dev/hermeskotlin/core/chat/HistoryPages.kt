package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage

/**
 * Stored rows read per page: the newest on opening a chat, then older ones as the reader scrolls up. Rows count
 * tool results and each step of a turn, so a page holds a handful of turns.
 */
internal const val HISTORY_PAGE = 60

/** The most rows the dashboard hands out at once, for loading everything (to count rows for a branch). */
internal const val HISTORY_PAGE_MAX = 500

/**
 * A full page from its first prompt on: the rows before it are the tail of a turn the page cut in two. Shown, that
 * tail would be a reply of its own that merges into the turn's start, and changes key, once the page before loads;
 * left out, it comes whole with that page. A page without a prompt (one very long turn) is kept as it is.
 */
internal fun List<SessionMessage>.fromFirstTurn(): List<SessionMessage> {
    val first = indexOfFirst { it.role == "user" && !it.isHidden }
    return if (first > 0) drop(first) else this
}

/**
 * The loaded rows with the newest [page] laid over their end: everything from the page's first row on is replaced,
 * which also drops rows an `/undo` took away. Null when the page doesn't reach back to a loaded row, so the older
 * rows can't be joined to it without a hole.
 */
internal fun List<SessionMessage>.withNewest(page: List<SessionMessage>): List<SessionMessage>? {
    if (isEmpty()) return page
    val firstId = page.firstOrNull()?.id ?: return null
    val at = indexOfFirst { it.id == firstId }
    return if (at >= 0) take(at) + page else null
}

/**
 * The loaded rows after [page], the newest page as read now (fetched [HISTORY_PAGE] at a time): the whole transcript
 * when it fit, else joined to what's loaded. Null when it can't be joined.
 */
internal fun List<SessionMessage>.afterNewestPage(page: List<SessionMessage>, limit: Int = HISTORY_PAGE): List<SessionMessage>? =
    if (page.size < limit) page else withNewest(page.fromFirstTurn())
