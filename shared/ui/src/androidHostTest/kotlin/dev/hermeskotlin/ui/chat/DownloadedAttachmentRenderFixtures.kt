package dev.hermeskotlin.ui.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shares the viewer test's sandbox: Compose's cached dispatcher must keep the same Looper. */
internal object DownloadedAttachmentRenderFixtures {
    fun actualAttachmentCardsRenderDownloadAndSavedStates() {
        val context = RuntimeEnvironment.getApplication()
        val fixture = File(context.cacheDir, "attachment-example.txt").apply { writeText("Local attachment example") }
        val provider = AttachmentRenderProvider(fixture)
        provider.attachInfo(context, ProviderInfo().apply { authority = "attachment-render" })
        ShadowContentResolver.registerProviderInternal("attachment-render", provider)
        val owner = GatewayUrl.parse("https://gateway.example") to "test-profile"
        val uri = Uri.parse("content://attachment-render/downloads/1")
        val savedItem = ReplyMedia("/fixtures/saved-example.txt", "saved-example.txt", false)
        val pendingItem = ReplyMedia("/fixtures/download-example.pdf", "download-example.pdf", false)
        runBlocking {
            SavedDownloadStore(context).remember(downloadedAttachmentKey(owner, savedItem.source)!!, uri, savedItem.name, "text/plain")
        }
        var fetches = 0
        val files = WidgetFiles(owner, { error("No widget preview") }, { error("No widget download") })
        // Keep both palettes in the same sandbox: Compose caches AndroidUiDispatcher's Looper.
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            CompositionLocalProvider(LocalWidgetFiles provides files, LocalMediaLoader provides { fetches++; error("Rendering must not download") }) {
                Column {
                    listOf(ColorScheme.Light, ColorScheme.Dark).forEach { scheme ->
                        HermesTheme(scheme) {
                            Column(
                                Modifier.weight(1f).fillMaxSize().background(Theme[colors][background]).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ReplyFile(pendingItem)
                                ReplyFile(savedItem)
                            }
                        }
                    }
                }
            }
        }
        val root = activity.window.decorView
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 960)
            Thread.sleep(20)
        }
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/reports/attachment-cards-light-dark.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(provider.reads > 0, "The real card must query the persisted local copy")
        assertEquals(0, fetches)
        controller.pause().stop().destroy()
    }
}

private class AttachmentRenderProvider(private val file: File) : ContentProvider() {
    @Volatile var reads = 0
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        reads++
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
}
