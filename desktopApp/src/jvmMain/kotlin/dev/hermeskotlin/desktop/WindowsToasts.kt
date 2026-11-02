package dev.hermeskotlin.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** An approval a notification's button answers: [request] in the chat [sessionId]. */
internal data class NoticeAnswer(val sessionId: String?, val request: InputRequest.Approval, val choice: ApprovalChoice)

/**
 * Windows' own notifications for an installed Herald: they stay in the Action Center, name Herald with its icon, and
 * carry buttons, which the tray's balloons can't. A click opens a `herald://` link, which Windows hands to Herald
 * (registered here for the user, as the installer can't), and a running Herald takes over from the launch it starts.
 *
 * - The notification itself opens its chat: `herald://session/<id>`.
 * - An approval's Approve and Deny buttons answer it: `herald://answer/<token>`. The token is random and known only
 *   to this run of Herald, so no web page can approve a command by guessing a link.
 */
internal object WindowsToasts {
    private val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    /** Only an installed Herald on Windows: one run from a build has no Herald.exe for Windows to start. */
    val available: Boolean = launcher != null && System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val answers = ConcurrentHashMap<String, NoticeAnswer>()
    private val random = SecureRandom()

    /**
     * Registers the `herald://` scheme and Herald's name and [icon] for its notifications, for this user. Each launch
     * writes them again, so a Herald that moved or updated is the one they start.
     */
    fun register(icon: Image, iconDir: File) {
        val launcher = launcher?.takeIf { available } ?: return
        runCatching {
            val scheme = "Software\\Classes\\${AppLink.DESKTOP_SCHEME}"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$scheme\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, scheme, "", "URL:Kyllene")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, scheme, "URL Protocol", "")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$scheme\\shell\\open\\command", "", "\"${launcher.path}\" \"%1\"")

            val png = File(iconDir, "notification-icon.png")
            val pixels = icon as? BufferedImage ?: BufferedImage(icon.getWidth(null), icon.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                .also { it.createGraphics().apply { drawImage(icon, 0, 0, null) }.dispose() }
            ImageIO.write(pixels, "png", png)
            val app = "Software\\Classes\\AppUserModelId\\$APP_ID"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, app)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, app, "DisplayName", "Kyllene")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, app, "IconUri", png.path)
        }
    }

    /** Shows [notice]; false when Windows wouldn't, and the tray's balloon should tell it instead. */
    fun show(notice: DesktopNotice): Boolean {
        if (!available) return false
        val open = notice.sessionId?.let { "${AppLink.DESKTOP_SCHEME}://session/${encode(it)}" } ?: "${AppLink.DESKTOP_SCHEME}://show"
        val approval = notice.request as? InputRequest.Approval
        val buttons = if (approval == null) {
            ""
        } else {
            "<actions>" +
                button("Approve once", answerLink(NoticeAnswer(notice.sessionId, approval, ApprovalChoice.Once))) +
                button("Deny", answerLink(NoticeAnswer(notice.sessionId, approval, ApprovalChoice.Deny))) +
                "</actions>"
        }
        val xml = "<toast activationType=\"protocol\" launch=\"${attr(open)}\">" +
            "<visual><binding template=\"ToastGeneric\"><text>${text(notice.title)}</text><text>${text(notice.body)}</text></binding></visual>" +
            buttons + "</toast>"
        val script = """
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > ${'$'}null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > ${'$'}null
            ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml('${xml.replace("'", "''")}')
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$APP_ID').Show([Windows.UI.Notifications.ToastNotification]::new(${'$'}xml))
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        // Windows PowerShell, which every Windows 10 and 11 has, reaches the notification API; PowerShell 7 can't.
        return runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
    }

    /** The answer a notification's button stands for, taken once; null for any other link. */
    fun takeAnswer(link: String): NoticeAnswer? {
        val prefix = "${AppLink.DESKTOP_SCHEME}://answer/"
        if (!link.startsWith(prefix, ignoreCase = true)) return null
        return answers.remove(link.substring(prefix.length).trimEnd('/'))?.also { taken ->
            // The other button of the same notification is spent too.
            answers.values.removeAll { it.request.id == taken.request.id }
        }
    }

    private fun answerLink(answer: NoticeAnswer): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        answers[token] = answer
        return "${AppLink.DESKTOP_SCHEME}://answer/$token"
    }

    private fun button(label: String, link: String) =
        "<action content=\"${attr(label)}\" activationType=\"protocol\" arguments=\"${attr(link)}\"/>"

    private fun text(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(value: String) = text(value).replace("\"", "&quot;")

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    /** The name Windows files Herald's notifications under. */
    private const val APP_ID = "dev.hermeskotlin.herald"
}
