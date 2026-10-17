package dev.hermeskotlin.ui.rooms

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

class RoomFileLinksTest {
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
        val targets = listOf(RoomFileLink(first, "a", file), RoomFileLink(first, "b", file), RoomFileLink(second, "a", file))
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
            val target = RoomFileLink(first, profile, file)
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
        val target = RoomFileLink(first, "a", file)
        assertEquals("The gateway keeps files like this one private.", assertIs<FileLoad.Failed>(target.preview(api)).message)
        assertEquals("The gateway keeps files like this one private.", assertIs<FileDownload.Failed>(target.download(api)).message)
        assertEquals(listOf("/api/fs/read-text", "/api/fs/download"), paths)
    }
}
