package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.config.ConfigApi
import dev.hermeskotlin.core.config.ConfigCatalog
import dev.hermeskotlin.core.config.at
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.hermeskotlin.ui.resources.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val schema = """{"fields":{
        "approvals.mode":{"type":"select","description":"Approval mode","category":"security","options":["manual","smart","off"]},
        "toolsets":{"type":"list","description":"Toolsets","category":"general"},
        "logging.level":{"type":"select","description":"Log level","category":"logging","options":["DEBUG","INFO"]}},
        "category_order":["general","security"]}"""
    private var config = """{"approvals":{"mode":"manual"},"toolsets":["hermes-cli"],"logging":{"level":"INFO"}}"""

    /** Every read fails while true, as with no network. */
    private var offline = false

    /** Each read of the values takes the config as it is when asked, then waits for the next gate, if any. */
    private val gates = ArrayDeque<CompletableDeferred<Unit>>()

    /** A gateway answering the schema and config, with [put] deciding what a save gets. */
    private fun viewModel(
        saves: MutableList<String> = mutableListOf(),
        put: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("""{"ok":true}""", HttpStatusCode.OK, json) },
    ): ConfigViewModel {
        val engine = MockEngineConfig()
        engine.dispatcher = dispatcher
        engine.addHandler { request ->
            when {
                request.method == HttpMethod.Put -> {
                    saves += "${request.url.encodedPath} ${request.body.toByteArray().decodeToString()}"
                    put(request)
                }
                offline -> respond("""{"detail":"Gateway down"}""", HttpStatusCode.ServiceUnavailable, json)
                request.url.encodedPath == "/api/config/schema" -> respond(schema, HttpStatusCode.OK, json)
                request.url.encodedPath == "/api/config/raw" -> respond("""{"yaml":"approvals:\n  mode: manual\n","path":"/srv/.hermes/config.yaml"}""", HttpStatusCode.OK, json)
                else -> {
                    val read = config
                    gates.removeFirstOrNull()?.await()
                    respond(read, HttpStatusCode.OK, json)
                }
            }
        }
        return ConfigViewModel(ConfigApi(createHttpClient(MockEngine(engine)))).also { it.bind(gateway, "rin") }
    }

    private fun ConfigViewModel.entry(key: String) = ConfigCatalog.entry(key, state.value.schema!!, state.value.values!!)!!

    @Test
    fun aChangeIsSavedAsAPatchOfJustThatKey() = runTest(dispatcher) {
        val saves = mutableListOf<String>()
        val vm = viewModel(saves)

        // What the gateway now holds, read back after the save.
        config = """{"approvals":{"mode":"smart"},"toolsets":["hermes-cli"],"logging":{"level":"INFO"}}"""
        vm.set(vm.entry("approvals.mode"), JsonPrimitive("smart"))

        assertEquals(listOf("""/api/config {"config":{"approvals":{"mode":"smart"}}}"""), saves)
        assertEquals(JsonPrimitive("smart"), vm.state.value.values!!.at("approvals.mode"))
        assertTrue(vm.state.value.saving.isEmpty())
    }

    @Test
    fun anUnchangedValueIsntSent() = runTest(dispatcher) {
        val saves = mutableListOf<String>()
        val vm = viewModel(saves)

        vm.set(vm.entry("approvals.mode"), JsonPrimitive("manual"))

        assertTrue(saves.isEmpty())
    }

    @Test
    fun aRefusedChangeIsPutBackWithTheReason() = runTest(dispatcher) {
        val vm = viewModel { respond("""{"detail":"Unknown approval mode"}""", HttpStatusCode.BadRequest, json) }

        vm.set(vm.entry("approvals.mode"), JsonPrimitive("off"))

        assertEquals(JsonPrimitive("manual"), vm.state.value.values!!.at("approvals.mode"))
        assertEquals(said(Res.string.sessions_config_save_failed, "Approval mode", detail = "Unknown approval mode"), vm.state.value.message)
    }

    @Test
    fun aRefusedFirstValueLeavesTheKeyUnset() = runTest(dispatcher) {
        config = """{"approvals":{"mode":"manual"},"toolsets":["hermes-cli"]}"""
        val vm = viewModel { respond("""{"detail":"No"}""", HttpStatusCode.BadRequest, json) }

        vm.set(vm.entry("logging.level"), JsonPrimitive("DEBUG"))

        // Not a null where nothing was.
        assertNull(vm.state.value.values!!.at("logging.level"))
    }

    @Test
    fun anOlderReadBackDoesntUndoALaterSave() = runTest(dispatcher) {
        val vm = viewModel()
        val slow = CompletableDeferred<Unit>()

        // The first save's read-back is slow and reads the file before the second save.
        gates += slow
        config = """{"approvals":{"mode":"smart"},"toolsets":["hermes-cli"],"logging":{"level":"INFO"}}"""
        vm.set(vm.entry("approvals.mode"), JsonPrimitive("smart"))
        config = """{"approvals":{"mode":"off"},"toolsets":["hermes-cli"],"logging":{"level":"INFO"}}"""
        vm.set(vm.entry("approvals.mode"), JsonPrimitive("off"))
        slow.complete(Unit)

        assertEquals(JsonPrimitive("off"), vm.state.value.values!!.at("approvals.mode"))
    }

    @Test
    fun aSlowReadBackDoesntUndoALaterRefresh() = runTest(dispatcher) {
        val vm = viewModel()
        val slow = CompletableDeferred<Unit>()

        // Opened again: a read-back starts and is slow; then Try again reads the changed file.
        gates += slow
        vm.bind(gateway, "rin")
        config = """{"approvals":{"mode":"off"},"toolsets":["hermes-cli"],"logging":{"level":"INFO"}}"""
        vm.refresh()
        slow.complete(Unit)

        assertEquals(JsonPrimitive("off"), vm.state.value.values!!.at("approvals.mode"))
    }

    @Test
    fun openingAgainAfterAFailedLoadTriesAgain() = runTest(dispatcher) {
        offline = true
        val vm = viewModel()
        assertNotNull(vm.state.value.error)

        offline = false
        vm.bind(gateway, "rin")

        assertNotNull(vm.state.value.values)
        assertNull(vm.state.value.error)
    }

    @Test
    fun aFailedRefreshOverAShownPageSaysSo() = runTest(dispatcher) {
        val vm = viewModel()

        offline = true
        vm.refresh()

        assertEquals(said(Res.string.sessions_config_refresh_failed, detail = "Can't reach the gateway: Gateway down"), vm.state.value.message)
        assertNotNull(vm.state.value.values)
    }

    @Test
    fun emptyingTheToolsetsWaitsForAYes() = runTest(dispatcher) {
        val saves = mutableListOf<String>()
        val vm = viewModel(saves)
        val toolsets = vm.entry("toolsets")

        vm.set(toolsets, JsonArray(emptyList()))
        assertNotNull(vm.state.value.confirm)
        assertTrue(saves.isEmpty())

        vm.cancelChange()
        assertNull(vm.state.value.confirm)
        assertTrue(saves.isEmpty())

        vm.set(toolsets, JsonArray(emptyList()))
        vm.confirmChange()
        assertEquals(listOf("""/api/config {"config":{"toolsets":[]}}"""), saves)
    }

    @Test
    fun backStepsUpThroughThePages() = runTest(dispatcher) {
        val vm = viewModel()
        val logging = ConfigCatalog.categories(vm.state.value.schema!!).first { it.category == "logging" }

        vm.open(ConfigPlace.AllSettings)
        vm.open(ConfigPlace.Section(logging))
        assertTrue(vm.back())
        assertEquals(ConfigPlace.AllSettings, vm.state.value.view)
        assertTrue(vm.back())
        assertEquals(ConfigPlace.Overview, vm.state.value.view)
        assertFalse(vm.back())

        vm.open(ConfigPlace.Section(ConfigCatalog.sections.first { it.id == "safety" }))
        assertTrue(vm.back())
        assertEquals(ConfigPlace.Overview, vm.state.value.view)
    }

    @Test
    fun theFileOpensAtTheTopAndSavesWhole() = runTest(dispatcher) {
        val saves = mutableListOf<String>()
        val vm = viewModel(saves)

        vm.open(ConfigPlace.Yaml)
        assertEquals("approvals:\n  mode: manual\n", vm.yaml.text.toString())
        assertEquals(0, vm.yaml.selection.start)
        assertEquals("/srv/.hermes/config.yaml", vm.state.value.yaml?.path)

        vm.yaml.edit { replace(0, length, "approvals:\n  mode: smart\n") }
        vm.saveYaml()

        assertEquals("""/api/config/raw {"yaml_text":"approvals:\n  mode: smart\n"}""", saves.single())
        assertEquals("approvals:\n  mode: smart\n", vm.state.value.yaml?.loaded)
        assertEquals(said(Res.string.sessions_config_yaml_saved), vm.state.value.message)
    }

    @Test
    fun leavingAChangedFileAsksFirst() = runTest(dispatcher) {
        val saves = mutableListOf<String>()
        val vm = viewModel(saves)
        vm.open(ConfigPlace.Yaml)

        vm.yaml.edit { append("# note\n") }
        assertTrue(vm.back())
        assertTrue(vm.state.value.confirmDiscard)
        assertEquals(ConfigPlace.Yaml, vm.state.value.view)

        vm.keepEditing()
        assertFalse(vm.state.value.confirmDiscard)
        assertEquals(ConfigPlace.Yaml, vm.state.value.view)

        vm.back()
        vm.discardYaml()
        assertEquals(ConfigPlace.Overview, vm.state.value.view)
        assertTrue(saves.isEmpty())

        // Unchanged, it just closes.
        vm.open(ConfigPlace.Yaml)
        assertTrue(vm.back())
        assertFalse(vm.state.value.confirmDiscard)
        assertEquals(ConfigPlace.Overview, vm.state.value.view)
    }

    @Test
    fun aRefusedFileKeepsTheTextAndSaysWhy() = runTest(dispatcher) {
        val vm = viewModel { respond("""{"detail":"YAML must be a mapping"}""", HttpStatusCode.BadRequest, json) }

        vm.open(ConfigPlace.Yaml)
        vm.yaml.edit { replace(0, length, "- a list") }
        vm.saveYaml()

        assertEquals("YAML must be a mapping", vm.state.value.yaml?.error)
        assertEquals("- a list", vm.yaml.text.toString())
        assertFalse(vm.state.value.yaml!!.saving)
    }

    @Test
    fun anotherProfileStartsClean() = runTest(dispatcher) {
        val vm = viewModel()
        vm.query.edit { replace(0, length, "approval") }
        vm.open(ConfigPlace.AllSettings)

        vm.bind(gateway, "mia")

        assertEquals(ConfigPlace.Overview, vm.state.value.view)
        assertEquals("", vm.query.text.toString())
        assertNotNull(vm.state.value.values)
    }
}
