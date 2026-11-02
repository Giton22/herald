package dev.hermeskotlin.desktop.web

import com.jetbrains.cef.JCefAppConfig
import dev.hermeskotlin.core.storage.appDataDir
import kotlinx.coroutines.future.await
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.callback.CefCommandLine
import org.cef.handler.CefAppHandlerAdapter
import org.cef.network.CefCookieManager
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Chromium the JetBrains Runtime carries (JCEF), started the first time a widget or a plugin page shows and
 * then shared by all of them: its processes cost memory, which a user who never opens one shouldn't pay.
 *
 * It reaches only the gateways saved when it started. Everything else goes to a proxy that isn't there: a widget's
 * requests are refused before that, but WebRTC goes around those checks, and the proxy is what stops it (JCEF can't
 * give widgets a proxy of their own). A plugin page on a gateway saved later opens in the system's browser instead.
 */
internal object Jcef {
    /** Whether the runtime has it: a JCEF build of JetBrains Runtime (the installer's), not any other JDK. */
    val available: Boolean = ModuleLayer.boot().findModule("jcef").isPresent

    @Volatile private var started: CompletableFuture<CefApp?>? = null

    /** The `host:port` of each gateway Chromium may reach; fixed when it starts. */
    @Volatile var reachable: Set<String> = emptySet()
        private set

    /**
     * The running Chromium, started on the first call with [gateways] as the ones it may reach; null when it couldn't
     * start. Each caller waits on a copy: one that goes away (a widget scrolled off while Chromium starts) would
     * otherwise cancel it for all.
     */
    suspend fun app(gateways: () -> List<String>): CefApp? {
        if (!available) return null
        val future = started ?: synchronized(this) {
            started ?: run {
                val hosts = gateways().mapNotNull(::hostPort).toSet()
                reachable = hosts
                CompletableFuture.supplyAsync {
                    runCatching { start(hosts) }.onFailure { System.err.println("Chromium didn't start: $it") }.getOrNull()
                }.also { started = it }
            }
        }
        return future.copy().await()
    }

    /** Whether a page at [url] can load here: on a gateway Chromium was started with. */
    fun reaches(url: String): Boolean = hostPort(url) in reachable

    /**
     * Closes Chromium, if it ever started, before the app quits: its helper processes would otherwise outlive the
     * app (holding its files while an update replaces them), and a page's session could stay in the jar on disk.
     */
    fun shutdown() {
        val app = started?.takeIf { it.isDone }?.getNow(null) ?: return
        runCatching {
            CefCookieManager.getGlobalManager().apply {
                deleteCookies("", "")
                val flushed = CountDownLatch(1)
                if (flushStore { flushed.countDown() }) flushed.await(2, TimeUnit.SECONDS)
            }
            app.dispose()
        }
    }

    private fun start(gateways: Set<String>): CefApp {
        val config = JCefAppConfig.getInstance()
        val args = config.appArgs
        val settings = config.cefSettings.apply {
            // Each view is drawn by Compose from the frames Chromium hands over, so menus and tooltips stay on top.
            windowless_rendering_enabled = true
            cache_path = File(appDataDir, "browser").path
            log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE
        }
        CefApp.addAppHandler(object : CefAppHandlerAdapter(args) {
            override fun onBeforeCommandLineProcessing(processType: String?, commandLine: CefCommandLine) {
                // WebRTC may only go through a proxy, and the proxy leads nowhere: past it, only the gateways. Loopback
                // isn't let past on its own (`<-loopback>`), only a gateway's own port on it.
                commandLine.appendSwitchWithValue("force-webrtc-ip-handling-policy", "disable_non_proxied_udp")
                commandLine.appendSwitchWithValue("proxy-server", NOWHERE)
                commandLine.appendSwitchWithValue("proxy-bypass-list", (gateways + "<-loopback>").joinToString(";"))
                // No Google sign-in, sync, translation or spelling service: nothing here talks to Google.
                commandLine.appendSwitch("disable-sync")
                commandLine.appendSwitch("disable-component-update")
                commandLine.appendSwitchWithValue("disable-features", "Translate,SpellcheckService")
            }
        })
        check(CefApp.startup(args)) { "JCEF's native part didn't load" }
        // The app comes back before Chromium is ready, and its cookie jar throws until then; a page lends the
        // session into that jar before it loads.
        val app = CefApp.getInstance(args, settings, null)
        val ready = CountDownLatch(1)
        app.onInitialization { state -> if (state == CefApp.CefAppState.INITIALIZED) ready.countDown() }
        check(ready.await(START_TIMEOUT_S, TimeUnit.SECONDS)) { "Chromium didn't get ready in ${START_TIMEOUT_S}s" }
        return app
    }
}

/** `host:port` of [url], the port filled in from its scheme; null for anything but http(s). */
internal fun hostPort(url: String): String? {
    val uri = parseUrl(url) ?: return null
    val host = uri.host?.lowercase() ?: return null
    val default = when (uri.scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> return null
    }
    return "$host:${uri.port.takeIf { it != -1 } ?: default}"
}

/**
 * [url] as a [URI], or, where Chromium leaves characters `URI` refuses (`{`, `|` and the like in a query), its
 * scheme, host and path alone; null when even that isn't one.
 */
internal fun parseUrl(url: String): URI? =
    runCatching { URI(url) }.getOrNull() ?: runCatching { URI(url.substringBefore('#').substringBefore('?')) }.getOrNull()

/** A port nothing listens on: Chromium's proxy, so what isn't a gateway goes nowhere. */
private const val NOWHERE = "127.0.0.1:9"

private const val START_TIMEOUT_S = 30L
