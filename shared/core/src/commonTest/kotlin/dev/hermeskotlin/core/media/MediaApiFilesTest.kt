package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
    fun aNewFolderIsAskedForByPath() = runTest {
        var asked: String? = null
        val api = MediaApi(createHttpClient(MockEngine { request ->
            asked = "${request.method.value} ${request.url.encodedPath} ${request.body.toByteArray().decodeToString()}"
            respond("""{"ok":true,"path":"/home/me/projects/site"}""", HttpStatusCode.OK, json)
        }))

        assertIs<ApiResult.Success<Unit>>(api.makeFolder(url, "~/projects/site"))
        assertEquals("""POST /api/files/mkdir {"path":"~/projects/site"}""", asked)
    }

    @Test
    fun aFolderTheGatewayRefusesSaysWhy() = runTest {
        val api = MediaApi(createHttpClient(MockEngine {
            respond("""{"detail":"A file already exists at that path"}""", HttpStatusCode.Conflict, json)
        }))

        assertEquals("A file already exists at that path", assertIs<ApiResult.Failed>(api.makeFolder(url, "/srv/a")).message)
    }

    @Test
    fun aGatewayWithoutMkdirSaysItCantMakeFolders() = runTest {
        // An older dashboard's own 404, FastAPI's, a proxy's non-JSON page, and the GET-only catch-all's 405.
        val replies = listOf(
            Triple("""{"detail":"No such API endpoint: /api/files/mkdir"}""", HttpStatusCode.NotFound, json),
            Triple("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json),
            Triple("<html>404</html>", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/html")),
            Triple("""{"detail":"Method Not Allowed"}""", HttpStatusCode.MethodNotAllowed, json),
        )
        for ((body, status, headers) in replies) {
            val api = MediaApi(createHttpClient(MockEngine { respond(body, status, headers) }))
            assertEquals(MediaApi.NO_MKDIR, assertIs<ApiResult.Failed>(api.makeFolder(url, "/srv/a")).message, body)
        }
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
