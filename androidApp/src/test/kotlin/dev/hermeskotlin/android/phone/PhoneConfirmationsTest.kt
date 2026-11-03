package dev.hermeskotlin.android.phone

import android.app.Activity
import android.app.KeyguardManager
import android.os.Looper
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneConfirmationsTest {
    private val writeTools = listOf(PhoneTool.Sound, PhoneTool.Alarms, PhoneTool.Open, PhoneTool.Clipboard, PhoneTool.Geofences, PhoneTool.Calendar)

    @After fun reset() {
        PhoneConfirmations.asking.value = emptyList()
        PhoneConfirmations.waiting.clear()
        PhoneConfirmations.inApp.value = false
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.fixture(ask: Boolean = true): Pair<SettingsStore, PhoneConfirmations> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        settings.settings.filterNotNull().first()
        settings.update {
            it.copy(phoneTools = true, phoneToolsOn = PhoneTool.entries.map { tool -> tool.id },
                phoneToolsAsk = PhoneTool.entries.associate { tool -> tool.id to ask })
        }
        PhoneConfirmations.inApp.value = true
        return settings to PhoneConfirmations(org.robolectric.RuntimeEnvironment.getApplication(), settings)
    }

    @Test fun optOutRunsExistingWriteToolsWithoutQuestion() = runTest {
        val (_, confirmations) = fixture(ask = false)
        var runs = 0
        for (tool in writeTools) confirmations.confirm(tool, "Use ${tool.title}") { runs++ }
        assertEquals(writeTools.size, runs)
        assertTrue(PhoneConfirmations.asking.value.isEmpty())
    }

    @Test fun optInWaitsForAllowAndRunsOnlyOnce() = runTest {
        val (_, confirmations) = fixture()
        var runs = 0
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (tool in writeTools) {
            val result = async { confirmations.confirm(tool, "Use ${tool.title}") { runs++ } }
            runCurrent()
            val before = runs
            val question = PhoneConfirmations.asking.value.single()
            assertEquals("Use ${tool.title}", question.what)
            assertTrue(PhoneConfirmations.allow(question.key, activity))
            assertFalse(PhoneConfirmations.allow(question.key, activity))
            result.await()
            assertEquals(before + 1, runs)
            assertTrue(PhoneConfirmations.asking.value.isEmpty())
        }
        assertEquals(writeTools.size, runs)
    }

    @Test fun denialAndCancellationNeverExecute() = runTest {
        val (_, confirmations) = fixture()
        var runs = 0
        val denied = async { runCatching { confirmations.confirm(PhoneTool.Clipboard, "Copy") { runs++ } } }
        runCurrent()
        PhoneConfirmations.deny(PhoneConfirmations.asking.value.single().key)
        assertTrue(denied.await().exceptionOrNull() is PhoneToolException)
        val cancelled = async { confirmations.confirm(PhoneTool.Clipboard, "Copy") { runs++ } }
        runCurrent()
        val key = PhoneConfirmations.asking.value.single().key
        cancelled.cancelAndJoin()
        assertFalse(PhoneConfirmations.allow(key, Robolectric.buildActivity(Activity::class.java).setup().get()))
        assertEquals(0, runs)
    }

    @Test fun disabledParentRefusesEvenWhenPermissionIsOff() = runTest {
        val (settings, confirmations) = fixture(ask = false)
        for (master in listOf(false, true)) {
            settings.update { it.copy(phoneTools = master, phoneToolsOn = if (master) emptyList() else listOf(PhoneTool.Clipboard.id)) }
            assertFailsWith<PhoneToolException> {
                confirmations.confirm(PhoneTool.Clipboard, "Copy") { error("Must not execute") }
            }
        }
    }

    @Test fun disablingWhileQuestionWaitsRefusesAtAllow() = runTest {
        val (settings, confirmations) = fixture()
        var runs = 0
        val result = async { runCatching { confirmations.confirm(PhoneTool.Calendar, "Add event") { runs++ } } }
        runCurrent()
        settings.update { it.copy(phoneTools = false) }
        PhoneConfirmations.allow(PhoneConfirmations.asking.value.single().key, Robolectric.buildActivity(Activity::class.java).setup().get())
        assertTrue(result.await().exceptionOrNull() is PhoneToolException)
        assertEquals(0, runs)
    }

    @Test fun optOutPreservesPlatformFailuresAndDoesNotLaunchFromBackground() = runTest {
        val (_, confirmations) = fixture(ask = false)
        assertFailsWith<SecurityException> {
            confirmations.confirm(PhoneTool.Clipboard, "Copy") { throw SecurityException("Android refused") }
        }
        PhoneConfirmations.inApp.value = false
        assertFailsWith<PhoneToolException> {
            confirmations.confirm(PhoneTool.Open, "Open", needsActivity = true) { error("Must not launch") }
        }
    }

    @Test fun changingPermissionWhileQueuedOrWaitingDoesNotBypassApproval() = runTest {
        val (settings, confirmations) = fixture(ask = false)
        var runs = 0
        val result = async { confirmations.confirm(PhoneTool.Clipboard, "Copy") { runs++ } }
        settings.update { it.copy(phoneToolsAsk = mapOf(PhoneTool.Clipboard.id to true)) }
        runCurrent()
        assertEquals(0, runs)
        val key = PhoneConfirmations.asking.value.single().key
        settings.update { it.copy(phoneToolsAsk = mapOf(PhoneTool.Clipboard.id to false)) }
        runCurrent()
        assertEquals(0, runs)
        PhoneConfirmations.allow(key, Robolectric.buildActivity(Activity::class.java).setup().get())
        result.await()
        assertEquals(1, runs)
    }

    @Test fun optOutDoesNotBypassTheDeviceLockAndRunsOnMain() = runTest {
        val (_, confirmations) = fixture(ask = false)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        assertFailsWith<PhoneToolException> {
            confirmations.confirm(PhoneTool.Clipboard, "Copy") { error("Must not execute") }
        }
        keyguard.setIsDeviceLocked(false)
        confirmations.confirm(PhoneTool.Clipboard, "Copy") {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
        }
    }

    @Test fun approvedPlatformFailureAndTimeoutNeverReportSuccess() = runTest {
        val (_, confirmations) = fixture()
        val failed = async { runCatching {
            confirmations.confirm(PhoneTool.Calendar, "Add event") { throw SecurityException("Android refused") }
        } }
        runCurrent()
        PhoneConfirmations.allow(PhoneConfirmations.asking.value.single().key, Robolectric.buildActivity(Activity::class.java).setup().get())
        assertEquals("Android refused", failed.await().exceptionOrNull()?.message)
        var runs = 0
        val timedOut = async { runCatching { confirmations.confirm(PhoneTool.Calendar, "Add event") { runs++ } } }
        runCurrent()
        advanceTimeBy(110_001)
        runCurrent()
        assertTrue(timedOut.await().exceptionOrNull() is PhoneToolException)
        assertEquals(0, runs)
        assertTrue(PhoneConfirmations.asking.value.isEmpty())
    }

}
