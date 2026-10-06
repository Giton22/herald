package dev.hermeskotlin.core.rooms

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomSeenTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val other = GatewayUrl.parse("https://other.example.ts.net")

    private fun room(id: String, latest: Int) = Room(roomId = id, name = id, latestSeq = latest)

    @Test
    fun aRoomFirstMetIsReadAndOnlyLaterLinesAreNew() = runTest {
        val store = RoomSeenStore(InMemoryKeyValueStore())
        store.takeStock(gateway, listOf(room("a", 4)))
        assertFalse(room("a", 4).isUnread(store.seen(gateway).first()))
        assertTrue(room("a", 6).isUnread(store.seen(gateway).first()))

        store.markSeen(gateway, "a", 6)
        assertFalse(room("a", 6).isUnread(store.seen(gateway).first()))
        // An older seq, or taking stock again, never moves it back.
        store.markSeen(gateway, "a", 2)
        store.takeStock(gateway, listOf(room("a", 1)))
        assertEquals(6, store.seen(gateway).first()["a"])
    }

    @Test
    fun aRoomNeverSeenIsntUnreadAndGatewaysAreApart() = runTest {
        val store = RoomSeenStore(InMemoryKeyValueStore())
        assertFalse(room("a", 9).isUnread(store.seen(gateway).first()))
        store.markSeen(gateway, "a", 3)
        assertTrue(store.seen(other).first().isEmpty())
    }

    @Test
    fun itsKeptAcrossRestarts() = runTest {
        val disk = InMemoryKeyValueStore()
        RoomSeenStore(disk).markSeen(gateway, "a", 7)
        assertEquals(7, RoomSeenStore(disk).seen(gateway).first()["a"])
    }

    @Test
    fun aRoomTurnsSessionIsKnownByItsTitleAndOnlyForAKnownRoom() {
        val rooms = setOf("room-86c8")
        assertEquals("room-86c8", roomOfTurnSession("Group: room-86c8", rooms))
        // A chat the user happened to title like that, or a room this phone doesn't know, isn't one.
        assertEquals(null, roomOfTurnSession("Group: weekend plans", rooms))
        assertEquals(null, roomOfTurnSession("room-86c8", rooms))
        assertEquals(null, roomOfTurnSession(null, rooms))
    }

    @Test
    fun onlyMembersLinesAfterTheMarkNotify() {
        val ops = RoomMember(memberId = "ops", profile = "ops", handle = "ops", displayName = "Ops")
        fun event(seq: Int, kind: String, member: String? = null, text: String? = null) = RoomEvent(
            roomId = "a", seq = seq, eventId = "e:$seq", kind = kind, actor = RoomActor("gateway", "gw"),
            payload = buildJsonObject {
                member?.let { put("member_id", it) }
                text?.let { put("text", it) }
            },
            createdAt = 100.0 + seq,
        )
        val lines = roomNewLines(
            listOf(
                event(3, "message.member", "ops", "old"),
                event(4, "message.user", text = "from my laptop"),
                event(5, "turn.settled", "ops"),
                event(6, "message.member", "ops", "  new  "),
                event(7, "message.member", "gone", "from someone who left"),
                event(8, "message.member", "ops", " "),
            ),
            listOf(ops),
            afterSeq = 3,
        )
        assertEquals(listOf("new", "from someone who left"), lines.map { it.text })
        assertEquals(ops, lines[0].member)
        assertEquals(null, lines[1].member)
        assertEquals(106.0, lines[0].createdAt)
    }
}
