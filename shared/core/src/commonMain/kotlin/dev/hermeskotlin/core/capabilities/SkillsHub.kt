package dev.hermeskotlin.core.capabilities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A skill the hub offers (featured, searched or from the official catalog). */
@Serializable
data class HubSkill(
    val name: String = "",
    val description: String = "",
    val source: String = "",
    val identifier: String = "",
    @SerialName("trust_level") val trustLevel: String = "",
    val repo: String? = null,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val installed: Boolean = false,
) {
    val isOfficial: Boolean get() = source == "official" || identifier.startsWith("official/")

    /** What install and uninstall take: the identifier, or the name when the hub gave none. */
    val installKey: String get() = identifier.ifBlank { name }
}

/** What the profile has installed from the hub, keyed by identifier. */
@Serializable
data class HubInstalled(
    val name: String? = null,
    @SerialName("trust_level") val trustLevel: String? = null,
    @SerialName("scan_verdict") val scanVerdict: String? = null,
)

/** A place the hub searches. */
@Serializable
data class HubSource(
    val id: String = "",
    val label: String = id,
    @SerialName("rate_limited") val rateLimited: Boolean = false,
    val available: Boolean = true,
)

/** `GET /api/skills/hub/sources`: the sources, the featured skills and what is already installed. */
@Serializable
data class HubSources(
    val sources: List<HubSource> = emptyList(),
    val featured: List<HubSkill> = emptyList(),
    val installed: Map<String, HubInstalled> = emptyMap(),
)

/** A hub search; `timedOut` names the sources that did not answer in time. */
@Serializable
data class HubSearch(
    val results: List<HubSkill> = emptyList(),
    @SerialName("timed_out") val timedOut: List<String> = emptyList(),
    val installed: Map<String, HubInstalled> = emptyMap(),
)

/** A skill's SKILL.md and file list, shown before installing. */
@Serializable
data class HubPreview(
    val name: String = "",
    val description: String = "",
    val source: String = "",
    val identifier: String = "",
    @SerialName("trust_level") val trustLevel: String = "",
    val repo: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("skill_md") val skillMd: String = "",
    val files: List<String> = emptyList(),
) {
    /** [skillMd] without its YAML front matter, whose name, description and tags the sheet already shows. */
    val body: String
        get() {
            val lines = skillMd.lines()
            if (lines.firstOrNull()?.trim() != "---") return skillMd
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
            if (end < 0) return skillMd
            return lines.drop(end + 2).joinToString("\n").trimStart('\n')
        }
}

/** One thing the security scan flagged. */
@Serializable
data class HubFinding(
    val severity: String = "",
    val category: String = "",
    val file: String = "",
    val line: Int = 0,
    val description: String = "",
)

/** The security scan of a skill; `policy` says whether the gateway would install it, ask, or refuse. */
@Serializable
data class HubScan(
    val name: String = "",
    val identifier: String = "",
    val source: String = "",
    @SerialName("trust_level") val trustLevel: String = "",
    val verdict: String = "",
    val summary: String = "",
    val policy: String = "",
    @SerialName("policy_reason") val policyReason: String = "",
    val findings: List<HubFinding> = emptyList(),
)

/** How a background hub action (install, uninstall, update) ended; [lines] is what to show for it. */
sealed interface HubActionResult {
    val lines: List<String>

    data class Done(override val lines: List<String>) : HubActionResult

    /** The security scan refused the install; [findings] is how many it counted, when the log says. */
    data class Blocked(val findings: Int?, override val lines: List<String>) : HubActionResult

    data class Failed(override val lines: List<String>) : HubActionResult

    /** Herald stopped waiting; the gateway may still finish it. [lines] says so. */
    data class StillRunning(override val lines: List<String>) : HubActionResult
}

private val scanBlockedNow = Regex("""Not installed:\s+the security scan found\s+(?:(\d+)\s+)?high-risk\s+pattern""", RegexOption.IGNORE_CASE)
private val scanBlockedLegacy = Regex("""Installation blocked:.*?\(([a-z_-]+) source \+ ([a-z_]+) verdict, (\d+) findings?\)""", RegexOption.IGNORE_CASE)

/** [Blocked][HubActionResult.Blocked] when the action's log says the scan refused it, else null. */
internal fun scanBlock(lines: List<String>): HubActionResult.Blocked? {
    for (line in lines) {
        scanBlockedNow.find(line)?.let { return HubActionResult.Blocked(it.groupValues[1].toIntOrNull(), lines) }
        scanBlockedLegacy.find(line)?.let { return HubActionResult.Blocked(it.groupValues[3].toIntOrNull(), lines) }
    }
    return null
}
