package dev.hermeskotlin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.core.gateway.ClientCertificateException
import dev.hermeskotlin.core.gateway.LinuxClientCertificates
import dev.hermeskotlin.core.gateway.linuxClientCertificates
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.SecureStorageException
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.ui.chat.fromDialog
import dev.hermeskotlin.ui.chat.openFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import java.io.File

@Composable
actual fun clientCertificatesAvailable(): Boolean = linuxClientCertificates(koinInject<KeyValueStore>()) != null

@Composable
actual fun rememberClientCertPicker(host: String?, port: Int, onPicked: (String?) -> Unit): ClientCertPicker {
    val store = koinInject<KeyValueStore>()
    val importer = remember(store) { linuxClientCertificates(store)?.let { it::import } }
    return key(store) { rememberDesktopClientCertPicker(host, port, importer, {
        fromDialog { openFiles(getString(Res.string.gateways_client_cert), multiple = false, setOf("p12", "pfx")) }
            .getOrElse { throw IllegalStateException("Couldn't open the file chooser.") }.firstOrNull()
    }, onPicked) }
}

/** The composition owns each import; a disposed/cancelled operation cannot select its late result. */
@Composable
internal fun rememberDesktopClientCertPicker(
    host: String?,
    port: Int,
    importer: (suspend (String, Int, ByteArray, CharArray) -> String)?,
    choose: suspend () -> File?,
    onPicked: (String?) -> Unit,
): ClientCertPicker {
    val scope = rememberCoroutineScope()
    val password = remember(host, port) { TextFieldState() }
    var file by remember(host, port) { mutableStateOf<File?>(null) }
    var deliver by remember(host, port) { mutableStateOf<((String?) -> Unit)?>(null) }
    var busy by remember(host, port) { mutableStateOf(false) }
    var error by remember(host, port) { mutableStateOf<String?>(null) }
    var operation by remember(host, port) { mutableStateOf<Job?>(null) }
    var generation by remember(host, port) { mutableIntStateOf(0) }
    fun cancel() {
        generation++
        operation?.cancel()
        operation = null
        file = null
        deliver = null
        busy = false
        error = null
        password.clearText()
    }
    DisposableEffect(host, port) { onDispose { cancel() } }
    if (file != null) ClientCertificateImportDialog(password, busy, error, onImport = {
        val selected = file
        val applyPick = deliver
        if (!busy && selected != null && applyPick != null && importer != null && host != null) {
            busy = true
            error = null
            val ticket = ++generation
            operation = scope.launch {
                val secret = password.text.toString().toCharArray()
                password.clearText()
                var bytes: ByteArray? = null
                try {
                    val input = runInterruptible(Dispatchers.IO) {
                        readClientCertificate(selected).also { bytes = it }
                    }
                    if (input.size > LinuxClientCertificates.MAX_BYTES) throw ClientCertificateException()
                    val alias = importer(host, port, input, secret)
                    currentCoroutineContext().ensureActive()
                    if (generation == ticket) {
                        generation++
                        file = null
                        deliver = null
                        busy = false
                        operation = null
                        applyPick(alias)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == ticket) {
                        val detail = clientCertificateFailure(e)
                        if (generation == ticket) error = detail
                    }
                } finally {
                    bytes?.fill(0)
                    secret.fill('\u0000')
                    if (generation == ticket) { busy = false; operation = null }
                }
            }
        }
    }, onDismiss = ::cancel)
    else if (error != null) Dialog(true, ::cancel, stringResource(Res.string.gateways_client_cert), message = error, actions = {
        Button(stringResource(Res.string.chat_close), onClick = ::cancel)
    })
    return remember(host, port, importer, choose, onPicked) {
        object : ClientCertPicker {
            override val available = importer != null && !host.isNullOrBlank() && port in 1..65535
            override fun pick() {
                if (!available || busy || file != null) return
                busy = true
                error = null
                val ticket = ++generation
                // Capture the requesting owner, rather than delivering into a newer callback after an await.
                val applyPick = onPicked
                operation = scope.launch {
                    try {
                        val selected = choose()
                        currentCoroutineContext().ensureActive()
                        if (generation == ticket) { file = selected; deliver = applyPick.takeIf { selected != null } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (generation == ticket) {
                            val detail = getString(Res.string.desktop_file_dialog_failed)
                            if (generation == ticket) error = detail
                        }
                    } finally {
                        if (generation == ticket) { busy = false; operation = null }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ClientCertificateImportDialog(
    password: TextFieldState,
    busy: Boolean,
    error: String?,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.client_cert_import_title),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost)
            Button(stringResource(if (busy) Res.string.client_cert_importing else Res.string.client_cert_import), onClick = onImport, enabled = !busy)
        },
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.client_cert_import_detail),
                style = Theme[typography][body], color = Theme[colors][textSecondary],
            )
            TextField(password, label = stringResource(Res.string.client_cert_password), password = true, error = error, enabled = !busy, autofocus = true)
        }
    }
}

/** UI guidance is catalogued; exception messages stay available as platform diagnostics. */
private suspend fun clientCertificateFailure(error: Exception): String = getString(when (error) {
    is ClientCertificateException -> Res.string.client_cert_invalid
    is ClientCertificateFileException -> Res.string.client_cert_regular_file
    is SecureStorageException -> when (error.reason) {
        SecureStorageException.Reason.Unavailable -> Res.string.client_cert_storage_unavailable
        SecureStorageException.Reason.Cancelled -> Res.string.client_cert_storage_cancelled
        SecureStorageException.Reason.Corrupt -> Res.string.client_cert_storage_corrupt
        SecureStorageException.Reason.Version -> Res.string.client_cert_storage_version
        SecureStorageException.Reason.Read -> Res.string.client_cert_storage_read
        SecureStorageException.Reason.Write -> Res.string.client_cert_storage_write
        SecureStorageException.Reason.Uncertain -> Res.string.client_cert_storage_uncertain
        SecureStorageException.Reason.Changed -> Res.string.client_cert_storage_changed
        SecureStorageException.Reason.Legacy -> Res.string.client_cert_storage_legacy
        SecureStorageException.Reason.Unsupported -> Res.string.client_cert_storage_unsupported
    }
    else -> Res.string.client_cert_read_failed
})
