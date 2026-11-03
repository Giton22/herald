package dev.hermeskotlin.android.phone

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidPhoneToolsTest {
    @Test fun readCallsNeedNoApprovalEvenWithTrueStoredOverrides() = withRunner { runner, _ ->
        // Use the actual dispatcher, including reads in tools which also have write actions.
        val reads = listOf(
            PhoneTool.Battery to emptyMap(),
            PhoneTool.Network to emptyMap(),
            PhoneTool.Sound to mapOf("action" to JsonPrimitive("get")),
            PhoneTool.Calendar to mapOf("action" to JsonPrimitive("list")),
            PhoneTool.Geofences to mapOf("action" to JsonPrimitive("list")),
        )
        for ((tool, args) in reads) {
            withTimeout(2_000) { runner.run(tool, JsonObject(args), "") }
            assertTrue(PhoneConfirmations.asking.value.isEmpty(), tool.id)
        }
    }

    @Test fun readsStillRefuseWhenParentOrMasterIsOff() = withRunner { runner, settings ->
        for (master in listOf(false, true)) {
            settings.update { it.copy(phoneTools = master, phoneToolsOn = if (master) emptyList() else listOf(PhoneTool.Network.id)) }
            assertFailsWith<PhoneToolException> { runner.run(PhoneTool.Network, JsonObject(emptyMap()), "") }
        }
        assertTrue(PhoneConfirmations.asking.value.isEmpty())
    }

    private fun withRunner(check: suspend (AndroidPhoneTools, SettingsStore) -> Unit) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { error("A phone read must not contact a gateway") }, cookies)
        val application = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(application) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? =
                if (receiver == null && filter?.hasAction(Intent.ACTION_BATTERY_CHANGED) == true) {
                    Intent(Intent.ACTION_BATTERY_CHANGED)
                        .putExtra(BatteryManager.EXTRA_LEVEL, 50).putExtra(BatteryManager.EXTRA_SCALE, 100)
                } else super.registerReceiver(receiver, filter)
        }
        try {
            val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("Gateway must stay unopened") }, scope)
            val settings = SettingsStore(InMemoryKeyValueStore(), scope)
            settings.settings.filterNotNull().first()
            settings.update { it.copy(phoneTools = true, phoneToolsOn = PhoneTool.entries.map { t -> t.id },
                phoneToolsAsk = PhoneTool.entries.associate { t -> t.id to true }) }
            val host = ChatHost(connection, SessionsApi(client), scope)
            val active = ActiveSessions(connection, scope)
            val watcher = SessionWatcher(connection, host, active, AttentionTracker(connection, host, active, scope), scope)
            val chats = ChatTargets(host, watcher, GatewayRepository(InMemoryKeyValueStore()), ProfileStore(InMemoryKeyValueStore()))
            val confirmations = PhoneConfirmations(context, settings)
            val runner = AndroidPhoneTools(context, confirmations, Geofences(context, settings, scope), chats,
                ChatNotifications(context), AgentScreens())
            shadowOf(application).grantPermissions(Manifest.permission.READ_CALENDAR)
            PhoneConfirmations.inApp.value = false
            shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
            check(runner, settings)
        } finally {
            scope.cancel()
            client.close()
            PhoneConfirmations.inApp.value = false
            PhoneConfirmations.asking.value = emptyList()
            PhoneConfirmations.waiting.clear()
            shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
            Dispatchers.resetMain()
        }
    }
}
