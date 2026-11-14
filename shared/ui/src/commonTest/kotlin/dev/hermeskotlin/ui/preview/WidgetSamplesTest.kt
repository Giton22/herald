package dev.hermeskotlin.ui.preview

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T15's decode half (`docs/WIDGETS-SPEC.md` §11): every sample widget snapshot round-trips through
 * the same lenient JSON the store writes, so a stored snapshot decodes across additive changes.
 * (The render half lives in `widget/WidgetPreviewsTest`, the Robolectric harness.)
 */
class WidgetSamplesTest {

    private val json = Json(HermesJson) { coerceInputValues = true }

    @Test
    fun everySampleDecodes() {
        assertEquals(PageSamples.nowNeedsYou(), roundTrip(PageSamples.nowNeedsYou(), NowWidgetSnapshot.serializer()))
        assertEquals(PageSamples.nowRunning(), roundTrip(PageSamples.nowRunning(), NowWidgetSnapshot.serializer()))
        assertEquals(PageSamples.nowQuiet(), roundTrip(PageSamples.nowQuiet(), NowWidgetSnapshot.serializer()))
        assertEquals(PageSamples.chatsWidget(), roundTrip(PageSamples.chatsWidget(), ChatsWidgetSnapshot.serializer()))
        assertEquals(PageSamples.kanbanWidget(), roundTrip(PageSamples.kanbanWidget(), KanbanWidgetSnapshot.serializer()))
        assertEquals(PageSamples.scheduledWidget(), roundTrip(PageSamples.scheduledWidget(), ScheduledWidgetSnapshot.serializer()))
    }

    @Test
    fun anOlderSnapshotMissingTheNewerFieldsStillDecodes() {
        // Additive changes must not break stored snapshots: a Now without `carried`/`socketSavedAt` reads.
        val older = """{"gatewayUrl":"https://gw.example.ts.net","profile":null,"savedAtEpochSeconds":100}"""
        val decoded = json.decodeFromString(NowWidgetSnapshot.serializer(), older)
        assertEquals(emptyList(), decoded.needsYou)
        assertEquals(null, decoded.socketSavedAtEpochSeconds)
    }

    private fun <T> roundTrip(value: T, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, value))
}
