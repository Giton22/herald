package dev.hermeskotlin.android.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The D26 fallback chain: fresh snapshot wins, then the stored one with its notice, else error. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetRefresherDecideTest {

    private data class FakeSnapshot(val at: Long)

    @Test
    fun freshSnapshotWinsOverStored() {
        val fresh = FakeSnapshot(2)
        val stored = FakeSnapshot(1)
        var noticed = false
        val outcome = WidgetRefresher.decide(fresh, stored) { noticed = true; "notice" }
        assertEquals(WidgetViews.UiState.Content(fresh), outcome)
        assertEquals(false, noticed)
    }

    @Test
    fun storedSnapshotFallsBackWithNotice() {
        val stored = FakeSnapshot(1)
        val outcome = WidgetRefresher.decide(null, stored) { "Saved notice for ${it.at}" }
        assertEquals(WidgetViews.UiState.Content(stored, "Saved notice for 1"), outcome)
    }

    @Test
    fun neitherFreshNorStoredIsError() {
        assertEquals(WidgetViews.UiState.Error, WidgetRefresher.decide<FakeSnapshot>(null, null) { "unused" })
    }

    @Test
    fun errorAndSignedOutAreDistinctStates() {
        assertTrue(WidgetViews.UiState.Error != WidgetViews.UiState.SignedOut)
        assertTrue(WidgetViews.UiState.Loading != WidgetViews.UiState.SignedOut)
    }
}
