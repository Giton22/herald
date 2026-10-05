package dev.hermeskotlin.android.push

import android.util.Log
import dev.hermeskotlin.android.notify.AppVisibility
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotMeta
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.NtfyClient
import dev.hermeskotlin.core.push.NtfyEvent
import dev.hermeskotlin.core.push.PushCrypto
import dev.hermeskotlin.core.push.PushDirection
import dev.hermeskotlin.core.push.PushGuard
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Listens on this phone's ntfy topic while "Notifications anywhere" is on, and turns what the gateway's
 * herald-push plugin sends into the same bot notifications the direct connection posts. Works off the
 * gateway's network: the topic is reached over the public internet. Nothing is shown unless its signature
 * verifies against a gateway key pinned at registration, it decrypts, and it is fresh and new.
 */
class PushListener(
    private val store: PushStore,
    private val ntfy: NtfyClient,
    private val settings: SettingsStore,
    private val connection: GatewayConnection,
    private val visibility: AppVisibility,
    private val notifications: ChatNotifications,
    private val bots: BotNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val guard = PushGuard(PushMessage.MAX_AGE_G2P_SECONDS) { System.currentTimeMillis() / 1000 }

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) { guard.remember(store.seen) }
            combine(settings.settings, store.changes) { prefs, _ -> prefs?.pushAnywhere == true && store.gateways().isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { listen -> if (listen) listen() }
        }
    }

    private suspend fun listen() {
        var failures = 0
        while (true) {
            val device = withContext(Dispatchers.IO) { store.device() }
            val started = System.currentTimeMillis()
            try {
                ntfy.subscribe(device.server, device.pushTopic, store.since).collect { event -> receive(device, event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ntfy stream ended: ${e::class.simpleName}")
            }
            // A stream that lived a while was fine; one that keeps dying backs off.
            failures = if (System.currentTimeMillis() - started > HEALTHY_MS) 0 else failures + 1
            delay(BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.lastIndex)])
        }
    }

    private suspend fun receive(device: PushStore.Device, event: NtfyEvent) {
        val message = withContext(Dispatchers.Default) { open(device, event) }
        store.since = event.id
        if (message == null || !guard.accept(message)) return
        store.seen = guard.snapshot()
        when (message.type) {
            PushMessage.BOT_MESSAGE -> showBotMessage(message)
            PushMessage.PING -> notifications.postPushTest()
        }
    }

    /** The message inside [event], or null for anything that isn't a valid envelope from a pinned gateway. */
    private fun open(device: PushStore.Device, event: NtfyEvent): PushMessage? {
        val body = event.message?.takeIf { it.length <= NtfyClient.MAX_BODY } ?: return null
        val envelope = runCatching { PushCrypto.decodeEnvelope(body) }.getOrNull() ?: return null
        for (gateway in store.gateways().values) {
            val signer = runCatching { PushCrypto.decodePublic(gateway.sigPub) }.getOrNull() ?: continue
            val plain = runCatching {
                PushCrypto.open(envelope, device.pushTopic, PushDirection.GatewayToPhone, device.enc.private, signer)
            }.getOrNull() ?: continue
            return runCatching { HermesJson.decodeFromString(PushMessage.serializer(), plain.decodeToString()) }.getOrNull()
        }
        return null
    }

    private suspend fun showBotMessage(message: PushMessage) {
        val name = message.bot?.takeIf { BOT_NAME.matches(it) } ?: return
        val session = message.session?.takeIf { SESSION_ID.matches(it) } ?: return
        val text = message.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val prefs = settings.settings.first { it != null } ?: return
        if (!prefs.notifyReplies) return
        // In sight and connected, the app shows it already.
        if (visibility.visible.value && connection.state.value is ConnectionState.Connected) return
        val bot = bots.knownBot(name) ?: fallbackBot(name, message.label)
        if (bot.meta.hidden) return
        notifications.postBotMessage(bot, bots.picture(bot), session, text)
    }

    private fun fallbackBot(name: String, label: String?): Bot {
        val title = label?.filter { !it.isISOControl() }?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
        return Bot(name = name, uiMeta = title?.let { JsonObject(mapOf(BotMeta.KEY to JsonObject(mapOf("title" to JsonPrimitive(it))))) })
    }

    private companion object {
        const val TAG = "PushListener"
        const val HEALTHY_MS = 60_000L
        const val MAX_LABEL = 64
        val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
        val BOT_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        val SESSION_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    }
}
