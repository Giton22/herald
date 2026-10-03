package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    private val release = """{"tag_name":"v0.3.0","html_url":"https://github.com/o/r/releases/tag/v0.3.0",
        "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                  {"name":"herald-0.3.0.apk","browser_download_url":"https://x/app.apk"}]}"""

    private fun checker(status: HttpStatusCode = HttpStatusCode.OK, body: String = release) = UpdateChecker(
        createHttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
        InMemoryKeyValueStore(),
    )

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(isNewer("0.10.0", "0.9.3"))
        assertTrue(isNewer("v1.0", "0.9.9"))
        assertFalse(isNewer("0.2.0", "0.2.0"))
        assertFalse(isNewer("0.2.0-beta", "0.2.0"))
        assertFalse(isNewer("nightly", "0.2.0"))
    }

    @Test
    fun aReleaseNamesItsApk() {
        val update = parseRelease(HermesJson.parseToJsonElement(release) as JsonObject)!!
        assertEquals("0.3.0", update.version)
        assertEquals("https://x/app.apk", update.apkUrl)
    }

    @Test
    fun aNewerReleaseIsOfferedUntilDismissed() = runTest {
        val checker = checker()
        assertEquals("0.3.0", checker.check("o/r", "0.2.0")?.version)
        checker.dismiss("0.3.0")
        assertNull(checker.check("o/r", "0.2.0"))
    }

    @Test
    fun nothingOnTheSameVersionOrAFailure() = runTest {
        assertNull(checker().check("o/r", "0.3.0"))
        assertNull(checker(HttpStatusCode.NotFound, """{"message":"Not Found"}""").check("o/r", "0.1.0"))
        assertNull(checker().check("", "0.1.0"))
    }
}
