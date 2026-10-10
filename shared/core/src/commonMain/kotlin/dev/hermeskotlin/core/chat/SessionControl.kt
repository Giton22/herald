package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The automation a session carries (tui_gateway/methods_session_control.py): a goal the agent works
 * toward over turns, a loop that fires a prompt on a cadence, and a heartbeat. A snapshot whose three
 * parts are all null means the session has none, so it parses to null rather than an empty shell.
 *
 * Parsed leniently, the way the rest of the chat payloads are: a part that's present but malformed is
 * dropped on its own, an unknown status keeps its raw value, and numbers may arrive as int or float.
 */
data class SessionControl(
    val goal: GoalControl?,
    val loop: LoopControl?,
    val heartbeat: HeartbeatControl?,
    /** The gateway's hash of the visible state; an unchanged one means nothing changed. */
    val revision: String,
) {
    companion object {
        /** A `control` snapshot from `session.control.read`, `session.control` or `session.control.update`. */
        fun parse(json: JsonObject?): SessionControl? {
            val goal = GoalControl.parse(json?.get("goal") as? JsonObject)
            val loop = LoopControl.parse(json?.get("loop") as? JsonObject)
            val heartbeat = HeartbeatControl.parse(json?.get("heartbeat") as? JsonObject)
            if (goal == null && loop == null && heartbeat == null) return null
            return SessionControl(goal, loop, heartbeat, json.string("revision").orEmpty())
        }
    }
}

/** How a goal reads at a glance, from the same precedence Desktop's visibleState uses. */
enum class GoalPhase { Active, Paused, Waiting, Blocked, Done }

data class GoalControl(
    val title: String,
    /** As the gateway sent it, since a newer one may name a phase this build doesn't know. */
    val status: String,
    val turnsUsed: Int,
    /** 0: no limit. */
    val maxTurns: Int,
    val contract: GoalContract,
    /** Shown as the goal's criteria; removal addresses them 1-based. */
    val subgoals: List<String>,
    val gates: List<GoalGate>,
    val pausedReason: String?,
    val lastVerdict: String?,
    val lastReason: String?,
    val waitBarrier: WaitBarrier?,
    val updatedAt: Double?,
) {
    /** Blocked wins the label, then waiting, paused, done, active. */
    val phase: GoalPhase
        get() = when {
            lastVerdict == "blocked" -> GoalPhase.Blocked
            waitBarrier != null -> GoalPhase.Waiting
            status == "paused" -> GoalPhase.Paused
            status == "done" -> GoalPhase.Done
            else -> GoalPhase.Active
        }

    companion object {
        fun parse(json: JsonObject?): GoalControl? {
            val title = json.string("title") ?: return null
            return GoalControl(
                title = title,
                status = json.string("status") ?: "active",
                turnsUsed = json.flexInt("turns_used") ?: 0,
                maxTurns = json.flexInt("max_turns") ?: 0,
                contract = GoalContract.parse(json?.get("contract") as? JsonObject),
                subgoals = (json?.get("subgoals") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                gates = (json?.get("gates") as? JsonArray).orEmpty().mapNotNull { GoalGate.parse(it as? JsonObject) },
                pausedReason = json.string("paused_reason"),
                lastVerdict = json.string("last_verdict"),
                lastReason = json.string("last_reason"),
                waitBarrier = WaitBarrier.parse(json?.get("wait_barrier") as? JsonObject),
                updatedAt = json.double("updated_at"),
            )
        }
    }
}

/** What done means and how it's checked; all empty until the goal spells it out. */
data class GoalContract(
    val outcome: String,
    val verification: String,
    val constraints: String,
    val boundaries: String,
    val stopWhen: String,
) {
    val isEmpty: Boolean get() = outcome.isEmpty() && verification.isEmpty() && constraints.isEmpty() && boundaries.isEmpty() && stopWhen.isEmpty()

    companion object {
        fun parse(json: JsonObject?) = GoalContract(
            outcome = json.string("outcome").orEmpty(),
            verification = json.string("verification").orEmpty(),
            constraints = json.string("constraints").orEmpty(),
            boundaries = json.string("boundaries").orEmpty(),
            stopWhen = json.string("stop_when").orEmpty(),
        )
    }
}

/** A shell check the goal's judge runs; [attempts] 0 means it hasn't run yet. */
data class GoalGate(
    val command: String,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val attempts: Int,
    val lastExitCode: Int?,
) {
    companion object {
        fun parse(json: JsonObject?): GoalGate? {
            val command = json.string("command") ?: return null
            return GoalGate(
                command = command,
                timeoutSeconds = json.flexInt("timeout_seconds") ?: 0,
                maxRetries = json.flexInt("max_retries") ?: 0,
                attempts = json.flexInt("attempts") ?: 0,
                lastExitCode = json.flexInt("last_exit_code"),
            )
        }
    }
}

/** What a waiting goal is parked on. */
sealed interface WaitBarrier {
    val reason: String

    data class Until(val untilAt: Double, override val reason: String) : WaitBarrier
    data class OnSession(val target: String, override val reason: String) : WaitBarrier
    data class OnProcess(val pid: Long, override val reason: String) : WaitBarrier

    companion object {
        fun parse(json: JsonObject?): WaitBarrier? {
            val reason = json.string("reason").orEmpty()
            return when (json.string("type")) {
                "until" -> json.double("until_at")?.let { Until(it, reason) }
                "session" -> json.string("target")?.let { OnSession(it, reason) }
                "pid" -> json.double("target")?.let { OnProcess(it.toLong(), reason) }
                else -> null
            }
        }
    }
}

/** A prompt that fires on an interval (or self-paced), [times] runs at most (0: unlimited). */
data class LoopControl(
    val prompt: String,
    /** As the gateway sent it. */
    val status: String,
    val selfPaced: Boolean,
    val intervalSeconds: Double,
    val times: Int,
    /** The condition that ends it early; empty when there is none. */
    val until: String,
    val ticksFired: Int,
    /** 0: it never fired. */
    val lastFiredAt: Double,
    val nextDueAt: Double,
    val awaitingResponse: Boolean,
    /** Held back while the goal is working. */
    val deferredByGoal: Boolean,
    val pausedReason: String?,
    val lastStopReason: String?,
) {
    companion object {
        fun parse(json: JsonObject?): LoopControl? {
            val prompt = json.string("prompt") ?: return null
            return LoopControl(
                prompt = prompt,
                status = json.string("status") ?: "active",
                selfPaced = json.string("mode") == "self_paced",
                intervalSeconds = json.double("interval_seconds") ?: 0.0,
                times = json.flexInt("times") ?: 0,
                until = json.string("until").orEmpty(),
                ticksFired = json.flexInt("ticks_fired") ?: 0,
                lastFiredAt = json.double("last_fired_at") ?: 0.0,
                nextDueAt = json.double("next_due_at") ?: 0.0,
                awaitingResponse = json.boolean("awaiting_response") ?: false,
                deferredByGoal = json.boolean("deferred_by_goal") ?: false,
                pausedReason = json.string("paused_reason"),
                lastStopReason = json.string("last_stop_reason"),
            )
        }
    }
}

/** A prompt that fires every [intervalSeconds]. */
data class HeartbeatControl(
    val prompt: String,
    /** As the gateway sent it. */
    val status: String,
    val intervalSeconds: Long,
    /** 0: it never fired. */
    val lastFiredAt: Double,
    val fireCount: Int,
) {
    companion object {
        fun parse(json: JsonObject?): HeartbeatControl? {
            val prompt = json.string("prompt") ?: return null
            return HeartbeatControl(
                prompt = prompt,
                status = json.string("status") ?: "active",
                intervalSeconds = json.double("interval_seconds")?.toLong() ?: 0L,
                lastFiredAt = json.double("last_fired_at") ?: 0.0,
                fireCount = json.flexInt("fire_count") ?: 0,
            )
        }
    }
}

/** The `session.control` actions, by the wire string the gateway takes (methods_session_control.py). */
enum class ControlAction(val wire: String) {
    GoalPause("goal.pause"),
    GoalResume("goal.resume"),
    GoalClear("goal.clear"),
    GoalUnwait("goal.unwait"),
    LoopPause("loop.pause"),
    LoopResume("loop.resume"),
    LoopStop("loop.stop"),
    HeartbeatPause("heartbeat.pause"),
    HeartbeatResume("heartbeat.resume"),
    HeartbeatClear("heartbeat.clear"),
    SubgoalAdd("subgoal.add"),
    SubgoalRemove("subgoal.remove"),
    SubgoalClear("subgoal.clear"),
}

/** What to say the action does when the gateway's own message can't be had. */
internal val ControlAction.verb: String
    get() = when (this) {
        ControlAction.GoalPause -> "pause the goal"
        ControlAction.GoalResume -> "resume the goal"
        ControlAction.GoalClear -> "clear the goal"
        ControlAction.GoalUnwait -> "resume the goal"
        ControlAction.LoopPause -> "pause the loop"
        ControlAction.LoopResume -> "resume the loop"
        ControlAction.LoopStop -> "stop the loop"
        ControlAction.HeartbeatPause -> "pause the heartbeat"
        ControlAction.HeartbeatResume -> "resume the heartbeat"
        ControlAction.HeartbeatClear -> "clear the heartbeat"
        ControlAction.SubgoalAdd -> "add the sub-goal"
        ControlAction.SubgoalRemove -> "remove the sub-goal"
        ControlAction.SubgoalClear -> "clear the sub-goals"
    }

/** Numbers arrive as int or float (1800 vs 1800.0); [JsonAccess]'s int only takes the first. */
private fun JsonObject?.flexInt(key: String): Int? = int(key) ?: double(key)?.toInt()
