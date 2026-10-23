package dev.hermeskotlin.ui.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedFileViewerRenderTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @org.junit.Before
    fun useThisSandboxMainLooper() {
        // Other host tests replace Main; bind it to this Robolectric sandbox, not a previous test's Looper.
        kotlinx.coroutines.Dispatchers.setMain(android.os.Handler(Looper.getMainLooper()).asCoroutineDispatcher())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @org.junit.After
    fun resetMain() { kotlinx.coroutines.Dispatchers.resetMain() }

    @Test
    fun savedFileScreensRenderWithoutAChooser() {
        // Compose caches AndroidUiDispatcher's Looper. Render the fixtures in one Robolectric
        // sandbox rather than retaining a dispatcher whose Looper a previous test has reset.
        DownloadedAttachmentRenderFixtures.actualAttachmentCardsRenderDownloadAndSavedStates()
        renderFixture("download-test.pdf", "application/pdf", "This is not a PDF".encodeToByteArray())
        renderFixture("download-test.json", "application/json", """{"status":"saved","items":[1,2,3]}""".encodeToByteArray())
        renderFixture("download-test.csv", "text/csv", "Item,Status,Count\nDocument,Saved,2\nImage,Ready,1".encodeToByteArray())
        renderFixture("download-test.md", "text/plain", "# Download test\n\nA **saved file** opened inside Herald.\n\n- Local preview\n- No app picker".encodeToByteArray())
    }

    private fun renderFixture(name: String, mime: String, bytes: ByteArray) {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        val provider = PdfProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val uri = Uri.parse("content://media/external_primary/downloads/42")
        val tap = Intent(context, OpenSavedFileActivity::class.java)
            .setDataAndType(uri, mime)
            .putExtra(Intent.EXTRA_TITLE, name)
        val controller = Robolectric.buildActivity(OpenSavedFileActivity::class.java, tap).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 720, 1600)
        // Exercise the actual Compose fallback; Robolectric does not implement PDF page rendering.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (provider.opens == 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        val bitmap = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/reports/saved-$name.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(provider.opens > 0, "The viewer must read the saved content URI")
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        assertTrue(!activity.isFinishing)
        controller.pause().stop().destroy()
    }
}

private class PdfProvider(private val file: File) : ContentProvider() {
    @Volatile var opens = 0
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        opens++
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/pdf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
}
