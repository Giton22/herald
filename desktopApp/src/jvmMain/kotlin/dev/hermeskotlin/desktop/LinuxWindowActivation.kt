package dev.hermeskotlin.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import java.awt.Toolkit
import java.awt.Window

/** A user launch asks the X11 window manager to activate this window; the manager may refuse. */
internal object LinuxWindowActivation {
    fun request(window: Window) {
        if (!System.getProperty("os.name").startsWith("Linux") || !window.isShowing) return
        // A missing X11 display/library must not prevent showing the window on another desktop/backend.
        runCatching {
            // AWT maps the popup through another connection; flush that before sending its activation request.
            Toolkit.getDefaultToolkit().sync()
            val x11 = X11.INSTANCE
            val display = x11.XOpenDisplay(null) ?: return
            try {
                // Intern normally: JNA's true Bool produces an invalid atom on this Xlib path.
                val atom = x11.XInternAtom(display, "_NET_ACTIVE_WINDOW", false)
                if (atom == null || atom.toLong() == 0L) return
                val event = eventFor(Native.getWindowID(window), atom)
                event.xclient.display = display
                x11.XSendEvent(display, x11.XDefaultRootWindow(display), 0,
                    NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong()), event)
                x11.XFlush(display)
            } finally {
                x11.XCloseDisplay(display)
            }
        }
    }

    internal fun eventFor(window: Long, atom: X11.Atom): X11.XEvent {
        require(window > 0)
        return X11.XEvent().apply {
            setType(X11.XClientMessageEvent::class.java)
            xclient.type = X11.ClientMessage
            xclient.window = X11.Window(window)
            xclient.message_type = atom
            xclient.format = 32
            xclient.data.setType("l")
            // Application, not pager; zero lets the manager use this window's own user time.
            xclient.data.l = Array(5) { NativeLong(0) }.also { it[0] = NativeLong(1) }
        }
    }
}
