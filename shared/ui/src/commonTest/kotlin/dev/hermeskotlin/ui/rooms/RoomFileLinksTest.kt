package dev.hermeskotlin.ui.rooms

import androidx.compose.runtime.saveable.SaverScope
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.ui.chat.FileDownload
import dev.hermeskotlin.ui.chat.FileLoad
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RoomFileLinksTest {
    @Test
    fun aLineOpensItsGatewayFilesAsItsOwnBotAndLeavesWebLinksAlone() {
        val opened = mutableListOf<Pair<String?, GatewayFile>>()
        val open = { profile: String?, file: GatewayFile -> opened += profile to file }
        roomFileLink("file:///output/report.md", "scribe", open)
        roomFileLink("/output/data.csv#L3", "ops", open)
        roomFileLink("https://example.com/report.md", "scribe", open)
        roomFileLink("mailto:someone@example.com", "scribe", open)
        assertEquals(listOf<Pair<String?, GatewayFile>>("scribe" to GatewayFile("/output/report.md"), "ops" to GatewayFile("/output/data.csv", 3)), opened)
    }

    @Test
    fun aDesktopMemberReadsFilesOnlyAsABotOfThisGateway() {
        val bots = listOf(Bot("default", isDefault = true), Bot("scribe"))
        assertEquals("scribe", desktopFileOwner("scribe", bots))
        assertEquals("default", desktopFileOwner("default", bots))
        assertNull(desktopFileOwner("elsewhere", bots))
        assertNull(desktopFileOwner(null, bots))
        assertNull(desktopFileOwner("scribe", emptyList()))
    }

    @Test
    fun anOpenRoomFileSurvivesBeingSavedAndRestored() {
        val scope = SaverScope { true }
        val link = RoomFileLink(first, hosted, "scribe", GatewayFile("/output/data.csv", 12))
        val saved = with(RoomFileLink.Saver) { scope.save(link) }!!
        assertEquals(link, RoomFileLink.Saver.restore(saved))
        val noLine = RoomFileLink(first, desktop, null, file)
        assertEquals(noLine, RoomFileLink.Saver.restore(with(RoomFileLink.Saver) { scope.save(noLine) }!!))
        assertNull(with(RoomFileLink.Saver) { scope.save(null) })
    }

    @Test
    fun aFileShowsOnlyOverTheRoomItCameFrom() {
        val link = RoomFileLink(first, hosted, "scribe", file)
        assertEquals(link, link.shownIn(hosted, emptyList()))
        // Restored before its room is open again, or with another room open: nothing shows, nothing is read.
        assertNull(link.shownIn(null, emptyList()))
        assertNull(link.shownIn(RoomFileLink.roomKey("other", null), emptyList()))
        assertNull(link.shownIn(desktop, emptyList()))
        assertEquals(hosted, RoomFileLink.roomKey("r1", "id:r1"))
        assertNull(RoomFileLink.roomKey(null, null))
    }

    @Test
    fun aDesktopFileFindsItsOwnerOnceTheRosterHasIt() {
        val link = RoomFileLink(first, desktop, "scribe", file)
        // Tapped while the roster was still loading: no owner yet, so no request.
        assertNull(link.shownIn(desktop, emptyList())!!.profile)
        assertEquals("scribe", link.shownIn(desktop, listOf(Bot("scribe")))!!.profile)
        assertNull(link.shownIn(desktop, listOf(Bot("default")))!!.profile)
    }

    private val hosted = RoomFileLink.roomKey("r1", null)!!
    private val desktop = RoomFileLink.roomKey(null, "id:r1")!!
    private val file = GatewayFile("/output/report.md")
    private val first = GatewayUrl.parse("https://first.example")
    private val second = GatewayUrl.parse("https://second.example")
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun samePathUsesTheMessagesGatewayAndProfileForPreviewAndDownload() = runTest {
        val requests = mutableListOf<String>()
        val api = MediaApi(createHttpClient(MockEngine { request ->
            requests += "${request.url.host}${request.url.encodedPath}:${request.url.parameters["profile"]}:${request.url.parameters["path"]}"
            if (request.url.encodedPath.endsWith("read-text")) respond("""{"text":"report"}""", headers = json)
            else respond("report")
        }))
        val targets = listOf(RoomFileLink(first, hosted, "a", file), RoomFileLink(first, hosted, "b", file), RoomFileLink(second, hosted, "a", file))
        targets.forEach { target ->
            assertEquals("report", assertIs<FileLoad.Ready>(target.preview(api)).text.text)
            assertEquals("report", assertIs<FileDownload.Ready>(target.download(api)).bytes.decodeToString())
        }
        assertEquals(listOf(
            "first.example/api/fs/read-text:a:/output/report.md", "first.example/api/fs/download:a:/output/report.md",
            "first.example/api/fs/read-text:b:/output/report.md", "first.example/api/fs/download:b:/output/report.md",
            "second.example/api/fs/read-text:a:/output/report.md", "second.example/api/fs/download:a:/output/report.md",
        ), requests)
        targets.first().preview(api, GatewayFile("/output/linked.csv"))
        assertEquals("first.example/api/fs/read-text:a:/output/linked.csv", requests.last())
        targets.first().download(api, GatewayFile("/output/linked.csv"))
        assertEquals("first.example/api/fs/download:a:/output/linked.csv", requests.last())
    }

    @Test
    fun missingOwnerNeverFallsBackToTheHostOrAnotherProfile() = runTest {
        var requests = 0
        val api = MediaApi(createHttpClient(MockEngine { requests++; respond("wrong file") }))
        listOf(null, "", " ").forEach { profile ->
            val target = RoomFileLink(first, hosted, profile, file)
            assertIs<FileLoad.Failed>(target.preview(api))
            assertIs<FileDownload.Failed>(target.download(api))
        }
        assertEquals(0, requests)
    }

    @Test
    fun gatewayDenialKeepsTheExistingErrorAndDoesNotRetryElsewhere() = runTest {
        val paths = mutableListOf<String>()
        val api = MediaApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"detail":"Sensitive file"}""", HttpStatusCode.Forbidden, json)
        }))
        val target = RoomFileLink(first, hosted, "a", file)
        assertEquals("The gateway keeps files like this one private.", assertIs<FileLoad.Failed>(target.preview(api)).message)
        assertEquals("The gateway keeps files like this one private.", assertIs<FileDownload.Failed>(target.download(api)).message)
        assertEquals(listOf("/api/fs/read-text", "/api/fs/download"), paths)
    }
}
