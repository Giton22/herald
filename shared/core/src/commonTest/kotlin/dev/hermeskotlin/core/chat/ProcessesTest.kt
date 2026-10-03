package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessesTest {

    @Test
    fun runningFirstWithTheFullOutputTail() {
        val result = HermesJson.parseToJsonElement(
            """{"processes":[
               {"session_id":"proc_a","command":"pytest -x","cwd":"/srv/app","pid":4120,"started_at":"2026-10-03T15:00:00",
                "uptime_seconds":75,"status":"exited","output_preview":"…passed","exit_code":1,"output_tail":"FAILED test_x\n1 failed"},
               {"session_id":"proc_b","command":"npm run dev","cwd":"/srv/web","pid":4188,"uptime_seconds":3900,
                "status":"running","output_preview":"ready","output_tail":"VITE ready on :5173"}]}""",
        ).jsonObject

        val processes = BackgroundProcess.parseList(result)

        assertEquals(listOf("proc_b", "proc_a"), processes.map { it.id })
        assertTrue(processes[0].running)
        assertEquals("VITE ready on :5173", processes[0].output)
        assertFalse(processes[1].running)
        assertEquals(1, processes[1].exitCode)
    }

    @Test
    fun killOutcomes() {
        fun outcome(json: String) = killOutcome(HermesJson.parseToJsonElement(json).jsonObject)
        assertEquals("Stopped.", outcome("""{"status":"killed","exit_code":-15}"""))
        assertEquals("It had already finished.", outcome("""{"status":"already_exited"}"""))
        assertEquals("permission denied", outcome("""{"status":"error","error":"permission denied"}"""))
    }
}
