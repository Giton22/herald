package dev.hermeskotlin.ui.chat

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OpenSavedFileTest {
    private val uri = Uri.parse("content://media/external_primary/downloads/42")

    @Test
    fun pdfTapStaysInHeraldInsteadOfLaunchingAnAppPicker() {
        val context = RuntimeEnvironment.getApplication()
        val tap = Intent(context, OpenSavedFileActivity::class.java).setDataAndType(uri, "application/pdf")
        val activity = Robolectric.buildActivity(OpenSavedFileActivity::class.java, tap).create().get()
        assertEquals(null, shadowOf(activity).nextStartedActivity, "A saved PDF should open directly inside Herald")
        assertTrue(!activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun explicitExternalOpenGrantsReadPermission() {
        val context = RuntimeEnvironment.getApplication()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        shadowOf(context.packageManager).addResolveInfoForIntent(view, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.reader"; name = "PdfReader"; exported = true }
        })
        val tap = Intent(context, OpenSavedFileActivity::class.java).setDataAndType(uri, "application/pdf")
        val activity = Robolectric.buildActivity(OpenSavedFileActivity::class.java, tap).create().get()
        activity.openInAnotherApp()
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(uri, opened.data)
        assertEquals("application/pdf", opened.type)
        assertEquals(uri, opened.clipData?.getItemAt(0)?.uri)
        assertTrue(opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun noViewerFallsBackToDownloadsInsteadOfCrashing() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).checkActivities(true)
        val downloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        shadowOf(context.packageManager).addResolveInfoForIntent(downloads, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.files"; name = "Downloads"; exported = true }
        })
        val tap = Intent(context, OpenSavedFileActivity::class.java).setDataAndType(uri, "application/x-unknown")
        val activity = Robolectric.buildActivity(OpenSavedFileActivity::class.java, tap).create().get()
        activity.openInAnotherApp()
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, shadowOf(activity).nextStartedActivity.action)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("No app can open"))
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun apkOnlyLaunchesInstallerAfterExplicitAction() {
        val context = RuntimeEnvironment.getApplication()
        val tap = Intent(context, OpenSavedFileActivity::class.java)
            .setDataAndType(uri, "application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "sample.apk")
        val activity = Robolectric.buildActivity(OpenSavedFileActivity::class.java, tap).create().get()
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        activity.openInAnotherApp()
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("application/vnd.android.package-archive", opened.type)
        assertEquals(uri, opened.data)
        assertTrue(opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun sharingUsesTheSavedUriWithoutDownloadingAgain() {
        val context = RuntimeEnvironment.getApplication()
        val tap = Intent(context, OpenSavedFileActivity::class.java).setDataAndType(uri, "application/pdf")
        val activity = Robolectric.buildActivity(OpenSavedFileActivity::class.java, tap).create().get()
        activity.shareFile()
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val shared = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, shared.action)
        assertEquals(uri, shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun missingUriFinishesWithoutLaunchingAnything() {
        val activity = Robolectric.buildActivity(OpenSavedFileActivity::class.java).create().get()
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
