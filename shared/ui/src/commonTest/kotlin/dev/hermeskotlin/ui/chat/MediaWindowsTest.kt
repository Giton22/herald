package dev.hermeskotlin.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.hermeskotlin.core.media.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MediaWindowsTest {

    private val song = PlayableMedia(key = "song", source = "song.mp3", name = "song.mp3", type = MediaType.Audio)
    private val film = PlayableMedia(key = "film", source = "film.mp4", name = "film.mp4", type = MediaType.Video)

    private fun held(media: PlayableMedia, phase: PlaybackPhase) = PlaybackState(key = media.key, media = media, phase = phase)

    @Test
    fun theBarStandsInForAnAudioRowOutOfSight() {
        assertTrue(miniPlayerShows(held(song, PlaybackPhase.Playing), rowInView = false))
        assertTrue(miniPlayerShows(held(song, PlaybackPhase.Paused), rowInView = false))
        assertTrue(miniPlayerShows(held(song, PlaybackPhase.Loading), rowInView = false))
        // At the end it stays, offering to play again.
        assertTrue(miniPlayerShows(held(song, PlaybackPhase.Ended), rowInView = false))
        // Never two controls on screen for one clip.
        assertFalse(miniPlayerShows(held(song, PlaybackPhase.Playing), rowInView = true))
    }

    @Test
    fun theBarKeepsAwayFromVideoFailuresAndAnEmptyPlayer() {
        assertFalse(miniPlayerShows(held(film, PlaybackPhase.Playing), rowInView = false))
        assertFalse(miniPlayerShows(held(song, PlaybackPhase.Failed), rowInView = false))
        assertFalse(miniPlayerShows(PlaybackState(), rowInView = false))
    }

    @Test
    fun aRowCountsAsSeenWhileHalfOfItShows() {
        val list = Rect(0f, 100f, 400f, 900f)
        assertTrue(rowInView(Rect(0f, 300f, 400f, 380f), list))
        assertTrue(rowInView(Rect(0f, 860f, 400f, 940f), list))
        assertFalse(rowInView(Rect(0f, 870f, 400f, 950f), list))
        assertFalse(rowInView(Rect(0f, 20f, 400f, 100f), list))
        assertFalse(rowInView(Rect(0f, 1000f, 400f, 1080f), list))
        assertFalse(rowInView(Rect(0f, 300f, 400f, 300f), list))
    }

    @Test
    fun rowsReportWhereTheyAreAndForgetWhenGone() {
        val rows = ClipRows()
        val row = Any()
        assertFalse(rows.inView("song"))
        rows.report(row, "song", "m1", Rect(0f, 200f, 400f, 280f))
        // Not known to be in sight until the list says where it shows.
        assertFalse(rows.inView("song"))
        rows.viewport = Rect(0f, 0f, 400f, 800f)
        assertTrue(rows.inView("song"))
        assertFalse(rows.inView("other"))
        rows.report(row, "song", "m1", Rect(0f, 900f, 400f, 980f))
        assertFalse(rows.inView("song"))
        rows.report(row, "song", "m1", Rect(0f, 200f, 400f, 280f))
        rows.forget(row)
        assertFalse(rows.inView("song"))
        assertNull(rows.boundsOf("song"))
    }

    @Test
    fun theBarGoesBackToTheMessageItLastSawTheClipIn() {
        val rows = ClipRows()
        assertFalse(rows.show("song"))
        assertNull(rows.goTo)
        rows.report(Any(), "song", "m7", Rect(0f, 0f, 10f, 10f))
        assertTrue(rows.show("song"))
        assertEquals("song", rows.goTo)
        assertEquals("m7", rows.messageOf("song"))
    }

    @Test
    fun theWindowSitsInEachCornerInsideTheMargin() {
        val area = Rect(0f, 0f, 1000f, 2000f)
        val window = Size(450f, 253f)
        assertEquals(Offset(24f, 24f), cornerOffset(PopupCorner.TopStart, area, window, 24f))
        assertEquals(Offset(526f, 24f), cornerOffset(PopupCorner.TopEnd, area, window, 24f))
        assertEquals(Offset(24f, 1723f), cornerOffset(PopupCorner.BottomStart, area, window, 24f))
        assertEquals(Offset(526f, 1723f), cornerOffset(PopupCorner.BottomEnd, area, window, 24f))
    }

    @Test
    fun aShortAreaKeepsTheWindowUnderTheTopBar() {
        // The keyboard up on a small phone leaves less room than the window needs.
        val area = Rect(0f, 0f, 1000f, 200f)
        val window = Size(450f, 253f)
        assertEquals(24f, cornerOffset(PopupCorner.BottomEnd, area, window, 24f).y)
        assertEquals(24f, cornerOffset(PopupCorner.TopStart, area, window, 24f).y)
    }

    @Test
    fun aDroppedWindowSettlesInTheNearestCorner() {
        val area = Rect(0f, 0f, 1000f, 2000f)
        val window = Size(400f, 200f)
        assertEquals(PopupCorner.TopStart, nearestCorner(Offset(10f, 10f), area, window))
        assertEquals(PopupCorner.TopEnd, nearestCorner(Offset(400f, 700f), area, window))
        assertEquals(PopupCorner.BottomStart, nearestCorner(Offset(50f, 1000f), area, window))
        assertEquals(PopupCorner.BottomEnd, nearestCorner(Offset(700f, 1700f), area, window))
    }

    @Test
    fun theVideoMovesBetweenTheWindowAndFullScreen() {
        val popup = ShownVideo(film, VideoMode.Popup)
        val full = popup.movedTo(VideoMode.Full)
        assertEquals(ShownVideo(film, VideoMode.Full), full)
        assertEquals(popup, full.movedTo(VideoMode.Popup))
        // Closed, there's nothing to move.
        assertNull((null as ShownVideo?).movedTo(VideoMode.Full))
    }

    @Test
    fun anotherClipTakesTheVideosPlace() {
        val full = ShownVideo(film, VideoMode.Full)
        // Playing on (from the card or the window) keeps it where it is.
        assertEquals(full, full.afterPlay("film"))
        // One player: audio starting stops the video, and another video replaces it.
        assertNull(full.afterPlay("song"))
        assertNull(full.afterPlay("other-film"))
        assertNull((null as ShownVideo?).afterPlay("film"))
    }

    @Test
    fun thePictureGoesToTheViewForTheModeShown() {
        val surfaces = SurfaceOwners<String>()
        assertNull(surfaces.owner(VideoMode.Popup))
        val window = "window"
        val full = "full"
        surfaces.add(window, VideoMode.Popup)
        assertSame(window, surfaces.owner(VideoMode.Popup))
        surfaces.add(full, VideoMode.Full)
        assertSame(full, surfaces.owner(VideoMode.Full))
        // The window kept under full screen takes the picture back only once the video is in it again.
        assertSame(window, surfaces.owner(VideoMode.Popup))
        // A rebuilt screen laying the window out after full screen doesn't steal it.
        val window2 = "window2"
        surfaces.remove(window)
        surfaces.add(window2, VideoMode.Popup)
        assertSame(full, surfaces.owner(VideoMode.Full))
        surfaces.remove(full)
        assertSame(window2, surfaces.owner(VideoMode.Full))
        surfaces.add(window2, VideoMode.Popup)
        assertEquals(listOf(window2), surfaces.all())
    }
}
