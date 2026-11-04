package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.settings.AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionHistoryTest {
    @Test fun incomingChecksAreBooleanOnlyAndEveryGuardIsRequired() {
        val checks = dev.hermeskotlin.core.chat.CallRequestChecks(true, true, true, true, true, true, true)
        assertTrue(checks.valid)
        listOf(checks.copy(connectionGatewayMatches = false), checks.copy(originGatewayMatches = false),
            checks.copy(profileMatches = false), checks.copy(connected = false), checks.copy(storedMatches = false),
            checks.copy(runtimeMatches = false), checks.copy(requestExactlyOnce = false)).forEach {
            assertFalse(it.valid)
        }

    }

    @Test fun subscriptionVoiceIsOffByDefault() {
        assertFalse(AppSettings().subscriptionVoicePreview)
    }

    @Test fun waitsForGatewayNotSavedCopyAndAcceptsEmpty() = runTest {
        val states = MutableStateFlow(ChatState(storedSessionId = "existing"))
        val result = async { subscriptionHistory(states) }
        runCurrent()
        assertFalse(result.isCompleted)
        states.value = states.value.copy(historyLoaded = true, savedCopyAt = 1)
        runCurrent()
        assertFalse(result.isCompleted)
        states.value = states.value.copy(savedCopyAt = null)
        assertEquals(emptyList(), assertNotNull(result.await()).messages)
    }

    @Test fun refusesFailedOrTimedOutHistory() = runTest {
        val failed = MutableStateFlow(ChatState(historyLoaded = true, historyError = "offline", savedCopyAt = 1))
        assertNull(subscriptionHistory(failed))
        val pending = MutableStateFlow(ChatState(storedSessionId = "existing"))
        assertNull(subscriptionHistory(pending, timeoutMs = 100))
    }

}
