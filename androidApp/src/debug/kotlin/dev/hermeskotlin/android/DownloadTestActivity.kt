package dev.hermeskotlin.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.composeunstyled.Text
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.chat.rememberMediaActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Debug-only fixtures for the real Save → notification → local viewer flow, with no gateway or private data. */
class DownloadTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra("type") ?: "pdf"
        setContent {
            HermesTheme {
                val actions = rememberMediaActions()
                var status by remember { mutableStateOf("Saving test file…") }
                LaunchedEffect(type) {
                    val bytes = withContext(Dispatchers.IO) { sample(type) }
                    status = actions.save(bytes, "download-test.$type")
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(status) }
            }
        }
    }

    private fun sample(type: String): ByteArray = when (type) {
        "json" -> """{"file":"download-test","status":"saved","items":[1,2,3]}""".encodeToByteArray()
        "csv" -> "Item,Status,Count\nDocument,Saved,2\nImage,Ready,1".encodeToByteArray()
        "md" -> "# Download test\n\nA **saved file** opened inside Herald.\n\n- Local preview\n- No app picker".encodeToByteArray()
        "txt" -> "A saved text file.\nThis preview is read from the phone, not downloaded again.".encodeToByteArray()
        "apk" -> java.io.File(applicationInfo.sourceDir).readBytes()
        "png" -> ByteArrayOutputStream().use { out ->
            val bitmap = androidx.core.graphics.createBitmap(800, 600)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.rgb(38, 88, 200))
            canvas.drawCircle(400f, 260f, 120f, Paint().apply { color = Color.WHITE; isAntiAlias = true })
            canvas.drawText("Image preview", 200f, 480f, Paint().apply { color = Color.WHITE; textSize = 56f; isAntiAlias = true })
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        else -> PdfDocument().let { doc ->
            try {
            repeat(2) { index ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
                val paint = Paint().apply { color = Color.BLACK; textSize = 26f; isAntiAlias = true }
                page.canvas.drawText("Download test", 48f, 80f, paint)
                paint.textSize = 16f
                page.canvas.drawText("Page ${index + 1}: saved on this phone.", 48f, 126f, paint)
                page.canvas.drawText("Pinch to zoom. Swipe to the next page.", 48f, 160f, paint)
                doc.finishPage(page)
            }
            ByteArrayOutputStream().use { out -> doc.writeTo(out); out.toByteArray() }
            } finally { doc.close() }
        }
    }
}
