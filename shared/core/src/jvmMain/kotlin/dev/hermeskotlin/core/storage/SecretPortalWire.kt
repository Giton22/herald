package dev.hermeskotlin.core.storage

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.Secret")
internal interface SecretPortalWire : DBusInterface {
    fun RetrieveSecret(fd: FileDescriptor, options: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Request")
internal interface SecretPortalRequest : DBusInterface {
    fun Close()
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) :
        DBusSignal(path, response, results)
}
