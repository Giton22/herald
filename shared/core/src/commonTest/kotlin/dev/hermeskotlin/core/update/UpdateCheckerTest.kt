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
        assertEquals("https://x/app.apk", update.downloadUrl)
    }

    @Test
    fun aDesktopTakesItsOwnInstallerAndNeverAnApk() {
        val release = HermesJson.parseToJsonElement(
            """{"tag_name":"v1.2.0","html_url":"https://github.com/o/r/releases/tag/v1.2.0","assets":[""" +
                listOf("kyllene-1.2.0.apk", "kyllene-desktop-1.2.0.dmg", "kyllene-desktop-1.2.0.msi", "kyllene-desktop-1.2.0.msi.sha256", "kyllene-desktop-1.2.0_amd64.deb")
                    .joinToString(",") { """{"name":"$it","browser_download_url":"https://x/$it"}""" } + "]}",
        ) as JsonObject

        assertEquals("https://x/kyllene-desktop-1.2.0.msi", parseRelease(release, emptyList(), installer = ".msi")?.downloadUrl)
        assertEquals("https://x/kyllene-desktop-1.2.0.dmg", parseRelease(release, emptyList(), installer = ".dmg")?.downloadUrl)
        assertEquals("https://x/kyllene-desktop-1.2.0_amd64.deb", parseRelease(release, emptyList(), installer = ".deb")?.downloadUrl)
        // A release without this desktop's installer: the release page.
        assertNull(parseRelease(release, emptyList(), installer = ".rpm")?.downloadUrl)
        // And a phone never takes a desktop installer.
        assertEquals("https://x/kyllene-1.2.0.apk", parseRelease(release, listOf("arm64-v8a"))?.downloadUrl)
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

        assertEquals("https://x/arm64.apk", parseRelease(split, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))?.downloadUrl)
        assertEquals("https://x/arm32.apk", parseRelease(split, listOf("armeabi-v7a", "armeabi"))?.downloadUrl)
        assertEquals("https://x/x86_64.apk", parseRelease(split, listOf("x86_64", "arm64-v8a"))?.downloadUrl)
        // No APK of its own (x86 here), or no ABI known: the universal one.
        assertEquals("https://x/universal.apk", parseRelease(split, listOf("x86"))?.downloadUrl)
        assertEquals("https://x/universal.apk", parseRelease(split, emptyList())?.downloadUrl)
    }

    @Test
    fun theWatchAppsApkIsNeverOffered() {
        // It sorts after every phone APK, so versions up to 0.5.1 still take the universal one first.
        val names = listOf("herald-0.9.0.apk", "herald-0.9.0_arm64-v8a.apk", "herald-wear-0.9.0.apk")
        assertEquals(names, names.sorted())
        fun release(vararg assets: Pair<String, String>) = HermesJson.parseToJsonElement(
            """{"tag_name":"v0.9.0","html_url":"https://github.com/o/r/releases/tag/v0.9.0","assets":[""" +
                assets.joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"https://x/$url"}""" } + "]}",
        ) as JsonObject

        val full = release("herald-0.9.0.apk" to "universal.apk", "herald-0.9.0_arm64-v8a.apk" to "arm64.apk", "herald-wear-0.9.0.apk" to "wear.apk")
        assertEquals("https://x/universal.apk", parseRelease(full, listOf("x86"))?.downloadUrl)
        assertEquals("https://x/arm64.apk", parseRelease(full, listOf("arm64-v8a"))?.downloadUrl)
        // Only the watch's APK: the release page, not an APK the phone can't use.
        val wearOnly = release("herald-wear-0.9.0.apk" to "wear.apk")
        assertEquals(null, parseRelease(wearOnly, listOf("arm64-v8a"))?.downloadUrl)

        // Under another app name too, even listed before the phone's APKs.
        val renamed = release("kyllene-wear-0.9.0.apk" to "wear.apk", "kyllene-0.9.0.apk" to "universal.apk", "kyllene-0.9.0_arm64-v8a.apk" to "arm64.apk")
        assertEquals("https://x/universal.apk", parseRelease(renamed, listOf("x86"))?.downloadUrl)
        assertEquals("https://x/arm64.apk", parseRelease(renamed, listOf("arm64-v8a"))?.downloadUrl)
        assertEquals(null, parseRelease(release("Kyllene-Wear-0.9.0.apk" to "wear.apk"), listOf("arm64-v8a"))?.downloadUrl)
        assertEquals(null, parseRelease(release("kyllene-dev-wear-0.9.0.apk" to "wear.apk"), listOf("arm64-v8a"))?.downloadUrl)

        // Nor the Pebble watchapp, listed before the phone's APKs.
        val pebble = release("herald-0.9.0.pbw" to "pebble.pbw", "herald-0.9.0.apk" to "universal.apk")
        assertEquals("https://x/universal.apk", parseRelease(pebble, listOf("x86"))?.downloadUrl)
        assertEquals(null, parseRelease(release("herald-pebble-0.9.0.pbw" to "pebble.pbw"), listOf("arm64-v8a"))?.downloadUrl)
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

    @Test
    fun aRenamedRepoIsFollowedToItsNewAddress() = runTest {
        // GitHub answers a renamed repo's old name with a redirect to the repo's id.
        fun redirecting(location: String) = UpdateChecker(
            createHttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath.startsWith("/repos/")) {
                        respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, location))
                    } else {
                        respond(release, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ),
            InMemoryKeyValueStore(),
            abis = listOf("arm64-v8a"),
        )
        assertEquals("0.3.0", redirecting("https://api.github.com/repositories/1/releases/latest").check("o/r", "0.2.0")?.version)
        // Only to GitHub's API, and not round in circles.
        assertNull(redirecting("https://example.com/repositories/1/releases/latest").check("o/r", "0.2.0"))
        assertNull(redirecting("https://api.github.com/repos/o/r/releases/latest").check("o/r", "0.2.0"))
    }
}
