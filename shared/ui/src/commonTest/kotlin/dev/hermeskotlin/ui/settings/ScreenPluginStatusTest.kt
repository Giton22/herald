package dev.hermeskotlin.ui.settings

import kotlinx.serialization.json.Json
import kotlin.test.*

class ScreenPluginStatusTest {
    private fun status(json: String) = screenPluginStatus(Json.parseToJsonElement(json))

    @Test fun recognizesOnlyOfficialScreen() {
        assertEquals(ScreenPluginStatus.Enabled, status("""{"plugins":[{"name":"herald-screen","status":"enabled"}]}"""))
        assertEquals(ScreenPluginStatus.Disabled, status("""{"plugins":[{"name":"herald-screen","status":"disabled"}]}"""))
        assertEquals(ScreenPluginStatus.Missing, status("""{"plugins":[{"name":"herald-screen-test","status":"enabled"}]}"""))
        assertEquals(ScreenPluginStatus.Missing, status("""{"plugins":[{"name":"herald-screen-test","status":"disabled"}]}"""))
        assertEquals(ScreenPluginStatus.Missing, status("""{"plugins":[{"name":"herald-screen-testing","status":"enabled"}]}"""))
        assertEquals(ScreenPluginStatus.Disabled, status("""{"plugins":[{"name":"herald-screen","status":"disabled"},{"name":"herald-screen-test","status":"enabled"}]}"""))
        assertEquals(ScreenPluginStatus.Enabled, status("""{"plugins":[{"name":"herald-screen","status":"enabled"},{"name":"herald-screen-test","status":"disabled"}]}"""))
    }

    @Test fun malformedAndAmbiguousResultsAreUnknown() {
        for (json in listOf("{}", "{\"plugins\":null}", "{\"plugins\":[{}]}", """{"plugins":[{"name":"herald-screen"}]}""", """{"plugins":[{"name":"herald-screen","status":"loaded"}]}""", """{"plugins":[{"name":"herald-screen","status":"enabled"},{"name":"herald-screen","status":"disabled"}]}"""))
            assertEquals(ScreenPluginStatus.Unknown, status(json), json)
        assertEquals(ScreenPluginStatus.Missing, status("""{"plugins":[]}"""))
        assertEquals(ScreenPluginStatus.Disabled, status("""{"plugins":[{"name":"herald-screen","status":"not enabled"}]}"""))
        for (value in listOf("unavailable", "loaded", "unknown"))
            assertEquals(ScreenPluginStatus.Unknown, status("""{"plugins":[{"name":"herald-screen","status":"$value"}]}"""))
    }

    @Test fun canAlwaysTurnOffButOnlyEnableWhenListedEnabled() {
        for (state in ScreenPluginStatus.entries) {
            assertTrue(canChangeScreenClipboard(true, state))
            assertEquals(state == ScreenPluginStatus.Enabled, canChangeScreenClipboard(false, state))
        }
    }
}
