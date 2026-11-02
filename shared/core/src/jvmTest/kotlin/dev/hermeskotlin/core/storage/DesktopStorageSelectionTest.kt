package dev.hermeskotlin.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DesktopStorageSelectionTest {
    @Test fun ordinaryLinuxKeepsItsExistingStoreAndStartupBehavior() = fixture { file, info ->
        val store = desktopKeyValueStore(file, "Linux", info)
        assertIs<FileKeyValueStore>(store)
        prepareDesktopStorage(store)
        store.put("existing", "preserved")
        assertEquals("preserved", FileKeyValueStore(file).get("existing"))
        assertFalse(File(file.parentFile, "settings.secure").exists())
    }

    @Test fun validMetadataSelectsSecureStorageWithoutUsingTheEnvironmentAsIdentity() = fixture { file, info ->
        info.writeText("[Application]\nname=io.example.StorageFixture\n[Instance]\ninstance-id=fixture\n")
        assertIs<LinuxKeyValueStore>(desktopKeyValueStore(file, "Linux", info))
    }

    @Test fun malformedOrUnreadableMetadataCannotSelectPlaintextStorage() = fixture { file, info ->
        for (contents in listOf("", "[Application]\nname=\n", "[Instance]\nname=not-an-application\n")) {
            info.writeText(contents)
            val store = desktopKeyValueStore(file, "Linux", info)
            assertIs<LinuxKeyValueStore>(store)
            assertEquals(SecureStorageException.Reason.Unsupported,
                assertFailsWith<SecureStorageException> { prepareDesktopStorage(store) }.reason)
        }
        check(info.delete()); check(info.mkdir())
        val unreadable = desktopKeyValueStore(file, "Linux", info)
        assertIs<LinuxKeyValueStore>(unreadable)
        assertFailsWith<SecureStorageException> { prepareDesktopStorage(unreadable) }
        assertFalse(file.exists())
    }

    @Test fun metadataIoFailureCannotBeMistakenForAnOrdinaryHost() = fixture { file, info ->
        val store = desktopKeyValueStore(file, "Linux", info) { throw IOException("synthetic metadata IO failure") }
        assertIs<LinuxKeyValueStore>(store)
        assertEquals(SecureStorageException.Reason.Unsupported,
            assertFailsWith<SecureStorageException> { prepareDesktopStorage(store) }.reason)
        assertFalse(file.exists())
    }

    @Test fun aHostAppStartedWithAStrayFlatpakIdKeepsItsStore() = fixture { file, info ->
        // A terminal or IDE inside a sandbox passes FLATPAK_ID on to the host apps it starts; only /.flatpak-info counts.
        FileKeyValueStore(file).put("existing", "host-value")
        val store = desktopKeyValueStore(file, "Linux", info)
        assertIs<FileKeyValueStore>(store)
        prepareDesktopStorage(store)
        assertEquals("host-value", store.get("existing"))
    }

    @Test fun otherDesktopPlatformsKeepTheirExistingStore() = fixture { file, info ->
        for (os in listOf("Windows 11", "Mac OS X")) {
            assertIs<FileKeyValueStore>(desktopKeyValueStore(file, os, info) { error("Other OS must not inspect Flatpak metadata") })
        }
    }

    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("kyllene-storage-selection-").toFile()
        try { block(File(directory, "settings.json"), File(directory, ".flatpak-info")) }
        finally { directory.deleteRecursively() }
    }
}
