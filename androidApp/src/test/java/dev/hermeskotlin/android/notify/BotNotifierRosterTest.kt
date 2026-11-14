package dev.hermeskotlin.android.notify

import dev.hermeskotlin.android.widget.WidgetTestApp
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The on-disk roster is also the cold widgets' only bot-chat existence evidence. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class BotNotifierRosterTest {
    @Test
    fun savedRosterKeepsCanonicalChatIdsWithoutSessionContents() {
        val saved = listOf(
            Bot(
                name = "researcher",
                canonicalSession = BotSession(
                    id = "bc-canonical",
                    resolvedId = "bc-open",
                    title = "Retitled bot chat",
                    preview = "private preview",
                    lastActive = 123.0,
                ),
                lastSession = BotSession(id = "other", preview = "other private preview"),
            ),
        ).forSavedRoster().single()

        assertEquals(BotSession(id = "bc-canonical", resolvedId = "bc-open"), saved.canonicalSession)
        assertNull(saved.canonicalSession?.title)
        assertNull(saved.canonicalSession?.preview)
        assertNull(saved.lastSession)
    }
}
