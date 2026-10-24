package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.capabilities.HubSkill
import dev.hermeskotlin.core.capabilities.SkillsHubApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsHubViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A gateway that answers by path; every request is logged as `METHOD path?query`. */
    private class Rig(json: io.ktor.http.Headers) {
        val log = mutableListOf<String>()
        var sources = """{"sources":[],"featured":[{"name":"pdf","identifier":"skills-sh/acme/pdf"}],"installed":{}}"""
        var official = """{"skills":[{"name":"ascii-art","identifier":"official/creative/ascii-art","source":"official"}]}"""
        var search = """{"results":[{"name":"pdf","identifier":"skills-sh/acme/pdf"}],"timed_out":[]}"""
        var scan = """{"name":"pdf","verdict":"safe","policy":"allow"}"""
        var scanStatus = HttpStatusCode.OK
        var previewStatus = HttpStatusCode.OK
        /** Called with the identifier before a preview or scan answers; a test can hold one here. */
        var gate: suspend (String) -> Unit = {}
        var sourcesStatus = HttpStatusCode.OK
        /** Called before a sources read answers; a test can hold one here. */
        var sourcesGate: suspend () -> Unit = {}
        var status: suspend () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":["done"]}""" }
        val config = MockEngineConfig().apply {
            addHandler { request ->
                val path = request.url.encodedPath
                log += "${request.method.value} $path?${request.url.encodedQuery}"
                when {
                    path.endsWith("hub/sources") -> {
                        sourcesGate()
                        respond(sources, sourcesStatus, json)
                    }
                    path.endsWith("hub/official") -> respond(official, HttpStatusCode.OK, json)
                    path.endsWith("hub/search") -> respond(search, HttpStatusCode.OK, json)
                    path.endsWith("hub/scan") -> {
                        gate(request.url.parameters["identifier"].orEmpty())
                        respond(scan, scanStatus, json)
                    }
                    path.endsWith("hub/preview") -> {
                        val id = request.url.parameters["identifier"].orEmpty()
                        gate(id)
                        respond(if (previewStatus.value < 300) """{"name":"$id"}""" else """{"detail":"no such skill"}""", previewStatus, json)
                    }
                    path.endsWith("/status") -> status().let { (code, body) -> respond(body, code, json) }
                    else -> respond("""{"name":"act1","ok":true}""", HttpStatusCode.OK, json)
                }
            }
        }
        fun count(part: String) = log.count { part in it }
    }

    private fun TestScope2.vm(rig: Rig): SkillsHubViewModel {
        rig.config.dispatcher = dispatcher
        return SkillsHubViewModel(SkillsHubApi(createHttpClient(MockEngine(rig.config)), pollIntervalMs = 0))
    }

    private fun rig() = Rig(json)

    /** Binds and opens the hub page, as the page does. */
    private fun SkillsHubViewModel.bound(profile: String?) {
        bind(gateway, profile)
        openHub()
    }

    private val community = HubSkill(name = "pdf", identifier = "skills-sh/acme/pdf", source = "skills-sh")
    private val official = HubSkill(name = "ascii-art", identifier = "official/creative/ascii-art", source = "official")

    @Test
    fun bindLoadsFeaturedAndOfficialForTheProfile() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bound("ruby")
        assertTrue(rig.log.filter { "hub/sources" in it || "hub/official" in it }.all { "profile=ruby" in it })
        assertEquals(2, rig.log.size)
        assertEquals(listOf("pdf"), vm.state.value.featured.items?.map { it.name })
        assertEquals(listOf("ascii-art"), vm.state.value.official.items?.map { it.name })
    }

    @Test
    fun searchWaitsForThePause() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bound(null)
        vm.search("pd")
        advanceTimeBy(200)
        vm.search("pdf")
        advanceTimeBy(401)
        assertEquals(1, rig.count("hub/search"))
        assertTrue("q=pdf" in rig.log.single { "hub/search" in it })
        assertEquals(1, vm.state.value.results?.items?.size)
    }

    @Test
    fun blankQueryClearsResults() = runTest(dispatcher) {
        val vm = vm(rig())
        vm.bound(null)
        vm.search("pdf")
        advanceTimeBy(401)
        assertNotNull(vm.state.value.results)
        vm.search("  ")
        assertNull(vm.state.value.results)
    }

    @Test
    fun installedIdentifiersMarkRows() = runTest(dispatcher) {
        val rig = rig().apply { sources = """{"installed":{"official/creative/ascii-art":{"name":"ascii-art"}}}""" }
        val vm = vm(rig)
        vm.bound(null)
        assertTrue("official/creative/ascii-art" in vm.state.value.installed)
    }

    @Test
    fun officialInstallSkipsTheScan() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        var changed = 0
        vm.onSkillsChanged = { changed++ }
        vm.bound(null)
        vm.install(official)
        assertEquals(0, rig.count("hub/scan"))
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
        assertEquals("Installed ascii-art. It applies from the next chat.", vm.state.value.message)
        assertEquals(1, changed)
        assertTrue(vm.state.value.running.isEmpty())
    }

    @Test
    fun safeScanInstallsStraightAway() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bound(null)
        vm.install(community)
        assertEquals(1, rig.count("hub/scan"))
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
    }

    @Test
    fun cautionWaitsForConfirmation() = runTest(dispatcher) {
        val rig = rig().apply { scan = """{"name":"pdf","verdict":"caution","policy":"ask"}""" }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(community)
        assertNotNull(vm.state.value.confirm)
        assertEquals(0, rig.count("POST /api/skills/hub/install"))
        vm.confirmInstall()
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
        assertNull(vm.state.value.confirm)

        vm.install(community)
        vm.cancelConfirm()
        assertNull(vm.state.value.confirm)
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
    }

    @Test
    fun blockedPolicyNeverInstalls() = runTest(dispatcher) {
        val rig = rig().apply { scan = """{"name":"pdf","verdict":"dangerous","policy":"block"}""" }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(community)
        assertEquals(0, rig.count("POST /api/skills/hub/install"))
        assertEquals("block", vm.state.value.detail?.scan?.policy)
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun scanBlockedInstallOffersTheScan() = runTest(dispatcher) {
        val rig = rig().apply {
            status = { HttpStatusCode.OK to """{"running":false,"exit_code":1,"lines":["Not installed: the security scan found 2 high-risk patterns"]}""" }
        }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(community)
        assertEquals("pdf was blocked by the security scan.", vm.state.value.message)
        assertEquals("safe", vm.state.value.detail?.scan?.verdict)
    }

    @Test
    fun failureShowsTheLastLogLine() = runTest(dispatcher) {
        val rig = rig().apply { status = { HttpStatusCode.OK to """{"running":false,"exit_code":1,"lines":["fetching","network down"]}""" } }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(community)
        assertEquals("Couldn't install pdf: network down", vm.state.value.message)
    }

    @Test
    fun secondInstallTapWhileRunningIsIgnored() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply {
            status = { hold.await(); HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":[]}""" }
        }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(official)
        vm.install(official)
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
        assertTrue("official/creative/ascii-art" in vm.state.value.running)
        hold.complete(Unit)
    }

    @Test
    fun uninstallCallsBackSoTheListRefetches() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        var changed = 0
        vm.onSkillsChanged = { changed++ }
        vm.bound(null)
        vm.uninstall("ascii-art")
        assertEquals(1, changed)
        assertEquals("Removed ascii-art. It applies from the next chat.", vm.state.value.message)
    }

    @Test
    fun profileSwitchCancelsPolling() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply { status = { hold.await(); HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":[]}""" } }
        val vm = vm(rig)
        vm.bound("old")
        vm.install(official)
        vm.bound("other")
        val before = rig.count("/status")
        hold.complete(Unit)
        assertEquals(before, rig.count("/status"))
        assertNull(vm.state.value.message)
        assertTrue(vm.state.value.running.isEmpty())
    }

    @Test
    fun olderGatewayWithoutHubRoutesSaysSo() = runTest(dispatcher) {
        val rig = rig().apply { sourcesStatus = HttpStatusCode.NotFound; sources = """{"detail":"Not Found"}""" }
        val vm = vm(rig)
        vm.bound(null)
        assertTrue(vm.state.value.unsupported)
    }

    @Test
    fun expiredSessionDuringInstallRaisesTheFlag() = runTest(dispatcher) {
        val rig = rig().apply { status = { HttpStatusCode.Unauthorized to "{}" } }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(official)
        assertTrue(vm.state.value.sessionExpired)
        vm.consumeSessionExpired()
        assertFalse(vm.state.value.sessionExpired)
    }

    @Test
    fun openFillsPreviewAndScan() = runTest(dispatcher) {
        val vm = vm(rig())
        vm.bound(null)
        vm.open(community)
        val detail = assertNotNull(vm.state.value.detail)
        assertEquals(community.identifier, detail.preview?.name)
        assertEquals("safe", detail.scan?.verdict)
        assertNull(detail.error)
    }

    @Test
    fun previewFailureSetsDetailError() = runTest(dispatcher) {
        val vm = vm(rig().apply { previewStatus = HttpStatusCode.NotFound })
        vm.bound(null)
        vm.open(community)
        assertEquals("no such skill", vm.state.value.detail?.error)
        assertNull(vm.state.value.detail?.preview)
    }

    @Test
    fun aLateAnswerForAnotherSkillIsDropped() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply { gate = { if (it == community.identifier) hold.await() } }
        val vm = vm(rig)
        vm.bound(null)
        vm.open(community)
        vm.open(official)
        assertEquals(official.identifier, vm.state.value.detail?.preview?.name)
        hold.complete(Unit)
        assertEquals(official.installKey, vm.state.value.detail?.skill?.installKey)
        assertEquals(official.identifier, vm.state.value.detail?.preview?.name)

        val hold2 = CompletableDeferred<Unit>()
        rig.gate = { hold2.await() }
        vm.open(community)
        vm.closeDetail()
        hold2.complete(Unit)
        assertNull(vm.state.value.detail)
    }

    @Test
    fun updateAllReportsAndRefetches() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply { status = { hold.await(); HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":[]}""" } }
        val vm = vm(rig)
        var changed = 0
        vm.onSkillsChanged = { changed++ }
        vm.bound(null)
        vm.updateAll()
        assertTrue(UPDATE_ALL in vm.state.value.running)
        hold.complete(Unit)
        assertTrue(vm.state.value.running.isEmpty())
        assertEquals("Skills updated. It applies from the next chat.", vm.state.value.message)
        assertEquals(1, changed)
    }

    @Test
    fun aFailedScanAfterABlockedInstallSetsTheDetailError() = runTest(dispatcher) {
        val rig = rig().apply {
            status = { HttpStatusCode.OK to """{"running":false,"exit_code":1,"lines":["Not installed: the security scan found 2 high-risk patterns"]}""" }
        }
        val vm = vm(rig)
        vm.bound(null)
        vm.open(official) // the page is open on the skill being installed; its own scan succeeds
        rig.scanStatus = HttpStatusCode.NotFound
        vm.install(official) // official skips the pre-scan, so the failing scan is the follow-up
        assertEquals("official/creative/ascii-art", vm.state.value.detail?.skill?.installKey)
        assertEquals("HTTP 404", vm.state.value.detail?.error)
    }

    private val blockedLines = """{"running":false,"exit_code":1,"lines":["Not installed: the security scan found 2 high-risk patterns"]}"""

    @Test
    fun aFailedFollowUpScanDoesNotReopenAClosedSheet() = runTest(dispatcher) {
        val rig = rig().apply { scanStatus = HttpStatusCode.NotFound; status = { HttpStatusCode.OK to blockedLines } }
        val vm = vm(rig)
        vm.bound(null)
        vm.open(community)
        vm.closeDetail()
        vm.install(official)
        assertNull(vm.state.value.detail)
    }

    @Test
    fun aFailedFollowUpScanDoesNotLandOnAnotherSkillsSheet() = runTest(dispatcher) {
        val rig = rig().apply { status = { HttpStatusCode.OK to blockedLines } }
        val vm = vm(rig)
        vm.bound(null)
        vm.open(community)
        rig.scanStatus = HttpStatusCode.NotFound
        vm.install(official)
        assertEquals(community.installKey, vm.state.value.detail?.skill?.installKey)
        assertNull(vm.state.value.detail?.error)
    }

    @Test
    fun anExpiredSessionOnTheFollowUpScanRaisesTheFlag() = runTest(dispatcher) {
        val rig = rig().apply { scanStatus = HttpStatusCode.Unauthorized; status = { HttpStatusCode.OK to blockedLines } }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(official)
        assertTrue(vm.state.value.sessionExpired)
    }

    @Test
    fun bindClearsTheSearchText() = runTest(dispatcher) {
        val vm = vm(rig())
        vm.bound("a")
        vm.query.edit { append("pdf") }
        vm.bound("b")
        assertEquals("", vm.query.text.toString())
    }

    @Test
    fun bindAloneSendsNoRequest() = runTest(dispatcher) {
        val rig = rig()
        vm(rig).bind(gateway, "ruby")
        assertEquals(0, rig.log.size)
    }

    @Test
    fun retryAfterAFailedLoadReloads() = runTest(dispatcher) {
        val rig = rig().apply { sourcesStatus = HttpStatusCode.InternalServerError }
        val vm = vm(rig)
        vm.bound(null)
        assertNotNull(vm.state.value.featured.error)
        rig.sourcesStatus = HttpStatusCode.OK
        vm.refresh()
        assertEquals(listOf("pdf"), vm.state.value.featured.items?.map { it.name })
        assertNull(vm.state.value.featured.error)
    }

    @Test
    fun uninstallingAnOfficialSkillClearsItsMark() = runTest(dispatcher) {
        val rig = rig().apply { official = """{"skills":[{"name":"ascii-art","identifier":"official/creative/ascii-art","source":"official","installed":true}]}""" }
        val vm = vm(rig)
        vm.bound(null)
        assertTrue("official/creative/ascii-art" in vm.state.value.installed)
        vm.uninstall("ascii-art")
        assertFalse("official/creative/ascii-art" in vm.state.value.installed)
    }

    @Test
    fun previewAndScanCarryTheProfile() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bound("ruby")
        vm.open(community)
        vm.install(community)
        val hub = rig.log.filter { "hub/preview" in it || "hub/scan" in it }
        // The install reuses the scan the open sheet already has.
        assertEquals(2, hub.size)
        assertTrue(hub.all { "profile=ruby" in it })
    }

    @Test
    fun installScansAgainWhenTheSheetHasNoScan() = runTest(dispatcher) {
        val rig = rig().apply { scanStatus = HttpStatusCode.InternalServerError }
        val vm = vm(rig)
        vm.bound(null)
        vm.open(community)
        assertEquals(1, rig.count("hub/scan"))
        assertNotNull(vm.state.value.detail?.scanError)
        rig.scanStatus = HttpStatusCode.OK
        vm.install(community)
        assertEquals(2, rig.count("hub/scan"))
        // The install's own scan replaces the failed one on the page.
        assertNull(vm.state.value.detail?.scanError)
        assertNotNull(vm.state.value.detail?.scan)
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
    }

    @Test
    fun aFailedScanOnOpenIsShownOnTheSheet() = runTest(dispatcher) {
        val vm = vm(rig().apply { scanStatus = HttpStatusCode.NotFound; scan = """{"detail":"Hub scan failed: boom"}""" })
        vm.bound(null)
        vm.open(community)
        assertEquals("Couldn't scan: Hub scan failed: boom", vm.state.value.detail?.scanError)
        // The preview's own error stays free, so the page knows the preview loaded.
        assertNull(vm.state.value.detail?.error)
        assertNotNull(vm.state.value.detail?.preview)
    }

    @Test
    fun aListReadOlderThanAnInstallKeepsItsMark() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig()
        val vm = vm(rig)
        vm.bound(null)
        // The next sources read answers with what the gateway had before the install.
        rig.sourcesGate = { hold.await() }
        vm.refresh()
        vm.install(official)
        assertTrue(official.installKey in vm.state.value.installed)
        hold.complete(Unit)
        assertTrue(official.installKey in vm.state.value.installed)
    }

    @Test
    fun anInstallThatOutlastsTheWaitSaysSoAndFreesTheButton() = runTest(dispatcher) {
        val rig = rig().apply { status = { HttpStatusCode.OK to """{"running":true,"exit_code":null,"lines":[]}""" } }
        rig.config.dispatcher = dispatcher
        val vm = SkillsHubViewModel(SkillsHubApi(createHttpClient(MockEngine(rig.config)), pollIntervalMs = 1_000, actionTimeoutMs = 3_000))
        var changed = 0
        vm.onSkillsChanged = { changed++ }
        vm.bound(null)
        vm.install(official)
        advanceTimeBy(5_000)
        assertEquals("The install is still running on the gateway. Check back later.", vm.state.value.message)
        assertTrue(vm.state.value.running.isEmpty())
        assertFalse(official.installKey in vm.state.value.installed)
        assertEquals(1, changed)
    }

    @Test
    fun aReloadDropsSkillsRemovedElsewhere() = runTest(dispatcher) {
        val rig = rig().apply { sources = """{"installed":{"skills-sh/acme/pdf":{"name":"pdf"}}}""" }
        val vm = vm(rig)
        vm.bound(null)
        assertTrue("skills-sh/acme/pdf" in vm.state.value.installed)
        // Removed from another device or the CLI; the next visit reloads.
        rig.sources = """{"installed":{}}"""
        vm.closeHub()
        vm.openHub()
        assertFalse("skills-sh/acme/pdf" in vm.state.value.installed)
    }

    @Test
    fun aSearchReplacesTheInstalledMarks() = runTest(dispatcher) {
        val rig = rig().apply { sources = """{"installed":{"skills-sh/acme/pdf":{"name":"pdf"}}}""" }
        val vm = vm(rig)
        vm.bound(null)
        vm.search("pdf")
        advanceTimeBy(401)
        assertFalse("skills-sh/acme/pdf" in vm.state.value.installed)
    }

    @Test
    fun aLaterErrorKeepsAnExpiredSession() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        // official answers 401 at once; sources fails later with a plain error.
        val config = MockEngineConfig().apply {
            this.dispatcher = this@SkillsHubViewModelTest.dispatcher
            addHandler { request ->
                if (request.url.encodedPath.endsWith("hub/official")) respond("{}", HttpStatusCode.Unauthorized, json)
                else { hold.await(); respond("""{"detail":"down"}""", HttpStatusCode.BadGateway, json) }
            }
        }
        val vm = SkillsHubViewModel(SkillsHubApi(createHttpClient(MockEngine(config)), pollIntervalMs = 0))
        vm.bound(null)
        assertTrue(vm.state.value.sessionExpired)
        hold.complete(Unit)
        assertTrue(vm.state.value.sessionExpired)
    }

    @Test
    fun closingTheHubDropsItsSheets() = runTest(dispatcher) {
        val rig = rig().apply { scan = """{"name":"pdf","verdict":"caution","policy":"ask"}""" }
        val vm = vm(rig)
        vm.bound(null)
        vm.open(community)
        vm.install(community)
        assertNotNull(vm.state.value.detail)
        assertNotNull(vm.state.value.confirm)
        vm.closeHub()
        assertNull(vm.state.value.detail)
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun aBlockAfterTheHubClosedLeavesTheSheetForViewScan() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply { status = { hold.await(); HttpStatusCode.OK to blockedLines } }
        val vm = vm(rig)
        vm.bound(null)
        vm.install(official)
        vm.closeHub()
        hold.complete(Unit)
        assertNull(vm.state.value.detail)
        assertNotNull(vm.state.value.message)
    }
}

private typealias TestScope2 = kotlinx.coroutines.test.TestScope
