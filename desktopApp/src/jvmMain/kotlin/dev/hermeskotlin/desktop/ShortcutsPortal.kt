package dev.hermeskotlin.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
internal interface ShortcutsPortal : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun BindShortcuts(session: DBusPath, shortcuts: List<Shortcut>, parent: String, options: Map<String, Variant<*>>): DBusPath

    class Shortcut(@field:Position(0) val id: String, @field:Position(1) val options: Map<String, Variant<*>>) : Struct()

    class Activated(path: String, val session: DBusPath, val id: String, val timestamp: UInt64, val options: Map<String, Variant<*>>) :
        DBusSignal(path, session, id, timestamp, options)
    class Deactivated(path: String, val session: DBusPath, val id: String, val timestamp: UInt64, val options: Map<String, Variant<*>>) :
        DBusSignal(path, session, id, timestamp, options)
    /** The user changed the keys in the desktop's own settings. */
    class ShortcutsChanged(path: String, val session: DBusPath, val shortcuts: List<Shortcut>) : DBusSignal(path, session, shortcuts)

    @DBusInterfaceName("org.freedesktop.portal.Request")
    interface Request : DBusInterface {
        fun Close()
        class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
    }

    @DBusInterfaceName("org.freedesktop.portal.Session")
    interface Session : DBusInterface {
        fun Close()
        class Closed(path: String, val details: Map<String, Variant<*>>) : DBusSignal(path, details)
    }

    /** Names a host (unsandboxed) app to the portal; without it, newer portals refuse every request. */
    @DBusInterfaceName("org.freedesktop.host.portal.Registry")
    interface HostRegistry : DBusInterface {
        fun Register(appId: String, options: Map<String, Variant<*>>)
    }
}
