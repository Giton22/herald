package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.ui.chat.NowPlaying.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowPlayingTest {

    private val now = NowPlaying()
    private val paused = mutableListOf<String>()

    private fun start(name: String, group: Group) = now.started(name, group) { paused += name }

    @Test
    fun aScreenPlayerStartingPausesTheChatClip() {
        start("clip", Group.Chat)
        start("screen video", Group.Screens)
        assertEquals(listOf("clip"), paused)
        assertFalse(now.isPlaying("clip"))
        assertTrue(now.isPlaying("screen video"))
    }

    @Test
    fun theChatClipStartingPausesEveryScreenPlayer() {
        start("screen video", Group.Screens)
        start("screen audio", Group.Screens)
        start("clip", Group.Chat)
        assertEquals(listOf("screen video", "screen audio"), paused)
        assertTrue(now.isPlaying("clip"))
    }

    @Test
    fun screenPlayersLeaveEachOtherAlone() {
        start("screen video", Group.Screens)
        start("screen audio", Group.Screens)
        assertEquals(emptyList(), paused)
    }

    @Test
    fun theSamePlayerStartingAgainPausesNothing() {
        start("clip", Group.Chat)
        start("clip", Group.Chat)
        assertEquals(emptyList(), paused)
    }

    @Test
    fun aStoppedPlayerIsntPausedLater() {
        start("screen video", Group.Screens)
        now.stopped("screen video")
        start("clip", Group.Chat)
        assertEquals(emptyList(), paused)
    }

    @Test
    fun aPauseThatReportsBackDoesNoHarm() {
        now.started("screen video", Group.Screens) { now.stopped("screen video"); paused += "screen video" }
        start("clip", Group.Chat)
        assertEquals(listOf("screen video"), paused)
        assertTrue(now.isPlaying("clip"))
        assertFalse(now.isPlaying("screen video"))
    }
}
