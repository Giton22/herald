package dev.hermeskotlin.android.widget

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import dev.hermeskotlin.android.HeraldActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The manifest wiring (docs/WIDGETS-SPEC.md D24-D25): four providers with their provider info,
 *  the RemoteViewsService with BIND_REMOTEVIEWS, and the hermes:// hosts on HeraldActivity. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetManifestTest {

    private val context = RuntimeEnvironment.getApplication()
    private val pm get() = context.packageManager

    private fun providerInfo(provider: Class<*>, xml: Int) {
        val info = pm.getReceiverInfo(ComponentName(context, provider), PackageManager.GET_META_DATA)
        assertNotNull(info.metaData)
        assertEquals(xml, info.metaData.getInt("android.appwidget.provider"))
    }

    @Test
    fun allFourProvidersDeclareTheirInfo() {
        providerInfo(NowWidgetProvider::class.java, dev.hermeskotlin.android.R.xml.widget_now_info)
        providerInfo(ChatsWidgetProvider::class.java, dev.hermeskotlin.android.R.xml.widget_chats_info)
        providerInfo(KanbanWidgetProvider::class.java, dev.hermeskotlin.android.R.xml.widget_kanban_info)
        providerInfo(ScheduledWidgetProvider::class.java, dev.hermeskotlin.android.R.xml.widget_scheduled_info)
    }

    @Test
    fun allFourProvidersAreExportedForTheRefreshBroadcast() {
        // The launcher fires the refresh PendingIntent; a non-exported provider is skipped for that
        // broadcast, so the refresh button silently does nothing (v0.16.2 bug). Keep them exported.
        for (provider in listOf(
            NowWidgetProvider::class.java,
            ChatsWidgetProvider::class.java,
            KanbanWidgetProvider::class.java,
            ScheduledWidgetProvider::class.java,
        )) {
            val info = pm.getReceiverInfo(ComponentName(context, provider), 0)
            assertTrue("${provider.simpleName} must be exported so its refresh broadcast lands", info.exported)
        }
    }

    @Test
    fun collectionServiceIsProtectedAndPrivate() {
        val info = pm.getServiceInfo(ComponentName(context, WidgetViewsService::class.java), 0)
        assertEquals("android.permission.BIND_REMOTEVIEWS", info.permission)
        assertEquals(false, info.exported)
    }

    @Test
    fun heraldActivityAdvertisesTheWidgetHosts() {
        for (host in listOf("kanban", "scheduled", "now")) {
            val matches = pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("hermes://$host")),
                0,
            )
            assertTrue(
                "hermes://$host should resolve to HeraldActivity",
                matches.any { it.activityInfo.name == HeraldActivity::class.java.name },
            )
        }
    }
}
