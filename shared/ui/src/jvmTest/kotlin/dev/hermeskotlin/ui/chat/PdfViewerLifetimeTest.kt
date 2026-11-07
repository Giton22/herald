package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.HermesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PdfViewerLifetimeTest {
    @Test
    fun leavingViewerCancelsAnOpeningDocument() = runComposeUiTest {
        var visible by mutableStateOf(true)
        val started = CompletableDeferred<Unit>()
        val retired = CompletableDeferred<Unit>()
        setContent {
            HermesTheme {
                if (visible) FileViewerPage(
                    file = GatewayFile("/synthetic.pdf"),
                    onDismiss = {},
                    load = { error("PDFs download whole") },
                    download = { FileDownload.Ready(byteArrayOf(1)) },
                    openPdf = {
                        started.complete(Unit)
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                kotlinx.coroutines.withTimeoutOrNull(5_000) { awaitCancellation() }
                            }
                            PdfOpen.Failed("Fixture retired")
                        } finally { retired.complete(Unit) }
                    },
                )
            }
        }
        waitUntil(timeoutMillis = 2_000) { started.isCompleted }
        runOnIdle { visible = false }
        waitUntil(timeoutMillis = 2_000) { retired.isCompleted }
    }
}
