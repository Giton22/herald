package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GatewayRepositoryTest {

    private val home = SavedGateway("http://100.64.0.1:9119")
    private val work = SavedGateway("https://hermes.work.example")
    private val lab = SavedGateway("http://lab.local:9119")

    @Test
    fun theOneGatewayEarlierBuildsKeptBecomesTheFirstSavedOne() = runTest {
        val store = InMemoryKeyValueStore()
        store.put("gateway.current.v1", """{"url":"http://100.64.0.1:9119","provider":"basic"}""")

        val list = GatewayRepository(store).all()

        assertEquals(listOf(home), list.gateways)
        assertEquals(home, list.current)
        assertEquals(home, list.primary)
        assertNull(store.get("gateway.current.v1"))
        // A second read (a new process) finds the migrated list.
        assertEquals(home, GatewayRepository(store).current())
    }

    @Test
    fun theFirstGatewaySavedIsPrimaryAndTheLastSavedIsCurrent() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(home)
        repo.save(work)

        val list = repo.all()
        assertEquals(work, list.current)
        assertEquals(home, list.primary)
        assertEquals(listOf(home, work), list.ordered)
    }

    @Test
    fun thePrimaryIsListedFirst() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(home)
        repo.save(work)
        repo.save(lab)
        repo.setPrimary(lab.url)

        assertEquals(listOf(lab, home, work), repo.all().ordered)
    }

    @Test
    fun signingInAgainKeepsTheNameGiven() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(home)
        repo.rename(home.url, "  Home  ")
        repo.save(work)
        repo.save(home)

        val saved = repo.all()
        assertEquals("Home", saved.current?.name)
        assertEquals("Home", saved.current?.label)
        assertEquals(2, saved.gateways.size)
    }

    @Test
    fun signingInWithAnOlderCopyDoesntUndoARename() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        val signInCopy = repo.save(home.copy(name = "Work"))
        repo.rename(home.url, "Lab")
        repo.save(signInCopy)

        assertEquals("Lab", repo.current()?.name)
    }

    @Test
    fun aBlankNameShowsTheAddress() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(work.copy(name = "Work"))
        repo.rename(work.url, " ")

        assertEquals("hermes.work.example", repo.current()?.label)
    }

    @Test
    fun removingThePrimaryHandsItToTheNextOne() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(home)
        repo.save(work)
        repo.remove(home.url)

        val list = repo.all()
        assertEquals(listOf(work), list.gateways)
        assertEquals(work, list.primary)
        assertEquals(work, list.current)
    }

    @Test
    fun removingTheCurrentOneLeavesNoneCurrent() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(home)
        repo.save(work)
        repo.remove(work.url)

        val list = repo.all()
        assertNull(list.current)
        assertEquals(home, list.primary)
    }

    @Test
    fun anUnsavedAddressCantBeSelected() = runTest {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        repo.save(home)
        repo.select(work.url)

        assertEquals(home, repo.current())
    }

    @Test
    fun changesSurviveARestart() = runTest {
        val store = InMemoryKeyValueStore()
        GatewayRepository(store).apply {
            save(home)
            save(work)
            setPrimary(work.url)
            select(home.url)
        }

        val list = GatewayRepository(store).all()
        assertEquals(home, list.current)
        assertEquals(work, list.primary)
    }
}
