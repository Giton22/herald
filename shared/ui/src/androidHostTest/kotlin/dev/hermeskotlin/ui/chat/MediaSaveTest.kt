package dev.hermeskotlin.ui.chat

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MediaSaveTest {
    private lateinit var context: Application
    private lateinit var provider: SavedFileProvider
    private lateinit var actions: MediaActions

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        provider = SavedFileProvider(File(context.cacheDir, "saved.pdf"))
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        // The platform implementation is private; exercise the same entry point the Compose viewer uses.
        val constructor = Class.forName("dev.hermeskotlin.ui.chat.AndroidMediaActions").getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        actions = constructor.newInstance(context) as MediaActions
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun completedSaveUsesHeadsUpImportance() = runTest {
        actions.save(byteArrayOf(1), "report.pdf")
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(notification.channelId).importance)
    }

    @Test
    fun existingDefaultChannelIsMigratedForHeadsUpOnUpgrade() = runTest {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_DEFAULT))
        actions.save(byteArrayOf(1), "report.pdf")
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(notification.channelId).importance)
    }

    @Test
    fun completionOffersAnExplicitOpenButton() = runTest {
        actions.save(byteArrayOf(1), "report.pdf")
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val open = notification.actions?.singleOrNull()
        assertNotNull(open, "The completion alert should offer an Open action")
        assertEquals("Open", open.title)
        assertEquals(notification.contentIntent, open.actionIntent)
    }

    @Test
    fun silencedLegacyChannelIsNotBypassedOnUpgrade() = runTest {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_NONE))
        assertEquals("Saved to Download/Herald", actions.save(byteArrayOf(1), "report.pdf"))
        assertEquals(null, manager.getNotificationChannel("download-completion"))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("downloads").importance)
    }

    @Test
    fun explicitLegacyImportanceIsPreservedOnUpgrade() = runTest {
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = android.app.NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_DEFAULT)
        // Simulate Android locking importance after the user chooses it in Settings.
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(legacy, "lockFields",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 4))
        manager.createNotificationChannel(legacy)
        actions.save(byteArrayOf(1), "report.pdf")
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("downloads", notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(notification.channelId).importance)
    }

    @Test
    fun newChannelKeepsUserLoweredImportanceOnLaterSaves() = runTest {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("download-completion", "Downloads", NotificationManager.IMPORTANCE_LOW))
        actions.save(byteArrayOf(1), "report.pdf")
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("download-completion", notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(notification.channelId).importance)
    }

    @Test
    fun failedInsertReturnsAnErrorWithoutANotification() = runTest {
        provider.failInsert = true
        assertEquals("Couldn't save report.pdf.", actions.save(byteArrayOf(1), "report.pdf"))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun deniedNotificationsStillLeaveTheFileSaved() = runTest {
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("Saved to Download/Herald", actions.save(byteArrayOf(1, 2), "report.pdf"))
        assertTrue(provider.file.readBytes().contentEquals(byteArrayOf(1, 2)))
        assertEquals(0, provider.deletes)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun disabledNotificationsStillLeaveTheFileSaved() = runTest {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals("Saved to Download/Herald", actions.save(byteArrayOf(1), "report.pdf"))
        assertEquals(0, provider.deletes)
    }

    @Test
    fun writeFailureRemovesThePartialEntryWithoutNotifying() = runTest {
        provider.failWrite = true
        assertEquals("Couldn't save report.pdf.", actions.save(byteArrayOf(1), "report.pdf"))
        assertEquals(1, provider.deletes)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun publishFailureDoesNotAnnounceAnUnavailableFile() = runTest {
        provider.failPublish = true
        assertEquals("Couldn't save report.pdf.", actions.save(byteArrayOf(1), "report.pdf"))
        assertEquals(1, provider.deletes)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun repeatedNamesKeepSeparateNotificationTargets() = runTest {
        actions.save(byteArrayOf(1), "report.pdf")
        actions.save(byteArrayOf(2), "report.pdf")
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(2, notifications.size)
        val targets = notifications.map { shadowOf(it.contentIntent).savedIntent.data }
        assertEquals(2, targets.toSet().size)
        notifications.forEach {
            val open = shadowOf(it.contentIntent).savedIntent
            assertEquals("application/pdf", open.type)
            assertEquals(OpenSavedFileActivity::class.java.name, open.component?.className)
            assertTrue(shadowOf(it.contentIntent).isImmutable)
        }
    }

    @Test
    fun platformKnownFileTypesKeepTheirViewerMimeType() = runTest {
        actions.save(byteArrayOf(1), "report.doc")
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("application/msword", shadowOf(notification.contentIntent).savedIntent.type)
    }

    @Test
    fun imageStillSavesInPicturesAndCanBeOpened() = runTest {
        assertEquals("Saved to Pictures/Herald", actions.save(byteArrayOf(1), "photo.png"))
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("image/png", shadowOf(notification.contentIntent).savedIntent.type)
        assertEquals("Pictures/Herald", provider.inserted?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    @Config(sdk = [29])
    fun android10NotifiesWithoutRuntimeNotificationPermission() = runTest {
        assertEquals("Saved to Download/Herald", actions.save(byteArrayOf(1), "report.pdf"))
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test
    @Config(sdk = [28])
    fun olderAndroidKeepsShareFallback() = runTest {
        assertEquals("Saving needs Android 10. Use Share instead.", actions.save(byteArrayOf(1), "report.pdf"))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun savedFileHasATappableCompletionNotification() = runTest {
        val bytes = "downloaded file".encodeToByteArray()
        assertEquals("Saved to Download/Herald", actions.save(bytes, "report.pdf"))
        assertTrue(provider.file.readBytes().contentEquals(bytes))
        assertEquals(0, provider.published?.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.singleOrNull()
        assertNotNull(notification, "A saved file must have a completion notification")
        assertEquals("report.pdf", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNotNull(notification.contentIntent)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }
}

private class SavedFileProvider(val file: File) : ContentProvider() {
    var published: ContentValues? = null
    var inserted: ContentValues? = null
    var failInsert = false
    var failWrite = false
    var failPublish = false
    var deletes = 0
    private var nextId = 42
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (failInsert) throw java.io.IOException("Storage unavailable")
        inserted = values
        return Uri.parse("content://media/external_primary/downloads/${nextId++}")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failWrite) throw java.io.FileNotFoundException("Storage unavailable")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (failPublish) return 0
        published = values
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deletes++
        return 1
    }
    override fun getType(uri: Uri) = "application/pdf"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
}
