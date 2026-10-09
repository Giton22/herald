package dev.hermeskotlin.ui.preview

import androidx.compose.foundation.text.input.TextFieldState
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.GatewayNotice
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.DelegatedTask
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SendCheck
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TurnUsage
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.core.slash.SlashSuggestion
import dev.hermeskotlin.ui.chat.ChatActions
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.ui.chat.CommentSource
import dev.hermeskotlin.ui.chat.LAST_REPLY
import dev.hermeskotlin.ui.chat.PendingComment
import dev.hermeskotlin.ui.chat.SelectionAnchor
import dev.hermeskotlin.ui.chat.newComment
import dev.hermeskotlin.ui.sessions.RowStatus
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoicePhase
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Made-up chats for previews and the screenshots in the README: a home server whose nightly backup
 * failed. Nothing here comes from a real gateway.
 */
internal object ChatSamples {

    const val TITLE = "Fix the nightly backup"
    const val PLACEHOLDER = "Send a follow-up"

    private val connected = ChatState(
        storedSessionId = "s1",
        title = TITLE,
        model = "claude-sonnet-5-5",
        provider = "anthropic",
        reasoningEffort = "medium",
        historyLoaded = true,
        attachment = Attachment.Attached("preview"),
    )

    private val ask = ChatMessage.User("u1", "The nightly backup to the NAS failed again. Can you find out why and fix it?")

    private val investigation = listOf(
        ToolActivity(
            id = "t1",
            name = "terminal",
            summary = "journalctl -u backup.service --since yesterday",
            durationSeconds = 1.2,
            input = "journalctl -u backup.service --since yesterday",
            output = "backup.sh[2214]: rsync: [receiver] mkstemp \"/mnt/nas/photos/.IMG_2231.jpg\" failed: No space left on device (28)\n" +
                "backup.sh[2214]: rsync error: error in file IO (code 11)",
        ),
        ToolActivity(
            id = "t2",
            name = "terminal",
            summary = "df -h /mnt/nas",
            durationSeconds = 0.4,
            input = "df -h /mnt/nas",
            output = "Filesystem      Size  Used Avail Use% Mounted on\nnas:/backup     2.0T  2.0T     0 100% /mnt/nas",
        ),
        ToolActivity(
            id = "t3",
            name = "patch",
            summary = "backup.sh: keep the last 14 snapshots",
            durationSeconds = 0.3,
            input = "/opt/backup/backup.sh",
            diff = "@@ -12,3 +12,5 @@\n rsync -a --delete \"\$SRC\" \"\$DEST/\$(date +%F)\"\n+# Keep two weeks of snapshots; older ones filled the share.\n+ls -1d \"\$DEST\"/20* | head -n -14 | xargs -r rm -rf",
        ),
    )

    private val answer = ChatMessage.Assistant(
        key = "a1",
        reasoning = "The service log should say why rsync stopped. If the share is full, old snapshots are the likely cause.",
        tools = investigation,
        text = """
            The share on the NAS is **full**: rsync stopped with *No space left on device*. Every night's
            snapshot has been kept since March, so the 2 TB filled up.

            What I changed in `backup.sh`:

            - It now keeps the **last 14 snapshots** and removes older ones after each run.
            - Nothing else changes: same source, same schedule.

            ```bash
            ls -1d "${'$'}DEST"/20* | head -n -14 | xargs -r rm -rf
            ```

            Tonight's run will free about 1.3 TB. Want me to run it now instead?
        """.trimIndent(),
        usage = TurnUsage(input = 18_400, output = 612, reasoning = 240, calls = 4),
    )

    /** A finished turn: reasoning, tools, a diff and a Markdown answer. */
    val reply: ChatState = connected.copy(messages = listOf(ask, answer))

    /** The finished turn with the gateway's notices: credits, and the agent still starting. */
    val notices: ChatState = reply.copy(
        notices = listOf(
            GatewayNotice("credits.depleted", "Credit access paused · run /topup to top up", GatewayNotice.Level.Error, GatewayNotice.Kind.Sticky),
            GatewayNotice("credits.usage", "80% of this month's credit used", GatewayNotice.Level.Warning, GatewayNotice.Kind.Sticky),
            GatewayNotice(
                "agent-build-slow",
                "Still starting the agent (tool discovery / model setup) — your message will be sent as soon as it's ready.",
                GatewayNotice.Level.Info,
                GatewayNotice.Kind.Agent,
            ),
        ),
    )

    /** Two prompts that lost their reply: one the transcript couldn't settle, one it found missing. */
    val undelivered: ChatState = connected.copy(
        messages = listOf(
            ask,
            answer,
            ChatMessage.User("u2", "Run the backup now.", check = SendCheck.NotReceived),
            ChatMessage.User("u3", "And mail me the report when it's done.", check = SendCheck.Unknown),
        ),
    )

    /** [reply] after an earlier exchange, long enough to scroll back up through. */
    val longChat: ChatState = connected.copy(
        messages = listOf(
            ChatMessage.User("e1", "Is the NAS healthy? It felt slow last week."),
            ChatMessage.Assistant(
                key = "e2",
                text = """
                    Both drives pass their **SMART** checks, and the pool is healthy. The slowness lines up with
                    the nightly backup: it now runs past 7 am, when the photo sync starts too.

                    - **Drive 1:** 0 reallocated sectors, 31 °C
                    - **Drive 2:** 0 reallocated sectors, 33 °C
                    - **Pool:** online, last scrub found nothing

                    If the backup keeps running long, the share may be close to full. Want me to look?
                """.trimIndent(),
            ),
            ChatMessage.User("e3", "Not now. Remind me which share the photos go to?"),
            ChatMessage.Assistant(
                key = "e4",
                text = "Photos sync to `nas:/backup/photos`, mounted at `/mnt/nas/photos`. The backup copies the same share every night at 2 am.",
            ),
        ) + reply.messages,
    )

    /** A turn still running, with the agent's plan pinned above the composer. */
    val working: ChatState = connected.copy(
        messages = listOf(
            ask,
            ChatMessage.Assistant(
                key = "a1",
                tools = investigation.take(2) + ToolActivity("t3", "read_file", detail = "/opt/backup/backup.sh", running = true),
                streaming = true,
            ),
        ),
        running = true,
        status = "Reading the backup script",
        todos = TodoList(
            items = listOf(
                TodoItem("1", "Read the backup service log", TodoStatus.Completed),
                TodoItem("2", "Check free space on the NAS share", TodoStatus.Completed),
                TodoItem("3", "Find what fills the share", TodoStatus.InProgress),
                TodoItem("4", "Limit how many snapshots are kept", TodoStatus.Pending),
                TodoItem("5", "Run the backup once to confirm", TodoStatus.Pending),
            ),
            revision = 3,
        ),
        todosLive = true,
    )

    /** The agent waits for permission to run a command. */
    val approval: ChatState = connected.copy(
        messages = listOf(ask, answer, ChatMessage.User("u2", "Yes, run it now."), ChatMessage.Assistant("a2", streaming = true)),
        running = true,
        inputRequests = listOf(
            InputRequest.Approval(
                id = "r1",
                command = "sudo systemctl start backup.service",
                description = "Run the backup now so the old snapshots are cleared",
                toolName = "terminal",
                choices = listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always, ApprovalChoice.Deny),
            ),
        ),
    )

    private val approvalRequest = approval.inputRequests.single() as InputRequest.Approval

    /** A command too long to show whole: it wraps past the preview and offers "Show full command". */
    val longApproval: ChatState = approval.copy(
        inputRequests = listOf(
            approvalRequest.copy(
                command = (1..6).joinToString(" ", prefix = "rm -rf ") { "/srv/backup/snapshots/2026-0$it-01/incremental" },
            ),
        ),
    )

    /** A gateway that names neither the tool nor the purpose. */
    val bareApproval: ChatState = approval.copy(
        inputRequests = listOf(approvalRequest.copy(toolName = "", description = "")),
    )

    /** The agent signs in on a page: the password manager is locked. */
    val vaultUnlock: ChatState = approval.copy(
        inputRequests = listOf(
            InputRequest.Secret("r2", InputRequest.Secret.Kind.VaultUnlock, "Hermes needs Bitwarden unlocked to sign in for you.", envVar = null, command = null),
        ),
    )

    /** The page asks for a one-time code. */
    val vaultCode: ChatState = approval.copy(
        inputRequests = listOf(
            InputRequest.Secret("r3", InputRequest.Secret.Kind.VaultCode, "Enter the sign-in code for example.com.", envVar = null, command = null),
        ),
    )

    /** A sign-in page with no login in the vault. */
    val vaultSaveLogin: ChatState = approval.copy(
        inputRequests = listOf(InputRequest.VaultSaveLogin("r4", site = "example.com", origin = "https://example.com")),
    )

    private val delegation = ToolActivity(
        id = "d1",
        name = "delegate_task",
        running = true,
        tasks = listOf(
            DelegatedTask("Check the SMART health of every disk in the NAS", SubagentStatus.Running),
            DelegatedTask("List which folders grew most since March", SubagentStatus.Running),
            DelegatedTask("Draft a weekly email report of backup sizes", SubagentStatus.Running),
        ),
    )

    /** Three subagents working on parts of a bigger job, one of them already done. */
    val subagents: ChatState = connected.copy(
        title = "Audit the home server",
        messages = listOf(
            ChatMessage.User("u1", "Do a health check of the home server: disks, storage growth, and set up a weekly backup report."),
            ChatMessage.Assistant(
                key = "a1",
                text = "I've split this into three jobs that run side by side.",
                tools = listOf(delegation),
                streaming = true,
            ),
        ),
        running = true,
        subagents = listOf(
            Subagent(
                id = "s1",
                toolId = "d1",
                goal = "Check the SMART health of every disk in the NAS",
                status = SubagentStatus.Done,
                model = "claude-haiku-4-5",
                toolCount = 4,
                activity = listOf("terminal: smartctl -H /dev/sda", "terminal: smartctl -H /dev/sdb"),
                summary = "Both disks pass. /dev/sdb has 2 reallocated sectors; worth watching, not urgent.",
                durationSeconds = 38.0,
            ),
            Subagent(
                id = "s2",
                toolId = "d1",
                taskIndex = 1,
                goal = "List which folders grew most since March",
                status = SubagentStatus.Running,
                model = "claude-haiku-4-5",
                toolCount = 3,
                activity = listOf("terminal: du -sh /mnt/nas/*"),
                thinking = "Comparing folder sizes with the March snapshot",
            ),
            Subagent(
                id = "s3",
                toolId = "d1",
                taskIndex = 2,
                goal = "Draft a weekly email report of backup sizes",
                status = SubagentStatus.Running,
                model = "claude-haiku-4-5",
                toolCount = 1,
                activity = listOf("write_file: /opt/backup/report.sh"),
            ),
        ),
    )

    /** A hands-free voice chat, a live call listening, so Mute shows. */
    val voice: ChatState = reply
    val listening = VoiceChatState(phase = VoicePhase.Listening, level = 0.55f, hearing = true, caption = "Yes, run it now, and mail me the report", live = true)

    /** A new chat, before the first prompt. */
    val empty: ChatState = connected.copy(title = null)

    /** The sidebar's list: a couple pinned, the rest by when they were last used. */
    fun sessions(nowSeconds: Double = Clock.System.now().epochSeconds.toDouble()): List<SessionSummary> {
        fun ago(minutes: Int) = nowSeconds - minutes * 60
        return listOf(
            SessionSummary("p1", "Home server runbook", pinned = true, lastActive = ago(60 * 26)),
            SessionSummary("p2", "Weekly meal plan", pinned = true, lastActive = ago(60 * 24 * 3)),
            SessionSummary("s1", TITLE, lastActive = ago(2), isActive = true),
            SessionSummary("s2", "Audit the home server", lastActive = ago(18)),
            SessionSummary("s3", "Summarize the router changelog", lastActive = ago(60 * 2)),
            SessionSummary("s4", "Plan a weekend in Vienna", lastActive = ago(60 * 5)),
            SessionSummary("s5", "Draft a reply to the landlord", lastActive = ago(60 * 9)),
            SessionSummary("s6", "Rename photos by date taken", lastActive = ago(60 * 24)),
            SessionSummary("s7", "Compare two NAS drives", lastActive = ago(60 * 24 * 2)),
            SessionSummary("s8", "Explain this Python traceback", lastActive = ago(60 * 24 * 4)),
            SessionSummary("s9", "Set up a Grafana dashboard", lastActive = ago(60 * 24 * 6)),
        )
    }

    /** What the sidebar says about some of [sessions]: one running, two waiting on the user, one unread. */
    val sessionStatuses = mapOf(
        "s1" to RowStatus(running = true),
        "s2" to RowStatus(waiting = Waiting.Approval),
        "s3" to RowStatus(unread = true),
        "s4" to RowStatus(waiting = Waiting.Question),
    )

    /** Chats in [sessions] holding unsent text. */
    val sessionDrafts = setOf("s5")

    /** Projects the sample chats sit in: two git roots and Home. */
    val projects = listOf(
        Project("/srv/backup", "backup", "/srv/backup", sessionCount = 6, isAuto = true),
        Project("p-site", "website", "/srv/site", sessionCount = 3),
        Project("__no_project__", "Home", null, sessionCount = 9, isNoProject = true),
    )

    /** Two comments on [reply]'s answer, waiting in the composer with their notes. */
    fun comments(): List<PendingComment> {
        val source = CommentSource(messageKey = answer.key, label = LAST_REPLY, markdown = answer.text)
        fun on(block: String, words: String, id: Long, note: String): PendingComment {
            val start = block.indexOf(words)
            return newComment(id, source, SelectionAnchor(listOf(block), 0, start, 0, start + words.length), note)
        }
        return listOf(
            on("It now keeps the last 14 snapshots and removes older ones after each run.", "last 14 snapshots", 1, "Make it 30, I want a month to go back to."),
            on("Tonight's run will free about 1.3 TB. Want me to run it now instead?", "run it now", 2, "Yes, and mail me the report."),
        )
    }

    const val USER = "Alex Morgan"
}

/** Does nothing: previews only draw. */
internal class PreviewChatActions(text: String = "") : ChatActions {
    override val composer = TextFieldState(text)
    override fun send(mode: RunningSend?) = Unit
    override fun interrupt() = Unit
    override fun removeAttachment(id: String) = Unit
    override fun addComment(source: CommentSource, anchor: SelectionAnchor) = 0L
    override fun removeComment(id: Long) = Unit
    override fun explain(source: CommentSource, anchor: SelectionAnchor) = Unit
    override fun askAside(source: CommentSource, anchor: SelectionAnchor) = Unit
    override fun answer(request: InputRequest, result: JsonObject) = Unit
    override fun pickSuggestion(suggestion: SlashSuggestion) = Unit
    override suspend fun loadMedia(source: String): ByteArray? = null
    override fun stopSubagent(subagentId: String) = Unit
    override fun editLastPrompt(key: String) = Unit
    override fun branchFrom(key: String) = Unit
    override fun regenerate(key: String) = Unit
    override fun startEdit(key: String) = Unit
    override fun cancelEdit() = Unit
    override fun checkDelivery(key: String) = Unit
    override fun resend(key: String) = Unit
    override fun editMessage(key: String) = Unit
    override fun retry() = Unit
    override fun dismissError() = Unit
    override fun dismissAttachmentError() = Unit
    override fun skipSpeech() = Unit
    override fun stopVoiceChat() = Unit
    override fun dismissVoiceChatError() = Unit
    override fun dismissDictationError() = Unit
}
