package dev.hermeskotlin.ui.preview

import dev.hermeskotlin.core.capabilities.Skill
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.ContextBreakdown
import dev.hermeskotlin.core.chat.ContextCategory
import dev.hermeskotlin.core.chat.ContextFile
import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.cron.DeliveryTarget
import dev.hermeskotlin.core.insights.ModelUsage
import dev.hermeskotlin.core.insights.SkillUsage
import dev.hermeskotlin.core.insights.SkillsBlock
import dev.hermeskotlin.core.insights.ToolUsage
import dev.hermeskotlin.core.insights.UsageDay
import dev.hermeskotlin.core.insights.UsageReport
import dev.hermeskotlin.core.insights.UsageTotals
import dev.hermeskotlin.core.insights.isoDateOf
import dev.hermeskotlin.core.sessions.SessionTotals
import dev.hermeskotlin.ui.chat.ProcessesState
import dev.hermeskotlin.ui.chat.UsageSheetState
import dev.hermeskotlin.ui.sessions.CapabilitiesUiState
import dev.hermeskotlin.ui.sessions.InsightsUiState
import dev.hermeskotlin.ui.sessions.JobEditor
import dev.hermeskotlin.ui.sessions.Loadable
import kotlin.time.Clock

/** Made-up data for the sidebar pages and the chat's sheets, in the same home-server story as [ChatSamples]. */
internal object PageSamples {

    /** A month of use, busier on weekdays, ending today. */
    fun insights(today: Long = Clock.System.now().toEpochMilliseconds() / 86_400_000): InsightsUiState {
        val pattern = listOf(4, 7, 5, 9, 6, 2, 1, 5, 8, 6, 11, 7, 3, 1, 6, 9, 7, 12, 8, 2, 2, 7, 10, 8, 14, 9, 3, 2, 8, 6)
        val daily = pattern.mapIndexed { i, weight ->
            UsageDay(
                day = isoDateOf(today - (pattern.size - 1 - i)),
                inputTokens = weight * 182_000L,
                outputTokens = weight * 9_400L,
                cacheReadTokens = weight * 410_000L,
                estimatedCost = weight * 0.61,
                sessions = (weight + 1) / 2,
                apiCalls = weight * 14,
            )
        }
        val report = UsageReport(
            daily = daily,
            byModel = listOf(
                ModelUsage("claude-sonnet-5-5", inputTokens = 29_100_000, outputTokens = 1_480_000, estimatedCost = 98.4, sessions = 61),
                ModelUsage("claude-haiku-4-5", inputTokens = 6_200_000, outputTokens = 410_000, estimatedCost = 7.9, sessions = 38),
                ModelUsage("claude-opus-5-5", inputTokens = 1_900_000, outputTokens = 120_000, estimatedCost = 17.6, sessions = 4),
            ),
            totals = UsageTotals(
                input = daily.sumOf { it.inputTokens },
                output = daily.sumOf { it.outputTokens },
                cacheRead = daily.sumOf { it.cacheReadTokens },
                estimatedCost = daily.sumOf { it.estimatedCost },
                sessions = daily.sumOf { it.sessions },
                apiCalls = daily.sumOf { it.apiCalls },
            ),
            periodDays = 30,
            skillsBlock = SkillsBlock(listOf(SkillUsage("homelab-runbook", 23), SkillUsage("weekly-report", 9), SkillUsage("grafana", 4))),
            tools = listOf(ToolUsage("terminal", 812), ToolUsage("read_file", 344), ToolUsage("patch", 158), ToolUsage("web_search", 97), ToolUsage("delegate_task", 31)),
        )
        return InsightsUiState(report = report, loading = false)
    }

    val capabilities = CapabilitiesUiState(
        skills = Loadable(
            listOf(
                Skill("homelab-runbook", "How the NAS, router and backups are set up, and how to fix them", category = "homelab", usage = 23),
                Skill("grafana", "Build and edit Grafana dashboards from Prometheus metrics", category = "homelab", provenance = "hub", usage = 4),
                Skill("smart-disks", "Read SMART data and judge whether a disk is failing", category = "homelab", enabled = false),
                Skill("weekly-report", "Write the Monday email of backup sizes and disk health", category = "writing", usage = 9),
                Skill("arxiv", "Search arXiv and summarize papers", category = "research", provenance = "bundled"),
                Skill("github-pr", "Open, review and merge pull requests with gh", category = "software", provenance = "bundled"),
                Skill("python-debug", "Read a traceback and find the failing line", category = "software", provenance = "bundled"),
            ),
        ),
    )

    val deliveryTargets = listOf(
        DeliveryTarget("local", "Local (save only)"),
        DeliveryTarget("telegram", "Telegram"),
        DeliveryTarget("email", "Email"),
        DeliveryTarget("discord", "Discord", homeTargetSet = false),
    )

    val jobEditor = JobEditor(deliver = "telegram")
    const val JOB_PROMPT = "Check last night's backup, the free space on the NAS and the SMART health of each disk. Send me a short summary, and say first if anything needs me."
    const val JOB_SCHEDULE = "0 9 * * 1-5"
    const val JOB_NAME = "Morning server check"

    val usage = UsageSheetState(
        totals = SessionTotals(
            inputTokens = 184_200,
            outputTokens = 6_120,
            cacheReadTokens = 512_800,
            reasoningTokens = 2_400,
            apiCalls = 14,
            estimatedCostUsd = 0.86,
            model = "claude-sonnet-5-5",
        ),
        breakdown = ContextBreakdown(
            categories = listOf(
                ContextCategory("system_prompt", "System prompt", 3_800),
                ContextCategory("tool_definitions", "Tool definitions", 14_200),
                ContextCategory("rules", "Rules", 1_900),
                ContextCategory("skills", "Skills", 4_600),
                ContextCategory("mcp", "MCP tools", 6_300),
                ContextCategory("memory", "Memory", 2_100),
                ContextCategory("conversation", "Conversation", 38_700),
            ),
            used = 71_600,
            max = 200_000,
            estimated = false,
            files = listOf(
                ContextFile("AGENTS.md", "~/homelab/AGENTS.md", 1_200, loaded = true),
                ContextFile("homelab-runbook", "~/.hermes/skills/homelab-runbook/SKILL.md", 2_900, loaded = true),
            ),
        ),
        limits = listOf("Weekly limit: 38% used, resets Monday"),
    )

    val liveUsage = SessionUsage(input = 184_200, output = 6_120, contextUsed = 71_600, contextMax = 200_000, contextPercent = 36)

    val processes = ProcessesState(
        processes = listOf(
            BackgroundProcess(
                id = "proc_1",
                command = "rsync -a --info=progress2 /srv/photos/ /mnt/nas/backup/2026-10-03/",
                cwd = "/opt/backup",
                pid = 48211,
                running = true,
                uptimeSeconds = 754,
                exitCode = null,
                output = "    412.88G  61%   88.41MB/s    1:18:02 (xfr#18214, to-chk=11902/31040)\n" +
                    "    418.02G  62%   89.17MB/s    1:15:31 (xfr#18522, to-chk=11594/31040)\n" +
                    "    423.40G  63%   90.02MB/s    1:12:58 (xfr#18871, to-chk=11245/31040)",
            ),
            BackgroundProcess(
                id = "proc_2",
                command = "python3 -m http.server 8080",
                cwd = "/opt/backup/report",
                pid = 48302,
                running = true,
                uptimeSeconds = 312,
                exitCode = null,
                output = "Serving HTTP on 0.0.0.0 port 8080 (http://0.0.0.0:8080/) ...",
            ),
            BackgroundProcess(
                id = "proc_3",
                command = "smartctl -t long /dev/sdb",
                cwd = null,
                pid = 47990,
                running = false,
                uptimeSeconds = null,
                exitCode = 0,
                output = "Testing has begun.\nPlease wait 255 minutes for test to complete.",
            ),
        ),
    )
}
