package dev.hermeskotlin.core.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `hermes-bots-groups` mirror, as Desktop writes it (group-chat.ts v3). */
class DesktopRoomsTest {

    private fun uiMeta(json: String): JsonObject =
        Json.parseToJsonElement("""{"hermes-bots-groups":$json}""").jsonObject

    @Test
    fun readsRoomsWithTheirRosterAndLog() {
        val rooms = parseDesktopRooms(
            uiMeta(
                """
                {"version":3,"updatedAt":1791338799073,
                 "rooms":{"id:r1":{"name":"OFM ops","roomId":"r1","revision":345,"omitted":204,"holdDetection":true,
                    "members":[{"name":"default","handle":"hermes","connectionId":"local"},{"name":"agora","handle":"agora"}],
                    "log":[{"id":"m1","from":{"kind":"user","name":"You"},"text":"once","at":1791338700000},
                           {"id":"m2","from":{"kind":"member","name":"agora","source":"This device"},"text":"on it","at":1791338797743,"thread":"t1"}]}}}
                """.trimIndent(),
            ),
        )
        assertEquals(1, rooms.size)
        val room = rooms.single()
        assertEquals("id:r1", room.key)
        assertEquals("OFM ops", room.name)
        assertEquals("r1", room.roomId)
        assertEquals(345, room.revision)
        assertEquals(204, room.omitted)
        assertEquals(listOf("default", "agora"), room.members.map { it.name })
        assertEquals("hermes", room.members.first().handle)
        assertEquals(2, room.lines.size)
        assertTrue(room.lines.first().fromUser)
        assertEquals("You", room.lines.first().speaker)
        assertEquals("agora", room.lines.last().speaker)
        assertEquals("This device", room.lines.last().source)
        assertEquals(1791338797743.0, room.lines.last().at)
        assertEquals(1791338797743.0, room.updatedAt)
    }

    @Test
    fun newestFirstAndKeyFallsBackForLegacyRooms() {
        val rooms = parseDesktopRooms(
            uiMeta(
                """
                {"version":3,
                 "rooms":{"name:Older room":{"name":"Older room","revision":2,"log":[{"from":{"kind":"member","name":"a"},"text":"old","at":1000}]},
                          "name:Newer room":{"name":"Newer room","revision":1,"log":[{"from":{"kind":"member","name":"b"},"text":"new","at":2000}]}}}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Newer room", "Older room"), rooms.map { it.name })
        assertEquals("r9", parseDesktopRooms(uiMeta("""{"version":3,"rooms":{"id:r9":{"revision":1}}}""")).single().name)
    }

    @Test
    fun tombstonesDecideWhatIsGone() {
        val rooms = parseDesktopRooms(
            uiMeta(
                """
                {"version":3,
                 "deleted":{"id:r1":1,"name:Renamed":93,"name:Recreated":90},
                 "rooms":{"id:r1":{"name":"Gone by id","revision":99},
                          "name:Renamed":{"name":"Renamed","revision":93},
                          "name:Recreated":{"name":"Recreated","revision":95},
                          "name:Survivor":{"name":"Survivor","revision":2}}}
                """.trimIndent(),
            ),
        )
        // An id: tombstone deletes whatever the revision; a name: one only from the revision it names on —
        // a same-name recreate starts over below it and must survive.
        assertEquals(listOf("Recreated", "Survivor"), rooms.map { it.name })
    }

    @Test
    fun needsYouFollowsTheNewestMemberLine() {
        fun room(log: String) = parseDesktopRooms(
            uiMeta("""{"version":3,"rooms":{"id:r":{"name":"R","revision":1,"log":$log}}}"""),
        ).single()

        assertTrue(room("""[{"from":{"kind":"member","name":"a"},"text":"@user take a look","at":1}]""").needsYou)
        assertTrue(room("""[{"from":{"kind":"member","name":"a"},"text":"@User?","at":1}]""").needsYou)
        // The newest member line decides: an older @user line was already answered by a later one.
        assertFalse(
            room(
                """[{"from":{"kind":"member","name":"a"},"text":"@user?","at":1},
                    {"from":{"kind":"member","name":"a"},"text":"handled it","at":2}]""",
            ).needsYou,
        )
        // The user's own words don't count, and @username is not @user.
        assertFalse(room("""[{"from":{"kind":"user","name":"You"},"text":"@user","at":3}]""").needsYou)
        assertFalse(room("""[{"from":{"kind":"member","name":"a"},"text":"@username ping","at":4}]""").needsYou)
    }

    @Test
    fun readsLenientlyAndEmptyWithoutAMirror() {
        assertEquals(emptyList(), parseDesktopRooms(null))
        assertEquals(emptyList(), parseDesktopRooms(JsonObject(emptyMap())))
        val rooms = parseDesktopRooms(
            uiMeta(
                """
                {"version":3,"rooms":{"id:r1":"nonsense",
                    "id:r2":{"name":"Ok","revision":1,"members":[{"handle":"no-name"},{"name":"fine"}],
                             "log":[{"text":"no sender"},{"from":{"kind":"member","name":"a"}},{"from":{"kind":"member","name":"a"},"text":"kept","truncated":true}]}}}
                """.trimIndent(),
            ),
        )
        val room = rooms.single()
        assertEquals("Ok", room.name)
        assertEquals(listOf("fine"), room.members.map { it.name })
        assertEquals(listOf("kept"), room.lines.map { it.text })
        assertTrue(room.lines.single().truncated)
        assertEquals("a", room.lines.single().speaker)
        assertNull(room.updatedAt)
        assertFalse(room.needsYou)
    }
}
