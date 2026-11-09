package dev.hermeskotlin.desktop

import com.sun.jna.platform.unix.X11
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Protocol data only: these tests do not open a display or create a native window. */
class LinuxWindowActivationTest {
    @Test fun requestsTheOwnedWindowAsAnApplicationWithoutInventingUserTime() {
        // JNA's X11 event structure loads libX11, which only Linux has; the request is only sent there.
        assumeTrue("X11 events need libX11", System.getProperty("os.name").startsWith("Linux"))
        val event = LinuxWindowActivation.eventFor(42, X11.Atom(73))
        event.write()
        event.read()
        val request = event.xclient
        assertEquals(X11.ClientMessage, request.type)
        assertEquals(42L, request.window.toLong())
        assertEquals(73L, request.message_type.toLong())
        assertEquals(32, request.format)
        // Keep the application source so the manager retains its application-focus policy.
        assertEquals(listOf(1L, 0L, 0L, 0L, 0L), request.data.l.map { it.toLong() })
    }

    @Test fun rejectsAnUnavailableNativeHandle() {
        assertFailsWith<IllegalArgumentException> { LinuxWindowActivation.eventFor(0, X11.Atom(73)) }
    }
}
