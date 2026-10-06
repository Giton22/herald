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
        abis = listOf("arm64-v8a"),
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
        val update = parseRelease(HermesJson.parseToJsonElement(release) as JsonObject, listOf("arm64-v8a"))!!
        assertEquals("0.3.0", update.version)
        assertEquals("https://x/app.apk", update.apkUrl)
    }

    @Test
    fun theApkForThisDevicesAbiWinsThenTheUniversalOne() {
        // In GitHub's order, by name. Versions up to 0.5.1 take the first APK, so the universal one has to sort
        // first: release.yml puts the ABI after "_", which comes after the universal name's ".".
        val names = listOf("herald-0.4.0.apk", "herald-0.4.0.apk.sha256", "herald-0.4.0_arm64-v8a.apk", "herald-0.4.0_armeabi-v7a.apk", "herald-0.4.0_x86_64.apk")
        assertEquals(names, names.sorted())
        val urls = listOf("universal.apk", "universal.sha256", "arm64.apk", "arm32.apk", "x86_64.apk")
        val split = HermesJson.parseToJsonElement(
            """{"tag_name":"v0.4.0","html_url":"https://github.com/o/r/releases/tag/v0.4.0","assets":[""" +
                names.zip(urls).joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"https://x/$url"}""" } + "]}",
        ) as JsonObject

        assertEquals("https://x/arm64.apk", parseRelease(split, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))?.apkUrl)
        assertEquals("https://x/arm32.apk", parseRelease(split, listOf("armeabi-v7a", "armeabi"))?.apkUrl)
        assertEquals("https://x/x86_64.apk", parseRelease(split, listOf("x86_64", "arm64-v8a"))?.apkUrl)
        // No APK of its own (x86 here), or no ABI known: the universal one.
        assertEquals("https://x/universal.apk", parseRelease(split, listOf("x86"))?.apkUrl)
        assertEquals("https://x/universal.apk", parseRelease(split, emptyList())?.apkUrl)
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
