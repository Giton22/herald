package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPlaybackTest {

    @Test
    fun timesReadAsAClockDoes() {
        assertEquals("0:00", formatPlayTime(0))
        assertEquals("0:00", formatPlayTime(-500))
        assertEquals("0:07", formatPlayTime(7_999))
        assertEquals("12:34", formatPlayTime((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatPlayTime((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun theRowShowsTheLengthOnceItIsKnown() {
        assertEquals("0:12 / 3:45", playTimeLabel(12_000, 225_000))
        assertEquals("0:12", playTimeLabel(12_000, null))
        assertEquals("0:12", playTimeLabel(12_000, 0))
    }

    @Test
    fun aSeekLandsInsideTheClip() {
        assertEquals(50_000L, seekPosition(0.5f, 100_000))
        assertEquals(0L, seekPosition(-0.2f, 100_000))
        assertEquals(100_000L, seekPosition(1.4f, 100_000))
        assertNull(seekPosition(0.5f, null))
    }

    @Test
    fun speedsGoRoundAndSayThemselves() {
        assertEquals(1.5f, nextSpeed(1f))
        assertEquals(2f, nextSpeed(1.5f))
        assertEquals(1f, nextSpeed(2f))
        // A speed from elsewhere starts the round again.
        assertEquals(1f, nextSpeed(0.75f))
        assertEquals("1x", speedLabel(1f))
        assertEquals("1.5x", speedLabel(1.5f))
    }

    @Test
    fun aClipShowsOnlyItsOwnState() {
        val playing = PlaybackState(key = "a", phase = PlaybackPhase.Playing, positionMs = 3_000)
        assertEquals(playing, playing.of("a"))
        assertEquals(PlaybackState(), playing.of("b"))
    }

    @Test
    fun oneClipAtATime() {
        val slot = PlayerSlot()
        assertEquals(PlayerSlot.Claim.Load, slot.claim("a"))
        assertEquals(PlayerSlot.Claim.Resume, slot.claim("a"))
        // Another clip takes the player over; calls about the first one no longer reach it.
        assertEquals(PlayerSlot.Claim.Load, slot.claim("b"))
        assertFalse(slot.holds("a"))
        assertTrue(slot.holds("b"))
        slot.clear()
        assertFalse(slot.holds("b"))
        assertEquals(PlayerSlot.Claim.Load, slot.claim("b"))
    }

    @Test
    fun failuresAreSaidInTheFileViewersWords() {
        assertEquals(FileProblem.SignInAgain, playbackProblem(ApiResult.SessionExpired))
        assertEquals(FileProblem.Said(MediaApi.TOO_LARGE), playbackProblem(ApiResult.Failed(413, MediaApi.TOO_LARGE)))
    }
}
