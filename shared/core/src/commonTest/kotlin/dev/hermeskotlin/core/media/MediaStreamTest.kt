package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.auth.WebSession
import dev.hermeskotlin.core.chat.AttachmentKind
import dev.hermeskotlin.core.chat.AttachmentSource
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.chat.extractReplyMedia
import dev.hermeskotlin.core.chat.splitAttachmentRefs
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ACCESS_BLOCKED
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaStreamTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun everyAudioAndVideoExtensionHasItsKind() {
        listOf("a.mp3", "a.wav", "a.ogg", "a.oga", "a.opus", "a.flac", "a.m4a", "a.aac", "Voice Note.MP3").forEach {
            assertEquals(GatewayFileKind.Audio, GatewayFileKind.of(it), it)
            assertEquals(MediaType.Audio, mediaTypeOf(it), it)
        }
        listOf("a.mp4", "a.m4v", "a.mov", "a.mkv", "a.webm", "a.avi", "a.3gp", "Clip.MOV").forEach {
            assertEquals(GatewayFileKind.Video, GatewayFileKind.of(it), it)
            assertEquals(MediaType.Video, mediaTypeOf(it), it)
        }
        // Played, not previewed as text; other binaries keep their card.
        assertFalse(GatewayFileKind.Audio.previewable)
        assertFalse(GatewayFileKind.Video.previewable)
        assertEquals(GatewayFileKind.Other, GatewayFileKind.of("backup.zip"))
        assertNull(mediaTypeOf("report.pdf"))
        assertNull(mediaTypeOf("notes.txt"))
        assertNull(mediaTypeOf("chart.png"))
    }

    @Test
    fun aMimeTypeWinsOverTheName() {
        assertEquals(MediaType.Audio, mediaTypeOf("recording", "audio/mpeg"))
        assertEquals(MediaType.Video, mediaTypeOf("clip.bin", "Video/MP4; codecs=avc1"))
        assertEquals(MediaType.Audio, mediaTypeOf("song.mp3", "application/octet-stream"))
        assertNull(mediaTypeOf("song", "application/octet-stream"))
    }

    @Test
    fun aWebAddressIsJudgedByItsPathAlone() {
        assertEquals(MediaType.Video, mediaTypeOf("https://cdn.example.com/v/clip.mp4?sig=abc.png#t=5"))
        assertNull(mediaTypeOf("https://example.com/watch?v=clip.mp4"))
    }

    @Test
    fun repliesKnowTheirAudioAndVideo() {
        val (_, media) = extractReplyMedia(
            "Here: MEDIA:/opt/data/out/song.mp3 and MEDIA:/opt/data/out/clip.webm, MEDIA:/opt/data/out/report.zip " +
                "and MEDIA:https://cdn.example.com/talk.m4a",
        )
        assertEquals(listOf(MediaType.Audio, MediaType.Video, null, MediaType.Audio), media.map { it.mediaType })
        assertFalse(media[0].isImage)
        assertTrue(media[0].onGateway)
        assertEquals(MediaType.Audio, ReplyMedia("data:audio/ogg;base64,AAAA", "clip", isImage = false).mediaType)
        assertNull(ReplyMedia("/a/cover.mp4.png", "cover.mp4.png", isImage = true).mediaType)
    }

    @Test
    fun sentAudioKeepsItsBytesToPlay() {
        val bytes = byteArrayOf(1, 2, 3)
        val voice = OutgoingAttachment("1", "memo.m4a", "audio/mp4", bytes).toShown()
        assertEquals(AttachmentKind.File, voice.kind)
        assertEquals(MediaType.Audio, voice.mediaType)
        assertContentEquals(bytes, voice.original)
        val untyped = OutgoingAttachment("2", "clip", "video/quicktime", bytes).toShown()
        assertEquals(MediaType.Video, untyped.mediaType)
        // An ordinary file still goes out without its bytes kept in memory.
        val zip = OutgoingAttachment("3", "a.zip", "application/zip", bytes).toShown()
        assertNull(zip.mediaType)
        assertNull(zip.original)
    }

    @Test
    fun aClipTooLargeForTheSocketIsntKeptInMemory() {
        // Read as it goes up (up to 100 MB): nothing of it is held for the bubble.
        val streamed = OutgoingAttachment(
            "1", "long.mp4", "video/mp4", ByteArray(0),
            source = AttachmentSource(90_000_000L) { error("not read here") },
        ).toShown()
        assertEquals(MediaType.Video, streamed.mediaType)
        assertNull(streamed.original)
        // Held whole but past what one socket message carries, it goes up over HTTP too, and isn't kept either.
        val held = OutgoingAttachment("2", "song.mp3", "audio/mpeg", ByteArray(OutgoingAttachment.MAX_INLINE_BYTES + 1)).toShown()
        assertNull(held.original)
    }

    @Test
    fun historyReadsAPathQuotedAnyWayTheGatewayQuotesIt() {
        // file.attach quotes a path with spaces in the first of ` " ' it doesn't hold (_format_ref_value).
        val prompt = listOf(
            "@file:`/opt/data/attachments/My clip ünï.mp4`",
            "@file:\"/opt/data/attachments/it's here.ogg\"",
            "@file:'/opt/data/attachments/a `b` \"c\".mp3'",
            "",
            "listen",
        ).joinToString("\r\n")
        val (refs, text) = splitAttachmentRefs(prompt, "k")
        assertEquals(listOf("My clip ünï.mp4", "it's here.ogg", "a `b` \"c\".mp3"), refs.map { it.name })
        assertEquals(
            listOf("/opt/data/attachments/My clip ünï.mp4", "/opt/data/attachments/it's here.ogg", "/opt/data/attachments/a `b` \"c\".mp3"),
            refs.map { it.gatewayPath },
        )
        assertEquals(listOf(MediaType.Video, MediaType.Audio, MediaType.Audio), refs.map { it.mediaType })
        assertEquals("listen", text)
    }

    @Test
    fun historyKnowsAnAttachedFilesPathWhenItIsAbsolute() {
        val (refs, _) = splitAttachmentRefs("@file:/opt/data/attachments/memo.ogg\n@file:attachments/song.mp3\n\nlisten", "k")
        assertEquals(listOf("/opt/data/attachments/memo.ogg", null), refs.map { it.gatewayPath })
        assertEquals(listOf(MediaType.Audio, MediaType.Audio), refs.map { it.mediaType })
    }

    @Test
    fun streamUrlEncodesThePathAndNamesTheProfile() {
        assertEquals(
            "https://hermes.example.ts.net/api/files/stream?path=%2Fopt%2Fmy+clips%2Fa%26b%231.mp4&profile=rin",
            mediaStreamUrl(gateway, "/opt/my clips/a&b#1.mp4", "rin"),
        )
        assertEquals(
            "http://box:9119/hermes/api/files/stream?path=%2Ftmp%2F%C3%A9t%C3%A9.mp3",
            mediaStreamUrl(GatewayUrl.parse("http://box:9119/hermes/"), "/tmp/été.mp3", " "),
        )
    }

    @Test
    fun theStreamRoutesAnswerDecidesThePlan() {
        assertEquals(StreamPlan.Stream, streamPlan(ApiResult.Success(Unit)))
        // An older gateway, a type it won't stream, files locked to a folder: fetch it whole instead.
        assertEquals(StreamPlan.Fetch, streamPlan(ApiResult.Failed(404, "No such API endpoint: /api/files/stream")))
        assertEquals(StreamPlan.Fetch, streamPlan(ApiResult.Failed(404, "File not found")))
        assertEquals(StreamPlan.Fetch, streamPlan(ApiResult.Failed(415, "Unsupported media type")))
        assertEquals(StreamPlan.Fetch, streamPlan(ApiResult.Failed(403, "Path outside managed files root")))
        // What fetching wouldn't change.
        val sensitive = ApiResult.Failed(403, "Access to sensitive files is not allowed")
        assertEquals(StreamPlan.Refused(sensitive), streamPlan(sensitive))
        val access = ApiResult.Failed(403, ACCESS_BLOCKED)
        assertEquals(StreamPlan.Refused(access), streamPlan(access))
        val tooBig = ApiResult.Failed(413, "File is too large")
        assertEquals(StreamPlan.Refused(tooBig), streamPlan(tooBig))
        assertEquals(StreamPlan.Refused(ApiResult.SessionExpired), streamPlan(ApiResult.SessionExpired))
        assertEquals(StreamPlan.Refused(ApiResult.Unavailable("down")), streamPlan(ApiResult.Unavailable("down")))
    }

    @Test
    fun playerHeadersCarryTheLentSignInAsOneCookieHeader() {
        val headers = mediaRequestHeaders(
            WebSession(
                cookies = listOf("hermes_session_at=abc; Path=/; Secure; HttpOnly", "hermes_session_provider=gh; Path=/"),
                headers = mapOf("CF-Access-Client-Id" to "id"),
                expiresAt = null,
            ),
        )
        assertEquals(mapOf("CF-Access-Client-Id" to "id", "Cookie" to "hermes_session_at=abc; hermes_session_provider=gh"), headers)
        assertEquals(emptyMap(), mediaRequestHeaders(WebSession(emptyList(), emptyMap(), null)))
    }

    @Test
    fun aStreamedFileIsProbedForOneByteThenPlayedFromTheRoute() = runTest {
        val asked = mutableListOf<String>()
        var range: String? = null
        val sources = MediaSources(MediaApi(createHttpClient(MockEngine { request ->
            asked += request.url.encodedPath
            range = request.headers[HttpHeaders.Range]
            respond(byteArrayOf(0), HttpStatusCode.PartialContent)
        }))) { WebSession(listOf("hermes_session_at=t; Path=/"), emptyMap(), null) }

        val source = assertIs<PlaySource.Gateway>(sources.resolve("/opt/a.mp3", gateway, "rin"))

        assertEquals(listOf("/api/files/stream"), asked)
        assertEquals("bytes=0-0", range)
        assertEquals(mediaStreamUrl(gateway, "/opt/a.mp3", "rin"), source.url)
        assertEquals(mapOf("Cookie" to "hermes_session_at=t"), source.headers)
        // The sign-in never shows in a log line.
        assertFalse("hermes_session_at" in source.toString())
    }

    @Test
    fun aGatewayWithoutTheStreamRouteHandsTheFileOverWhole() = runTest {
        val asked = mutableListOf<String>()
        val sources = MediaSources(MediaApi(createHttpClient(MockEngine { request ->
            asked += request.url.encodedPath
            if (request.url.encodedPath.endsWith("stream")) {
                respond("""{"detail":"No such API endpoint: /api/files/stream"}""", HttpStatusCode.NotFound, json)
            } else {
                respond(byteArrayOf(7, 8), HttpStatusCode.OK)
            }
        }))) { WebSession(emptyList(), emptyMap(), null) }

        val source = assertIs<PlaySource.Bytes>(sources.resolve("file:///opt/a.mp4", gateway, null))

        assertEquals(listOf("/api/files/stream", "/api/fs/download"), asked)
        assertContentEquals(byteArrayOf(7, 8), source.bytes)
    }

    @Test
    fun aRefusedFileIsNotFetchedAnotherWay() = runTest {
        val asked = mutableListOf<String>()
        val sources = MediaSources(MediaApi(createHttpClient(MockEngine { request ->
            asked += request.url.encodedPath
            respond("""{"detail":"Access to sensitive files is not allowed"}""", HttpStatusCode.Forbidden, json)
        }))) { WebSession(emptyList(), emptyMap(), null) }

        val source = assertIs<PlaySource.Failed>(sources.resolve("/opt/.env.mp3", gateway, null))

        assertEquals(403, assertIs<ApiResult.Failed>(source.result).status)
        assertEquals(listOf("/api/files/stream"), asked)
    }

    @Test
    fun webAndInlineClipsNeedNoGateway() = runTest {
        val sources = MediaSources(MediaApi(createHttpClient(MockEngine { error("no request expected") }))) { error("no sign-in expected") }
        assertEquals(PlaySource.Web("https://example.com/a.mp3"), sources.resolve("https://example.com/a.mp3", null, null))
        assertContentEquals(byteArrayOf(0, 0, 0), assertIs<PlaySource.Bytes>(sources.resolve("data:audio/ogg;base64,AAAA", null, null)).bytes)
        assertIs<PlaySource.Failed>(sources.resolve("data:audio/ogg,raw", null, null))
        assertIs<PlaySource.Failed>(sources.resolve("/opt/a.mp3", null, null))
    }
}
