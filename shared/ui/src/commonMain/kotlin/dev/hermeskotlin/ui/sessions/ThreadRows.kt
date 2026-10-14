package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.projects.ProjectIndex
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.threads.ChatThread
import dev.hermeskotlin.core.threads.ThreadsRepository

/** A thread as its row shows it: the thread, and its chat as the list has it now (made up when not listed yet). */
data class ThreadRow(val thread: ChatThread, val session: SessionSummary)

/** The recent list split for the sidebar: the Threads section, then every other chat, each shown once. */
data class ThreadSections(val threads: List<ThreadRow>, val others: List<SessionSummary>)

/**
 * Splits [listed] (what the list shows) around [threads]. A thread's chat is found among [rows] (every chat the
 * list loaded) or [listed], by its id or its compression lineage; one not loaded yet still shows, by the id the
 * thread keeps. Thread chats leave [listed], so a pinned one isn't also under Pinned. With a [project] picked,
 * only threads whose chat is in it show, at the top of its list.
 */
internal fun threadSections(
    threads: List<ChatThread>,
    listed: List<SessionSummary>,
    rows: List<SessionSummary>,
    project: Project?,
    index: ProjectIndex,
): ThreadSections {
    val shown = threads.mapNotNull { thread ->
        val inList = ThreadsRepository.chatOf(thread, listed)
        val session = inList ?: ThreadsRepository.chatOf(thread, rows)
            ?: SessionSummary(thread.sessionId, title = thread.name, pinned = true, cwd = thread.cwd)
        val belongs = project == null || inList != null || index.projectOf(session)?.id == project.id
        ThreadRow(thread, session).takeIf { belongs }
    }
    val others = listed.filterNot { session -> threads.any { it.holds(session) } }
    return ThreadSections(shown, others)
}
