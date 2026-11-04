package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.connection.ConnectionState
import kotlin.test.Test
import dev.hermeskotlin.ui.resources.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRowStatusTest {

    @Test
    fun waitingOnTheUserOutranksEverythingElse() {
        val status = RowStatus(waiting = Waiting.Approval, unread = true, running = true)
        assertEquals(RowTone.Approval, rowTone(status, draft = true))
        assertEquals(RowTone.Question, rowTone(RowStatus(waiting = Waiting.Question, running = true), draft = false))
        assertEquals(RowTone.Question, rowTone(RowStatus(waiting = Waiting.Input), draft = false))
    }

    @Test
    fun aRequestSeenElsewhereReadsAsApproval() {
        assertEquals(RowTone.Approval, rowTone(RowStatus(waiting = Waiting.Unknown), draft = false))
    }

    @Test
    fun runningThenReplyThenDraftThenIdle() {
        assertEquals(RowTone.Running, rowTone(RowStatus(running = true, unread = true), draft = true))
        assertEquals(RowTone.Reply, rowTone(RowStatus(unread = true), draft = true))
        assertEquals(RowTone.Draft, rowTone(null, draft = true))
        assertEquals(RowTone.Idle, rowTone(null, draft = false))
        assertEquals(RowTone.Idle, rowTone(RowStatus(), draft = false))
    }

    @Test
    fun theLineSaysEveryStatusInWords() {
        assertEquals(
            listOf(
                Res.string.sessions_waiting_approval,
                Res.string.sessions_status_running,
                Res.string.sessions_status_new_reply,
                Res.string.sessions_status_draft,
            ),
            rowStatusWords(RowStatus(waiting = Waiting.Approval, unread = true, running = true), draft = true),
        )
        assertEquals(listOf(Res.string.sessions_status_draft), rowStatusWords(null, draft = true))
        assertTrue(rowStatusWords(RowStatus(), draft = false).isEmpty())
    }

    @Test
    fun theConnectionDotIsSpoken() {
        assertEquals(Res.string.sessions_connection_connected, connectionWord(null))
        assertEquals(Res.string.sessions_connection_reconnecting, connectionWord(ConnectionState.Reconnecting(1, 0, "lost")))
        assertEquals(Res.string.sessions_connection_signed_out, connectionWord(ConnectionState.SessionExpired))
        assertEquals(Res.string.sessions_connection_not_connected, connectionWord(ConnectionState.Failed("guard")))
        assertEquals(Res.string.sessions_connection_connecting, connectionWord(ConnectionState.Idle))
    }
}
