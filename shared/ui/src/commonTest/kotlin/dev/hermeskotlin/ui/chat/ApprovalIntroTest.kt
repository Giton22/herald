package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApprovalIntroTest {

    private fun approval(toolName: String?, description: String) = InputRequest.Approval(
        id = "r1",
        command = "sudo systemctl start backup.service",
        description = description,
        toolName = toolName,
        choices = listOf(ApprovalChoice.Once, ApprovalChoice.Deny),
    )

    @Test
    fun toolAndPurposeReadAsOneLine() {
        assertEquals(
            ApprovalIntro(tool = "terminal", purpose = "Run the backup now"),
            approvalIntro(approval("terminal", "run the backup now")),
        )
    }

    @Test
    fun aBlankToolNameIsLeftOut() {
        assertEquals(ApprovalIntro(tool = null, purpose = "Run the backup now"), approvalIntro(approval("", "run the backup now")))
        assertEquals(ApprovalIntro(tool = null, purpose = "Run the backup now"), approvalIntro(approval(" ", "run the backup now")))
    }

    @Test
    fun noToolAndNoPurposeShowsNothing() {
        assertNull(approvalIntro(approval(null, "")))
        assertNull(approvalIntro(approval("", "  ")))
    }
}
