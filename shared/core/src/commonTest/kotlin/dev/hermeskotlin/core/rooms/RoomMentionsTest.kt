package dev.hermeskotlin.core.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomMentionsTest {

    private val main = RoomMember(memberId = "default", profile = "default", handle = "default", displayName = "Main")
    private val side = RoomMember(memberId = "side", profile = "side", handle = "side", displayName = "Side")
    private val ops = RoomMember(memberId = "ops-bot", profile = "ops-bot", handle = "ops-bot", displayName = "Ops Bot")
    private val members = listOf(main, side, ops)

    private fun to(text: String) = roomRecipients(text, members).map { it.handle }

    @Test
    fun aMessageThatNamesNoOneAsksEveryone() {
        assertEquals(listOf("default", "side", "ops-bot"), to("what do you all think?"))
    }

    @Test
    fun aNamedMemberIsAskedAlone() {
        assertEquals(listOf("side"), to("@side what do you think?"))
    }

    @Test
    fun twoNamesAskBothInRosterOrder() {
        assertEquals(listOf("default", "ops-bot"), to("@ops-bot and @default, compare notes"))
    }

    @Test
    fun allAndEveryoneAskEveryoneEvenBesideAName() {
        assertEquals(3, to("@all please").size)
        assertEquals(3, to("@side and @everyone").size)
    }

    @Test
    fun handlesMatchWhateverTheCase() {
        assertEquals(listOf("side"), to("@SIDE hi"))
    }

    @Test
    fun anUnknownHandleCountsForNothing() {
        // The gateway ignores it, so the message goes to everyone.
        assertEquals(3, to("@hermes are you there").size)
        assertEquals(listOf("side"), to("@hermes and @side"))
    }

    @Test
    fun thePickerMatchesHandlesAndNameWordsHandleStartsFirst() {
        assertEquals(listOf("side"), members.mentionable("si").map { it.handle })
        // "Bot" starts a word of "Ops Bot"; "ops" starts the handle.
        assertEquals(listOf("ops-bot"), members.mentionable("bot").map { it.handle })
        assertEquals(3, members.mentionable("").size)
        assertTrue(mentionsEveryone("ev"))
        assertTrue(mentionsEveryone("a"))
        assertFalse(mentionsEveryone("si"))
    }
}
