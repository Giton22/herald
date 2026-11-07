package dev.hermeskotlin.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.Flatpak")
internal interface FlatpakPortalWire : DBusInterface {
    fun CreateUpdateMonitor(options: Map<String, Variant<*>>): DBusPath
    fun Spawn(cwd: ByteArray, argv: List<ByteArray>, fds: Map<UInt32, FileDescriptor>, env: Map<String, String>,
        flags: UInt32, options: Map<String, Variant<*>>): UInt32
}

@DBusInterfaceName("org.freedesktop.portal.Flatpak.UpdateMonitor")
internal interface FlatpakMonitorWire : DBusInterface {
    fun Update(parent: String, options: Map<String, Variant<*>>)
    fun Close()
    class UpdateAvailable(path: String, val info: Map<String, Variant<*>>) : DBusSignal(path, info)
    class Progress(path: String, val info: Map<String, Variant<*>>) : DBusSignal(path, info)
}
