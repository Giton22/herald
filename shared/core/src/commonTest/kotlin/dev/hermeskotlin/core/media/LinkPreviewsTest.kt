package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPreviewsTest {
    private val html = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")

    @Test fun selectsThreeDistinctLinksOutsideCodeAndImages() {
        val text = """
            `https://code.example` ![image](https://image.example/a.png)
            [https://label.example](https://one.example/a_(b)) https://one.example/a_(b).
            ```
            https://fenced.example
            ```
            <https://two.example> https://three.example?q=one&x=two. https://four.example
        """.trimIndent()
        assertEquals(listOf("https://one.example/a_(b)", "https://two.example", "https://three.example?q=one&x=two"), replyWebLinks(text))
    }

    @Test fun followsRenderedMarkdownDestinationsAndIgnoresCodeAndReferenceImages() {
        assertEquals(emptyList(), replyWebLinks("    https://code.example"))
        assertEquals(emptyList(), replyWebLinks("[https://label.test](mailto:a@example.com)"))
        assertEquals(emptyList(), replyWebLinks("![alt][pic]\n\n[pic]: https://images.example/a.png"))
        assertEquals(listOf("https://e.test/a)"), replyWebLinks("[x](<https://e.test/a)>)"))
        assertEquals(listOf("https://e.test"), replyWebLinks("[https://e.test](https://e.test)?prose"))
        assertEquals(listOf("https://e.test/a?x=1&y=2"), replyWebLinks("[x](https://e.test/a?x=1&amp;y=2)"))
        assertEquals(listOf("https://second.example", "https://first.example"), replyWebLinks(
            "[second][b] [first][a]\n\n[a]: https://first.example\n[b]: https://second.example",
        ))
    }

    @Test fun formattedBareLinksKeepTheirActualDestination() {
        for (text in listOf("**https://example.com**", "*https://example.com*", "~~https://example.com~~", "https://example.com**bold**")) {
            assertEquals(listOf("https://example.com"), replyWebLinks(text), text)
        }
        assertEquals(listOf("https://example.com/?x=one&y=two"), replyWebLinks("**https://example.com/?x=one&y=two**"))
        assertEquals(listOf("https://example.com/path~part*part"), replyWebLinks("https://example.com/path~part*part"))
    }

    @Test fun honorsHttpDeclaredHtmlCharset() = runBlocking {
        val content = "<title>Caf\u00e9</title><meta name='description' content='Cr\u00e8me'>"
        val bytes = content.map { it.code.toByte() }.toByteArray()
        val api = LinkPreviews(createHttpClient(MockEngine {
            respond(bytes, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=ISO-8859-1"))
        }))
        val card = assertNotNull(api.load("https://example.com/latin1"))
        assertEquals("Caf\u00e9", card.title)
        assertEquals("Cr\u00e8me", card.description)
        assertEquals("UTF-8", linkMetadata("<title>UTF-8</title>".encodeToByteArray(), "https://example.com", "invalid@charset")?.title)
    }

    @Test fun redirectsAndParsesMetadataWithRelativeThumbnail() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/start" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/article"))
                "/photo.png" -> respond("picture", headers = headersOf(HttpHeaders.ContentType, "image/png"))
                else -> respond("""<TITLE>Fallback</TITLE><META content='Cats &amp; Dogs' property='og:title'>
                    <meta name='description' content='A useful article'><meta property='og:image' content='/photo.png'>""", headers = html)
            }
        }))
        val card = assertNotNull(api.load("https://example.com/start"))
        assertEquals("Cats & Dogs", card.title)
        assertEquals("A useful article", card.description)
        assertEquals("picture", card.image?.decodeToString())
        assertEquals("https://example.com/start", card.url)
    }

    @Test fun redirectsReplaceQueriesWithoutLeakingThemToAnotherHost() = runBlocking {
        val start = "https://source.example/dir/start?private=secret"
        for ((location, expected) in listOf(
            "https://other.example/target" to "https://other.example/target",
            "//other.example/target" to "https://other.example/target",
            "child" to "https://source.example/dir/child",
            "?new=1" to "https://source.example/dir/start?new=1",
            "#section" to "$start#section",
        )) {
            val requests = mutableListOf<String>()
            val api = LinkPreviews(createHttpClient(MockEngine { request ->
                requests += request.url.toString()
                if (requests.size == 1) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                else respond("<title>Destination</title>", headers = html)
            }))
            assertNotNull(api.load(start))
            assertEquals(listOf(start, expected), requests, location)
        }
    }

    @Test fun fallsBackToStructuredPageImageAndKeepsOpenGraphPreferred() {
        val structured = """<title>Search</title><meta itemprop='image' content='/logo.png'>"""
        assertEquals("https://example.com/logo.png", linkMetadata(structured.encodeToByteArray(), "https://example.com/")?.image)
        val withTwitter = structured + """<meta name='twitter:image' content='/twitter.png'>"""
        assertEquals("https://example.com/twitter.png", linkMetadata(withTwitter.encodeToByteArray(), "https://example.com/")?.image)
        val withOpenGraph = withTwitter + """<meta property='og:image' content='/preview.png'>"""
        assertEquals("https://example.com/preview.png", linkMetadata(withOpenGraph.encodeToByteArray(), "https://example.com/")?.image)
    }

    @Test fun fallsBackToDeclaredSiteIconAfterPageImages() {
        val icons = """<title>News</title><link rel='icon' href='/small.png'><link rel='apple-touch-icon' href='/large.png'>"""
        assertEquals("https://example.com/large.png", linkMetadata(icons.encodeToByteArray(), "https://example.com/")?.image)
        val preview = icons + """<meta itemprop='image' content='/preview.png'>"""
        assertEquals("https://example.com/preview.png", linkMetadata(preview.encodeToByteArray(), "https://example.com/")?.image)
    }

    @Test fun requestsHtmlAndImagesFromContentNegotiatingSites() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            val accept = request.headers[HttpHeaders.Accept].orEmpty()
            when {
                request.url.encodedPath == "/image" && accept.startsWith("image/*") ->
                    respond("thumbnail", headers = headersOf(HttpHeaders.ContentType, "image/png"))
                request.url.encodedPath != "/image" && accept.startsWith("text/html") ->
                    respond("""<title>Repository</title><meta property='og:image' content='/image'>""", headers = html)
                else -> respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }))
        val card = assertNotNull(api.load("https://example.com/repo"))
        assertEquals("Repository", card.title)
        assertEquals("thumbnail", card.image?.decodeToString())
    }

    @Test fun failedImageKeepsTitleAndDescription() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/bad") respond("missing", HttpStatusCode.NotFound)
            else respond("""<title>Page title</title><meta name='twitter:description' content='Details'><meta name='twitter:image' content='/bad'>""", headers = html)
        }))
        val card = assertNotNull(api.load("https://example.com"))
        assertEquals("Page title", card.title)
        assertEquals("Details", card.description)
        assertNull(card.image)
    }

    @Test fun thumbnailNetworkFailureKeepsTheCard() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/broken") error("connection reset")
            respond("""<title>Still useful</title><meta property='og:image' content='/broken'>""", headers = html)
        }))
        assertEquals("Still useful", api.load("https://example.com")?.title)
    }

    @Test fun skipsNonHtmlMissingTitlesAndUnsafeUrls() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/json" -> respond("<title>Not HTML</title>", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "/redirect" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "file:///private"))
                else -> respond("<p>No title</p>", headers = html)
            }
        }))
        for (url in listOf("https://example.com/json", "https://example.com/redirect", "https://example.com/empty", "file:///private", "https://user:password@example.com")) assertNull(api.load(url))
    }

    @Test fun relativeLinksNeverBecomeLocalhostPreviewsOrConsumeSlots() = runBlocking {
        assertEquals(listOf("https://example.com"), replyWebLinks(
            "[relative](/docs) [anchor](#section) [implicit](//example.com/path) [web](https://example.com)",
        ))
        var requests = 0
        val api = LinkPreviews(createHttpClient(MockEngine {
            requests++
            respond("<title>Unexpected</title>", headers = html)
        }))
        for (url in listOf("", "relative", "/relative", "#section", "//example.com/path", "http:relative",
            "https://", "https://?x", "https://#x", "https:///relative", "https://\\example.com", "https://:80", "https://u:p@example.com")) assertNull(api.load(url), url)
        assertEquals(0, requests)
        assertTrue(webUrl("HTTPS://example.com") != null)
        assertTrue(webUrl("https://[2606:4700::1111]/") != null)
    }

    @Test fun addressesOnTheUsersOwnNetworksAreNeverPreviewed() = runBlocking {
        for (host in listOf("localhost", "router", "printer.local", "nas.lan", "box.home.arpa", "app.localhost", "svc.internal",
            "127.0.0.1", "10.0.0.8", "172.20.1.1", "192.168.1.1", "169.254.169.254", "100.101.102.103", "0.0.0.0", "224.0.0.1",
            "[::1]", "[::]", "[fd7a:115c:a1e0::1]", "[fe80::1]", "[::ffff:192.168.1.1]",
            // Lenient address forms a connection reads as 127.0.0.1, 10.0.0.1, 192.168.1.1 and so on.
            "127.1", "10.1", "192.168.257", "0177.0.0.1", "010.0.0.1", "2130706433",
            "１９２。１６８。１.１")) {
            assertNull(webUrl("http://$host/admin"), host)
        }
        for (host in listOf("example.com", "8.8.8.8", "100.128.0.1", "172.32.0.1", "[2606:4700::1111]")) assertNotNull(webUrl("http://$host/"), host)
        assertEquals(listOf("https://example.com"), replyWebLinks("http://192.168.1.1/reboot http://localhost:9119 https://example.com"))
        assertTrue(publicAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        assertTrue(!publicAddress(byteArrayOf(100, 100, 1, 1)))
        assertTrue(!publicAddress(ByteArray(16).also { it[15] = 1 }))
        assertTrue(!publicAddress(ByteArray(16).also { it[0] = 0xfd.toByte() }))
        assertTrue(publicAddress(ByteArray(16).also { it[0] = 0x26 }))
        val nat64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(publicAddress(nat64 + byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        assertTrue(!publicAddress(nat64 + byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))

        val requests = mutableListOf<String>()
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            requests += request.url.toString()
            when (request.url.encodedPath) {
                "/to-router" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://192.168.1.1/reboot"))
                else -> respond("""<title>Page</title><meta property='og:image' content='http://127.0.0.1:8080/x.png'>""", headers = html)
            }
        }))
        assertNull(api.load("https://example.com/to-router"))
        assertNull(assertNotNull(api.load("https://example.com/page")).image)
        assertEquals(listOf("https://example.com/to-router", "https://example.com/page"), requests)
    }

    @Test fun droppedConnectionsAndBusyServersAreTriedAgain() = runBlocking {
        var calls = 0
        var failure: String? = "reset"
        val api = LinkPreviews(createHttpClient(MockEngine {
            calls++
            when (failure) {
                "reset" -> error("connection reset")
                "busy" -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> respond("<title>Back online</title>", headers = html)
            }
        }))
        assertNull(api.load("https://example.com"))
        failure = "busy"
        assertNull(api.load("https://example.com"))
        failure = null
        assertEquals("Back online", api.load("https://example.com")?.title)
        assertEquals("Back online", api.load("https://example.com")?.title)
        assertEquals(3, calls)
    }

    @Test fun aNameThatLeadsHomeStaysRefused() = runBlocking {
        var calls = 0
        val api = LinkPreviews(createHttpClient(MockEngine {
            calls++
            throw NotPublicAddress("127.0.0.1.nip.io")
        }))
        assertNull(api.load("https://127.0.0.1.nip.io/"))
        assertNull(api.load("https://127.0.0.1.nip.io/"))
        assertEquals(1, calls)
    }

    @Test fun aCardWhoseThumbnailFailedIsLoadedAgain() = runBlocking {
        var imageFails = true
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/image") {
                if (imageFails) error("connection reset")
                respond("thumbnail", headers = headersOf(HttpHeaders.ContentType, "image/png"))
            } else respond("""<title>Page</title><meta property='og:image' content='/image'>""", headers = html)
        }))
        assertNull(assertNotNull(api.load("https://example.com")).image)
        imageFails = false
        assertEquals("thumbnail", api.load("https://example.com")?.image?.decodeToString())
    }

    @Test fun cachedThumbnailsStayWithinAByteBudget() = runBlocking {
        val pages = mutableMapOf<String, Int>()
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith(".png")) respond(ByteArray(2 * 1024 * 1024), headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else {
                pages[path] = (pages[path] ?: 0) + 1
                respond("""<title>Page</title><meta property='og:image' content='$path.png'>""", headers = html)
            }
        }))
        for (i in 1..5) assertNotNull(api.load("https://example.com/$i")?.image)
        api.load("https://example.com/5")
        api.load("https://example.com/1")
        assertEquals(1, pages["/5"])
        assertEquals(2, pages["/1"])
    }

    @Test fun listedAndNonSvgSiteIconsAreUsed() {
        val shortcut = """<title>Shop</title><link rel='shortcut icon' href='/favicon.png'>"""
        assertEquals("https://example.com/favicon.png", linkMetadata(shortcut.encodeToByteArray(), "https://example.com/")?.image)
        val svgFirst = """<title>Docs</title><link rel='icon' type='image/svg+xml' href='/logo.svg'><link rel='icon' href='/logo.png'>"""
        assertEquals("https://example.com/logo.png", linkMetadata(svgFirst.encodeToByteArray(), "https://example.com/")?.image)
        val svgOnly = """<title>Docs</title><link rel='icon' href='/logo.svg?v=2'>"""
        assertNull(linkMetadata(svgOnly.encodeToByteArray(), "https://example.com/")?.image)
        assertNull(linkMetadata("""<title>Mask</title><link rel='mask-icon' href='/m.png'>""".encodeToByteArray(), "https://example.com/")?.image)
    }

    @Test fun svgThumbnailsAreNotDownloaded() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/image") respond("<svg/>", headers = headersOf(HttpHeaders.ContentType, "image/svg+xml"))
            else respond("""<title>Vector</title><meta property='og:image' content='/image'>""", headers = html)
        }))
        assertNull(assertNotNull(api.load("https://example.com")).image)
    }

    @Test fun largePagesUseOnlyABoundedPrefix() = runBlocking {
        val padding = " ".repeat(262_144)
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/late") padding + "<title>Too late</title>"
                else "<title>Large page</title>" + padding
            val headers = if (request.url.encodedPath == "/declared")
                headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.ContentLength to listOf(body.length.toString())) else html
            respond(body, headers = headers)
        }))
        assertEquals("Large page", api.load("https://example.com/chunked")?.title)
        assertEquals("Large page", api.load("https://example.com/declared")?.title)
        assertNull(api.load("https://example.com/late"))
    }

    @Test fun findsLateImageMetadataWithinTwoMebibytesWithoutRefetching() = runBlocking {
        var pages = 0
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/image") respond("thumbnail", headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else {
                pages++
                respond("<title>Large page</title>" + " ".repeat(262_144) + """<meta property='og:image' content='/image'>""", headers = html)
            }
        }))
        assertEquals("thumbnail", assertNotNull(api.load("https://example.com/late-image")).image?.decodeToString())
        assertEquals(1, pages)
    }

    @Test fun expandedPagesStayBoundedAndSkipExpansionWhenAnImageIsKnown() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            val early = if (request.url.encodedPath == "/early") """<meta property='og:image' content='/missing'>""" else ""
            respond("<title>Keep title</title>" + early + " ".repeat(262_144) +
                """<meta name='description' content='Late description'>""" + " ".repeat(2_097_152) +
                """<meta property='og:image' content='/too-late'>""", headers = html)
        }))
        val expanded = assertNotNull(api.load("https://example.com/bounded"))
        assertEquals("Keep title", expanded.title)
        assertEquals("Late description", expanded.description)
        assertNull(expanded.image)
        assertEquals("", assertNotNull(api.load("https://example.com/early")).description)
    }

    @Test fun failedOrStalledExpansionPreservesTheInitialTextCard() = runBlocking {
        for (fail in listOf(true, false)) {
            val channel = ByteChannel(autoFlush = true)
            val writer = launch {
                channel.writeFully(("<title>Keep me</title>" + " ".repeat(262_144)).encodeToByteArray())
                if (fail) {
                    delay(100)
                    channel.cancel(IllegalStateException("connection reset"))
                } else awaitCancellation()
            }
            try {
                val api = LinkPreviews(createHttpClient(MockEngine { respond(channel, headers = html) }))
                val card = assertNotNull(api.load("https://example.com/stalled"))
                assertEquals("Keep me", card.title)
                assertNull(card.image)
            } finally { writer.cancel() }
        }
    }

    @Test fun boundsThumbnailBodiesWithoutRelyingOnContentLength() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/image") respond("x".repeat(2_097_153), headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else respond("""<title>Small</title><meta property='og:image' content='/image'>""", headers = html)
        }))
        assertNull(assertNotNull(api.load("https://example.com/small")).image)
    }

    @Test fun limitsParallelLoadsAndCachesFailures() = runBlocking {
        val lock = Mutex()
        var active = 0
        var peak = 0
        var calls = 0
        val api = LinkPreviews(createHttpClient(MockEngine {
            lock.withLock {
                calls++
                active++
                peak = maxOf(peak, active)
            }
            delay(10)
            lock.withLock { active-- }
            respond("", HttpStatusCode.NotFound)
        }))
        (1..3).map { async { api.load("https://example.com/$it") } }.awaitAll()
        assertEquals(2, peak)
        assertNull(api.load("https://example.com/1"))
        assertEquals(3, calls)
    }

    @Test fun cancellationIsNotCachedAsFailure() = runBlocking {
        var cancelled = true
        val api = LinkPreviews(createHttpClient(MockEngine {
            if (cancelled) throw CancellationException("left chat")
            respond("<title>Back</title>", headers = html)
        }))
        assertFailsWith<CancellationException> { api.load("https://example.com") }
        cancelled = false
        assertEquals("Back", api.load("https://example.com")?.title)
    }
}
