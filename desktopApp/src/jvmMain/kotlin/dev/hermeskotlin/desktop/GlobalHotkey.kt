package dev.hermeskotlin.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Ctrl+Shift+Space from any app, as Hermes Desktop's quick entry: Windows' own hotkey, on a thread that waits for
 * it. Elsewhere there's none yet, and [register] says so.
 */
class GlobalHotkey private constructor(private val threadId: Int, private val waiter: Thread) {

    /** Lets go of the hotkey, waiting briefly for it to be free, so registering it again right after works. */
    fun unregister() {
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        waiter.join(UNREGISTER_WAIT_MS)
    }

    companion object {
        private const val ID = 1
        private const val MOD_CONTROL = 0x0002
        private const val MOD_SHIFT = 0x0004
        private const val MOD_NOREPEAT = 0x4000
        private const val VK_SPACE = 0x20
        private const val UNREGISTER_WAIT_MS = 500L

        /**
         * Calls [onPress] (on the hotkey's thread) for each Ctrl+Shift+Space; null where the system has no such
         * hotkey for the app, or another app holds this one.
         */
        fun register(onPress: () -> Unit): GlobalHotkey? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
            var registered = false
            var threadId = 0
            val ready = CountDownLatch(1)
            val waiter = thread(name = "herald-hotkey", isDaemon = true) {
                threadId = Kernel32.INSTANCE.GetCurrentThreadId()
                registered = User32.INSTANCE.RegisterHotKey(null, ID, MOD_CONTROL or MOD_SHIFT or MOD_NOREPEAT, VK_SPACE)
                ready.countDown()
                if (!registered) return@thread
                val msg = WinUser.MSG()
                // GetMessage answers 0 on WM_QUIT (unregister) and -1 on a failure; either ends the wait.
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WinUser.WM_HOTKEY && msg.wParam.toInt() == ID) onPress()
                }
                User32.INSTANCE.UnregisterHotKey(null, ID)
            }
            ready.await()
            return if (registered) GlobalHotkey(threadId, waiter) else null
        }
    }
}
