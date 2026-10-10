package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.jsonObject

/**
 * Real snapshots produced by the server's `_snapshot_control`, verbatim from
 * herald-goals/control-snapshots.json (`empty`, `goal_active`, `goal_paused`, `goal_waiting`,
 * `goal_loop_heartbeat`, `goal_done`).
 */
internal object ControlFixtures {
    private val all = HermesJson.parseToJsonElement(
        """
        {
         "empty": {
          "goal": null,
          "loop": null,
          "heartbeat": null,
          "revision": "",
          "updated_at": 0
         },
         "goal_active": {
          "goal": {
           "title": "Get the test suite green on the feat/goals branch",
           "status": "active",
           "turns_used": 4,
           "max_turns": 20,
           "contract": {
            "outcome": "",
            "verification": "",
            "constraints": "",
            "boundaries": "",
            "stop_when": ""
           },
           "subgoals": [
            "Fix the flaky reconnect test",
            "Pin the wire contract in a test"
           ],
           "gates": [
            {
             "command": "./gradlew :shared:core:testAndroidHostTest",
             "timeout_seconds": 600,
             "max_retries": 2,
             "attempts": 0,
             "last_exit_code": null
            }
           ],
           "created_at": 1791645333.5273962,
           "last_verdict": "continue",
           "last_reason": "Two tests still fail in ChatSessionTest."
          },
          "loop": null,
          "heartbeat": null,
          "revision": "4f3e15ca7c831c1c3a1918666e3e9f3fae7106716c932de8eac41a91bdf9ae81",
          "updated_at": 1791645333.5273962
         },
         "goal_paused": {
          "goal": {
           "title": "Get the test suite green on the feat/goals branch",
           "status": "paused",
           "turns_used": 4,
           "max_turns": 20,
           "contract": {
            "outcome": "",
            "verification": "",
            "constraints": "",
            "boundaries": "",
            "stop_when": ""
           },
           "subgoals": [
            "Fix the flaky reconnect test",
            "Pin the wire contract in a test"
           ],
           "gates": [
            {
             "command": "./gradlew :shared:core:testAndroidHostTest",
             "timeout_seconds": 600,
             "max_retries": 2,
             "attempts": 0,
             "last_exit_code": null
            }
           ],
           "created_at": 1791645333.5273962,
           "paused_reason": "user-paused",
           "last_verdict": "continue",
           "last_reason": "Two tests still fail in ChatSessionTest."
          },
          "loop": null,
          "heartbeat": null,
          "revision": "f1904628a7e61080720f52af12a47c763613adb76881dc27afb9cbcafe4c68a2",
          "updated_at": 1791645333.5273962
         },
         "goal_waiting": {
          "goal": {
           "title": "Get the test suite green on the feat/goals branch",
           "status": "active",
           "turns_used": 0,
           "max_turns": 20,
           "contract": {
            "outcome": "",
            "verification": "",
            "constraints": "",
            "boundaries": "",
            "stop_when": ""
           },
           "subgoals": [
            "Fix the flaky reconnect test",
            "Pin the wire contract in a test"
           ],
           "gates": [
            {
             "command": "./gradlew :shared:core:testAndroidHostTest",
             "timeout_seconds": 600,
             "max_retries": 2,
             "attempts": 0,
             "last_exit_code": null
            }
           ],
           "created_at": 1791645333.5273962,
           "last_verdict": "continue",
           "last_reason": "Two tests still fail in ChatSessionTest.",
           "wait_barrier": {
            "type": "until",
            "until_at": 1791646233.593687,
            "reason": "CI is running; check back when it finishes"
           }
          },
          "loop": null,
          "heartbeat": null,
          "revision": "60fe8ff0f105fb091d6a92e0a7ac7235f8e8dc70fc5e0ab0297b510c7c357bcb",
          "updated_at": 1791645333.5273962
         },
         "goal_loop_heartbeat": {
          "goal": {
           "title": "Get the test suite green on the feat/goals branch",
           "status": "active",
           "turns_used": 0,
           "max_turns": 20,
           "contract": {
            "outcome": "",
            "verification": "",
            "constraints": "",
            "boundaries": "",
            "stop_when": ""
           },
           "subgoals": [
            "Fix the flaky reconnect test",
            "Pin the wire contract in a test"
           ],
           "gates": [
            {
             "command": "./gradlew :shared:core:testAndroidHostTest",
             "timeout_seconds": 600,
             "max_retries": 2,
             "attempts": 0,
             "last_exit_code": null
            }
           ],
           "created_at": 1791645333.5273962,
           "last_verdict": "continue",
           "last_reason": "Two tests still fail in ChatSessionTest."
          },
          "loop": {
           "prompt": "Check the deploy log and report errors",
           "status": "active",
           "mode": "interval",
           "interval_seconds": 1800.0,
           "current_delay": 1800.0,
           "times": 6,
           "until": "",
           "max_ticks": 100,
           "ticks_fired": 0,
           "created_at": 1791645333.61065,
           "last_fired_at": 0.0,
           "next_due_at": 1791645333.61065,
           "awaiting_response": false,
           "deferred_by_goal": true
          },
          "heartbeat": {
           "prompt": "Any new GitHub notifications?",
           "status": "active",
           "interval_seconds": 3600,
           "created_at": 1791645333.6209419,
           "last_fired_at": 0.0,
           "fire_count": 0
          },
          "revision": "dced25cac75f65f0dce50607239ba5a41dbc0b9b91d1c4e75503dde64af77467",
          "updated_at": 1791645333.6209419
         },
         "goal_done": {
          "goal": {
           "title": "Get the test suite green on the feat/goals branch",
           "status": "done",
           "turns_used": 0,
           "max_turns": 20,
           "contract": {
            "outcome": "",
            "verification": "",
            "constraints": "",
            "boundaries": "",
            "stop_when": ""
           },
           "subgoals": [
            "Fix the flaky reconnect test",
            "Pin the wire contract in a test"
           ],
           "gates": [
            {
             "command": "./gradlew :shared:core:testAndroidHostTest",
             "timeout_seconds": 600,
             "max_retries": 2,
             "attempts": 0,
             "last_exit_code": null
            }
           ],
           "created_at": 1791645333.5273962,
           "last_verdict": "done",
           "last_reason": "All tests pass and CI is green."
          },
          "loop": {
           "prompt": "Check the deploy log and report errors",
           "status": "active",
           "mode": "interval",
           "interval_seconds": 1800.0,
           "current_delay": 1800.0,
           "times": 6,
           "until": "",
           "max_ticks": 100,
           "ticks_fired": 0,
           "created_at": 1791645333.61065,
           "last_fired_at": 0.0,
           "next_due_at": 1791645333.61065,
           "awaiting_response": false,
           "deferred_by_goal": false
          },
          "heartbeat": {
           "prompt": "Any new GitHub notifications?",
           "status": "active",
           "interval_seconds": 3600,
           "created_at": 1791645333.6209419,
           "last_fired_at": 0.0,
           "fire_count": 0
          },
          "revision": "94c2dd7391c2035954c6121cd9a5103e58590cefba9fbf5dfae071c2c9b1d7cc",
          "updated_at": 1791645333.6209419
         }
        }
        """.trimIndent(),
    ).jsonObject

    val EMPTY = all.getValue("empty").toString()
    val GOAL_ACTIVE = all.getValue("goal_active").toString()
    val GOAL_PAUSED = all.getValue("goal_paused").toString()
    val GOAL_WAITING = all.getValue("goal_waiting").toString()
    val GOAL_LOOP_HEARTBEAT = all.getValue("goal_loop_heartbeat").toString()
    val GOAL_DONE = all.getValue("goal_done").toString()

    fun parse(snapshot: String): SessionControl? =
        SessionControl.parse(HermesJson.parseToJsonElement(snapshot).jsonObject)
}
