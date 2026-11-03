package dev.hermeskotlin.ui.inbox

import dev.hermeskotlin.core.chat.AttachmentKind
import dev.hermeskotlin.core.chat.DraftStart
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.ui.reportChatProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportChatTest {

    private val run = InboxRun(bot = "work", job = "006998ffc458", run = "2026-10-12_08-02-07", label = "morning-briefing", at = 1_000, text = "Rain later.")

    @Test
    fun aRunsChatOfTheUnifiedRuntimeIsOpenedToReadNotContinued() {
        // An older gateway's run session goes on like any chat.
        assertEquals("Continue its chat", runChatLabel(run.copy(session = "cron_006998ffc458_20261012_080150")))
        // From hermes-agent 0.22.3 a run keeps the runtime's local id, and its session only runs its job.
        val unified = run.copy(session = "local-" + "a".repeat(64)).withCheckedSession()
        assertTrue(unified.sessionReadOnly)
        assertEquals("Open its chat", runChatLabel(unified))
    }

    @Test
    fun aReportIsAttachedWhole() {
        val long = run.copy(text = "x".repeat(50_000))
        val file = reportAttachment(long, "Morning briefing")
        assertEquals(50_000, file.bytes.decodeToString().length)
        assertEquals("text/markdown", file.mimeType)
        assertEquals(AttachmentKind.File, file.kind)
    }

    @Test
    fun theFileIsNamedForTheJobAndTheRunsTime() {
        assertEquals("Morning briefing 2026-10-12 08-02-07.md", reportAttachment(run, "Morning briefing").name)
        // What a file name can't hold goes; an empty name falls back to the job's id.
        assertEquals("Sales a b 2026-10-12 08-02-07.md", reportAttachment(run, "Sales: a/b?").name)
        assertEquals("Line one 2026-10-12 08-02-07.md", reportAttachment(run, "Line\none").name)
        assertEquals("006998ffc458 2026-10-12 08-02-07.md", reportAttachment(run, " /:* ").name)
        assertTrue(reportAttachment(run, "y".repeat(200)).name.length < 90)
    }

    @Test
    fun aFailedRunSaysSoInItsNameAndAnEmptyOneInItsText() {
        val failed = run.copy(status = InboxRun.FAILED, text = "")
        val file = reportAttachment(failed, "Backup")
        assertEquals("Backup 2026-10-12 08-02-07 (failed).md", file.name)
        assertEquals("The run failed and left no output.", file.bytes.decodeToString())
    }

    @Test
    fun eachRunsFileHasItsOwnId() {
        assertNotEquals(reportAttachment(run, "A").id, reportAttachment(run.copy(run = "2026-10-13_08-02-07"), "A").id)
    }

    @Test
    fun theDraftLeavesTheComposerEmptyForTheQuestion() {
        val draft = reportDraft(run, "Morning briefing")
        assertNull(draft.text)
        assertEquals(DraftStart.Typing, draft.start)
        assertEquals(1, draft.attachments.size)
    }

    @Test
    fun aReportsChatIsInTheProfileItsJobRanIn() {
        // The chats on the launch profile, which the job ran in: none named, as for any of its chats.
        assertNull(reportChatProfile("default", chatsProfile = null, launch = "default"))
        // Another profile's job is named, whatever the chats are on.
        assertEquals("work", reportChatProfile("work", chatsProfile = null, launch = "default"))
        assertEquals("work", reportChatProfile("work", chatsProfile = "work", launch = "default"))
        assertEquals("default", reportChatProfile("default", chatsProfile = "work", launch = "default"))
        // The launch profile unknown: named, not guessed.
        assertEquals("default", reportChatProfile("default", chatsProfile = null, launch = null))
        // A gateway launched as another profile: the default one's job is still named.
        assertEquals("default", reportChatProfile("default", chatsProfile = null, launch = "work"))
        assertNull(reportChatProfile("work", chatsProfile = null, launch = "work"))
    }

    @Test
    fun aReportTheGatewayCutShortSaysSoOnItsFirstLine() {
        val cut = run.copy(text = "Rain later.", truncated = true)
        val file = reportAttachment(cut, "Morning briefing")
        assertEquals("$TRUNCATED_NOTE\n\nRain later.", file.bytes.decodeToString())
        assertTrue(file.bytes.decodeToString().startsWith("> "))
        assertEquals(reportAttachment(run, "Morning briefing").name, file.name)
        // A whole one says nothing of it.
        assertEquals("Rain later.", reportAttachment(run, "Morning briefing").bytes.decodeToString())
    }
}
