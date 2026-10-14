package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaApiFilesTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun readTextAsksForThePathInTheProfile() = runTest {
        var asked: String? = null
        val api = MediaApi(createHttpClient(MockEngine { request ->
            asked = "${request.url.encodedPath}?${request.url.encodedQuery}"
            respond(
                """{"binary":false,"byteSize":900000,"language":"markdown","mimeType":"text/markdown","path":"/opt/a b.md","text":"# Hi","truncated":true}""",
                HttpStatusCode.OK, json,
            )
        }))

        val text = assertIs<ApiResult.Success<FileText>>(api.readText(url, "/opt/a b.md", "rin")).value

        assertEquals("/api/fs/read-text?path=%2Fopt%2Fa+b.md&profile=rin", asked)
        assertEquals(FileText("# Hi", 900_000, truncated = true, binary = false, language = "markdown"), text)
    }

    @Test
    fun plainTextHasNoLanguage() = runTest {
        val api = MediaApi(createHttpClient(MockEngine {
            respond("""{"binary":false,"byteSize":2,"language":"text","text":"hi","truncated":false}""", HttpStatusCode.OK, json)
        }))
        assertEquals(null, assertIs<ApiResult.Success<FileText>>(api.readText(url, "/a.txt")).value.language)
    }

    @Test
    fun aMissingFileIsReportedNotRetriedElsewhere() = runTest {
        val paths = mutableListOf<String>()
        val api = MediaApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"detail":"File not found"}""", HttpStatusCode.NotFound, json)
        }))

        val result = assertIs<ApiResult.Failed>(api.download(url, "/gone.txt"))

        assertEquals(404, result.status)
        assertEquals(listOf("/api/fs/download"), paths)
    }

    @Test
    fun aGatewayWithoutFsDownloadFallsBackToManagedFiles() = runTest {
        val paths = mutableListOf<String>()
        val api = MediaApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/api/fs/download") {
                respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
            } else {
                respond("bytes", HttpStatusCode.OK)
            }
        }))

        val bytes = assertIs<ApiResult.Success<ByteArray>>(api.download(url, "/a.txt")).value

        assertEquals("bytes", bytes.decodeToString())
        assertEquals(listOf("/api/fs/download", "/api/files/download"), paths)
    }

    @Test
    fun aProfilesFileIsntFetchedFromTheHostInstead() = runTest {
        val paths = mutableListOf<String>()
        val api = MediaApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
        }))

        val result = assertIs<ApiResult.Failed>(api.download(url, "/a.txt", "rin"))

        assertEquals(MediaApi.NO_PROFILE_DOWNLOAD, result.message)
        assertEquals(listOf("/api/fs/download"), paths)
    }

    @Test
    fun aFileOverTheCapIsRefusedWhileItStreams() = runTest {
        val api = MediaApi(createHttpClient(MockEngine { respond("x".repeat(2_000), HttpStatusCode.OK) }), maxDownloadBytes = 1_000)

        val result = assertIs<ApiResult.Failed>(api.download(url, "/big.bin"))

        assertEquals(413, result.status)
        assertEquals(MediaApi.TOO_LARGE, result.message)
    }

    @Test
    fun aFileAtTheCapComesWhole() = runTest {
        val api = MediaApi(createHttpClient(MockEngine { respond("x".repeat(1_000), HttpStatusCode.OK) }), maxDownloadBytes = 1_000)
        assertEquals(1_000, assertIs<ApiResult.Success<ByteArray>>(api.download(url, "/ok.bin")).value.size)
    }
}
