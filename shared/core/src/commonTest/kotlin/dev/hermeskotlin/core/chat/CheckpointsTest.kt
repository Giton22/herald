package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckpointsTest {

    private fun obj(json: String) = HermesJson.parseToJsonElement(json).jsonObject

    @Test
    fun listKeepsTheGatewaysOrderAndSkipsRowsWithoutAHash() {
        // Shaped like methods_tools.py's rollback.list in hermes-agent v2026.10.7.
        val list = Checkpoints.parse(
            obj(
                """{"enabled":true,"checkpoints":[
                    {"hash":"9f2c1ab47e0d","timestamp":"2026-10-08T20:41:03+03:00","message":"before write_file app.py"},
                    {"hash":"","timestamp":"x","message":"broken"},
                    {"hash":"11aa22bb33cc","timestamp":"2026-10-08T20:30:00+03:00","message":"auto"}]}""",
            ),
        )
        assertTrue(list.enabled)
        assertEquals(listOf("9f2c1ab4", "11aa22bb"), list.checkpoints.map { it.shortHash })
        assertEquals("before write_file app.py", list.checkpoints.first().message)
    }

    @Test
    fun checkpointsOffInTheProfile() {
        val list = Checkpoints.parse(obj("""{"enabled":false,"checkpoints":[]}"""))
        assertFalse(list.enabled)
        assertTrue(list.checkpoints.isEmpty())
    }

    @Test
    fun anEmptyDiffMeansNothingChanged() {
        assertTrue(CheckpointDiff.parse(obj("""{"stat":"","diff":""}""")).unchanged)
        val diff = CheckpointDiff.parse(obj("""{"stat":" app.py | 2 +-","diff":"-old\n+new"}"""))
        assertFalse(diff.unchanged)
        assertFalse(CheckpointDiff(error = "nope").unchanged)
    }

    @Test
    fun restoreOutcomes() {
        assertEquals(
            "Restored 2 files to 9f2c1ab4. The chat's last turn was taken back too.",
            restoreOutcome(obj("""{"success":true,"restored_to":"9f2c1ab47e0d","restored_files":["a.py","b.py"],"history_removed":4}""")),
        )
        assertEquals(
            "Restored 1 file. Left alone, since you changed them yourself: notes.md.",
            restoreOutcome(obj("""{"success":true,"restored_files":["a.py"],"skipped_user_edits":["notes.md"],"history_removed":0}""")),
        )
        assertEquals("Restored the folder.", restoreOutcome(obj("""{"success":true}""")))
        assertEquals("Checkpoint 'zz' not found", restoreOutcome(obj("""{"success":false,"error":"Checkpoint 'zz' not found"}""")))
        assertEquals("Couldn't restore the checkpoint.", restoreOutcome(null))
    }
}
