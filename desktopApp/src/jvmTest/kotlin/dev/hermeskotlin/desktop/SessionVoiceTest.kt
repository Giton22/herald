package dev.hermeskotlin.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.sun.net.httpserver.HttpServer
import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.InMemoryBlobFile
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.NoSpeechPlayer
import dev.hermeskotlin.core.voice.NoVoiceRecorder
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.ui.App
import dev.hermeskotlin.ui.AppViewModel
import dev.hermeskotlin.ui.Route
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.di.sharedModules
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** Real App/Home routing with an in-memory account and a call that owns no audio or provider connection. */
@OptIn(ExperimentalTestApi::class)
class SessionVoiceTest {
    @Test fun signingOutClosesTheCall() = sessionEnds(relogin = true) { it.signOut() }

    @Test fun expirationClosesTheCall() = sessionEnds { it.onSessionExpired() }

    @Test fun changingProfileClosesTheCall() = sessionEnds(leavesHome = false) { it.switchProfile("other") }

    @Test fun closingTheRootClosesTheCall() = sessionEnds(disposeRoot = true) {}

    @Test fun addingAGatewayKeepsTheCall() = sessionEnds(adding = true) {}

    private fun sessionEnds(
        leavesHome: Boolean = true,
        disposeRoot: Boolean = false,
        relogin: Boolean = false,
        adding: Boolean = false,
        leave: (AppViewModel) -> Unit,
    ) = runComposeUiTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val status = exchange.requestURI.path == "/api/audio/voice-live/status"
                val body = if (status) """{"mode":"gpt-live","available":true}""" else "{}"
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (status) 200 else 503, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        val gateway = SavedGateway("http://127.0.0.1:${server.address.port}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Channel<OwnedCall>(Channel.UNLIMITED)
        val koin = startKoin {
            modules(sharedModules + desktopModule + module {
                single<KeyValueStore> { InMemoryKeyValueStore() }
                single { scope }
                single { OfflineCache(InMemoryOfflineDao(), PlainSealer) { 0L } }
                single { WallpaperStore(InMemoryBlobFile(), scope) }
                single<VoiceRecorder> { NoVoiceRecorder }
                single<SpeechPlayer> { NoSpeechPlayer }
                single { LiveCalls { OwnedCall().also { calls.trySend(it) } } }
            })
        }.koin
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner, LifecycleOwner {
            override val viewModelStore = store
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        lateinit var app: AppViewModel
        lateinit var chat: ChatViewModel
        var initialized = false
        var shown by mutableStateOf(true)
        // The gateways whose chat windows App said to close.
        val ended = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides owner) {
                    app = koinViewModel()
                    chat = koinViewModel()
                    initialized = true
                    if (shown) App(onSessionEnded = { ended += it })
                }
            }
            waitUntil(timeoutMillis = 10_000) { initialized && app.route.value !is Route.Loading }
            runOnIdle { app.onSignedIn(gateway) }
            waitUntil(timeoutMillis = 10_000) { app.route.value is Route.Chat && koin.get<ChatHost>().session.value != null }
            runOnIdle { chat.startVoiceChat() }
            var call: OwnedCall? = null
            waitUntil(timeoutMillis = 10_000) {
                if (call == null) call = calls.tryReceive().getOrNull()
                call?.job != null
            }
            val owned = checkNotNull(call)
            assertTrue(checkNotNull(owned.job).isActive)
            // A background transition keeps a held desktop call. It does not dispose authenticated Home.
            runOnIdle { chat.voice.onBackground() }
            waitForIdle()
            assertEquals(0, owned.closes.get())
            assertTrue(checkNotNull(owned.job).isActive)
            runOnIdle { koin.get<SettingsStore>().update { it.copy(language = "de") } }
            waitUntil(timeoutMillis = 10_000) { koin.get<SettingsStore>().settings.value?.language == "de" }
            waitForIdle()
            assertEquals(0, owned.closes.get(), "Changing language must retain the authenticated call")
            assertTrue(checkNotNull(owned.job).isActive)
            if (adding) {
                // Adding a gateway, up to its sign-in: the current one stays signed in throughout.
                runOnIdle { app.addGateway() }
                waitUntil(timeoutMillis = 10_000) { (app.route.value as? Route.Connect)?.canCancel == true }
                waitForIdle()
                runOnIdle { app.onGatewayChosen(SavedGateway("http://127.0.0.1:1")) }
                waitUntil(timeoutMillis = 10_000) { (app.route.value as? Route.SignIn)?.adding == true }
                waitForIdle()
                assertEquals(0, owned.closes.get(), "Adding a gateway must retain the authenticated call")
                assertTrue(checkNotNull(owned.job).isActive)
                assertEquals(emptyList(), ended.toList())
                return@runComposeUiTest
            }
            if (leavesHome) {
                runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
                waitForIdle()
                assertEquals(0, owned.closes.get(), "A hidden window must retain the authenticated call")
            }
            if (leavesHome && !disposeRoot) mainClock.autoAdvance = false
            runOnIdle { leave(app); if (disposeRoot) shown = false }
            if (leavesHome && !disposeRoot) {
                waitUntil(timeoutMillis = 10_000) { app.route.value is Route.SignIn }
                assertIs<Route.SignIn>(app.route.value)
            }
            waitUntil(timeoutMillis = 10_000) { owned.closes.get() > 0 && checkNotNull(owned.job).isCompleted }
            mainClock.autoAdvance = true
            assertEquals(1, owned.closes.get())
            // Signed out or expired, the gateway's chat windows close; another profile of it keeps them.
            if (leavesHome && !disposeRoot) assertEquals(listOf(gateway.url), ended.toList())
            else assertEquals(emptyList(), ended.toList())
            // Repeated session retirement and eventual root disposal must not close the old call twice.
            runOnIdle { app.onSessionExpired(); shown = false }
            waitForIdle()
            assertEquals(1, owned.closes.get())
            if (relogin) {
                runOnIdle { shown = true; app.onSignedIn(gateway) }
                waitUntil(timeoutMillis = 10_000) { app.route.value is Route.Chat && koin.get<ChatHost>().session.value != null }
                runOnIdle { chat.startVoiceChat() }
                var next: OwnedCall? = null
                waitUntil(timeoutMillis = 10_000) {
                    if (next == null) next = calls.tryReceive().getOrNull()
                    next?.job?.isActive == true
                }
                waitForIdle()
                assertEquals(0, checkNotNull(next).closes.get(), "Retiring the previous Home must not close the new call")
                assertEquals(1, owned.closes.get())
            }
        } finally {
            mainClock.autoAdvance = true
            try {
                runOnIdle {
                    if (initialized) chat.voice.stopAll()
                    shown = false
                    store.clear()
                }
            } finally {
                koin.get<ChatHost>().close()
                scope.cancel()
                stopKoin()
                server.stop(0)
            }
        }
    }

    private class OwnedCall : LiveCall {
        override val events = Channel<String>(Channel.UNLIMITED)
        val closes = AtomicInteger()
        @Volatile var job: Job? = null
        override suspend fun connect(answer: suspend (String) -> String) {
            job = currentCoroutineContext().job
            events.send("""{"type":"session.started"}""")
        }
        override fun send(event: String) = true
        override fun speaking() = false
        override fun micLevel() = 0f
        override fun setMuted(muted: Boolean) = Unit
        override fun close() {
            closes.incrementAndGet()
            events.cancel()
        }
    }
}
