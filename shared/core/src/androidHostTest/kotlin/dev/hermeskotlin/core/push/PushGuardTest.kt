package dev.hermeskotlin.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushGuardTest {

    private var clock = 1_000_000L
    private fun guard() = PushGuard(PushMessage.MAX_AGE_G2P_SECONDS) { clock }
    private fun message(id: String, ts: Long = clock) = PushMessage(id = id, ts = ts, type = PushMessage.PING)

    @Test
    fun acceptsAMessageOnce() {
        val guard = guard()
        assertTrue(guard.accept(message("a")))
        assertFalse(guard.accept(message("a")))
        assertTrue(guard.accept(message("b")))
    }

    @Test
    fun refusesStaleFutureAndBlank() {
        val guard = guard()
        assertFalse(guard.accept(message("old", clock - PushMessage.MAX_AGE_G2P_SECONDS - 1)))
        assertFalse(guard.accept(message("future", clock + PushMessage.MAX_SKEW_SECONDS + 1)))
        assertTrue(guard.accept(message("skewed", clock + PushMessage.MAX_SKEW_SECONDS)))
        assertFalse(guard.accept(message("")))
    }

    @Test
    fun remembersAcrossRestarts() {
        val first = guard()
        first.accept(message("a"))
        val saved = first.snapshot()
        val second = guard().apply { remember(saved) }
        assertFalse(second.accept(message("a")))
    }

    @Test
    fun snapshotForgetsIdsOutsideTheWindow() {
        val guard = guard()
        guard.accept(message("a"))
        clock += PushMessage.MAX_AGE_G2P_SECONDS + PushMessage.MAX_SKEW_SECONDS + 1
        assertEquals(emptyMap(), guard.snapshot())
    }

    @Test
    fun serversMustBeHttps() {
        assertTrue(NtfyClient.validServer("https://ntfy.sh"))
        assertTrue(NtfyClient.validServer("https://push.example.org:8443/"))
        assertFalse(NtfyClient.validServer("http://ntfy.sh"))
        assertFalse(NtfyClient.validServer("https://user@evil/x"))
        assertFalse(NtfyClient.validServer("https://ntfy.sh/path"))
    }
}
