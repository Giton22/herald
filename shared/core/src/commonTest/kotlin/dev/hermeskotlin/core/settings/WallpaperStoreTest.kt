package dev.hermeskotlin.core.settings

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stores run in the test's own scope: advanceUntilIdle leaves backgroundScope's writes unrun. */
class WallpaperStoreTest {

    @Test
    fun startsWithoutAnImageAndRemembersOne() = runTest {
        val disk = InMemoryBlobFile()
        val first = WallpaperStore(disk, this)
        assertNull(first.wallpaper.filterNotNull().first().image)

        first.set(byteArrayOf(1, 2, 3))
        advanceUntilIdle()

        val reopened = WallpaperStore(disk, this)
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.wallpaper.filterNotNull().first().image)
    }

    @Test
    fun clearingDeletesTheFile() = runTest {
        val disk = InMemoryBlobFile(byteArrayOf(9))
        val store = WallpaperStore(disk, this)
        advanceUntilIdle()

        store.clear()
        advanceUntilIdle()

        assertNull(store.wallpaper.value?.image)
        assertNull(disk.bytes)
    }

    @Test
    fun aChangeBeforeTheReadFinishesWins() = runTest {
        val disk = InMemoryBlobFile(byteArrayOf(9))
        val store = WallpaperStore(disk, this)
        store.set(byteArrayOf(4))
        advanceUntilIdle()

        assertContentEquals(byteArrayOf(4), store.wallpaper.value?.image)
        assertContentEquals(byteArrayOf(4), disk.bytes)
    }

    @Test
    fun anUnreadableFileMeansNoImage() = runTest {
        val broken = object : BlobFile {
            override suspend fun read(): ByteArray = error("disk")
            override suspend fun write(bytes: ByteArray) = Unit
            override suspend fun delete() = Unit
        }
        assertEquals(null, WallpaperStore(broken, this).wallpaper.filterNotNull().first().image)
    }
}
