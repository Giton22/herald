package dev.hermeskotlin.ui.rooms

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.rooms.DesktopLine
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.ui.bots.BotFaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a Desktop room's mirrored copy is drawn: its row in the roster and the lines of its transcript. */
class DesktopRoomRowsTest {

    private val faces = BotFaces(listOf(Bot(name = "default", displayName = "Main"), Bot(name = "side")))

    private fun room(vararg lines: DesktopLine) = DesktopRoom(key = "id:r", name = "R", lines = lines.toList())

    private fun member(speaker: String, text: String, id: String? = null) = DesktopLine(id = id, fromUser = false, speaker = speaker, text = text)

    @Test
    fun subtitleNamesTheMemberByItsBotsLabelNotItsProfile() {
        // The mirror names the default bot "default"; the roster calls it "Main".
        assertEquals("Main: Hey there", room(member("default", "Hey\n  there")).rowSubtitle(faces))
        assertEquals("Side: ok", room(member("side", "ok")).rowSubtitle(faces))
        assertEquals("You: hi", room(DesktopLine(fromUser = true, speaker = "You", text = "hi")).rowSubtitle(faces))
        assertEquals("Continue on Desktop", room().rowSubtitle(faces))
    }

    @Test
    fun subtitleSaysWhyTheDotIsThereWhenTheRoomAsksForTheUser() {
        assertEquals("Needs you · Main: @user take a look", room(member("default", "@user take a look")).rowSubtitle(faces))
    }

    @Test
    fun transcriptLinesKeyByMessageIdSoTheSlidingWindowKeepsThem() {
        val before = room(member("default", "one", id = "m1"), member("side", "two", id = "m2")).roomLines()
        val after = room(member("side", "two", id = "m2"), member("default", "three", id = "m3")).roomLines()
        // "two" moved from the second place to the first; its key stays its own.
        assertEquals("m2", before[1].eventId)
        assertEquals("m2", after[0].eventId)
    }

    @Test
    fun transcriptKeysStayUniqueForLegacyAndRepeatedIds() {
        val lines = room(member("a", "x"), member("a", "y", id = "m1"), member("a", "z", id = "m1")).roomLines()
        assertEquals(listOf("desktop:0", "m1", "desktop:2"), lines.map { it.eventId })
    }

    @Test
    fun transcriptLeavesTheNameToTheBotsLabel() {
        val line = room(member("default", "hey")).roomLines().single()
        // No speaker of its own: the member line draws the bot's label ("Main"), found by profile.
        assertNull(line.speaker)
        assertEquals("default", line.profile)
        assertEquals("Main", faces.roomBot(line.profile).label)
    }
}
