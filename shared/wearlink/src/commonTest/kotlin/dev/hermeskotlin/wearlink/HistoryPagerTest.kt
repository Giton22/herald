package dev.hermeskotlin.wearlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryPagerTest {
    private val owner = WatchScope("https://gateway.example", "work")
    private fun request(id: String = "first", offset: Int = 0) = HistoryRequest(id, owner, owner, "saved", offset = offset)
    private fun row(id: String, text: String = id) = HistoryMessage(id, "assistant", text)

    @Test
    fun ignoresLateResponseAfterSwitchAndKeepsChronologicalPagesWithoutDuplicates() {
        val pager = HistoryPager()
        val first = request()
        pager.begin(first)
        assertTrue(pager.accept(HistoryPage(first, "saved", listOf(row("2"), row("3")), 20)))
        val older = request("older", 20)
        pager.begin(older)
        assertTrue(pager.accept(HistoryPage(older, "saved", listOf(row("1"), row("2")), null)))
        assertEquals(listOf("1", "2", "3"), pager.page!!.messages.map { it.id })
        val next = request("next", 20).copy(scope = owner.copy(profile = "other"))
        pager.begin(next)
        assertFalse(pager.accept(HistoryPage(first, "saved", listOf(row("private")))))
        assertTrue(pager.page!!.messages.isEmpty())
    }

    @Test
    fun errorsEndLoadingAndKeepEarlierRowsButNewBotTipNeedsRefresh() {
        val pager = HistoryPager()
        val first = request()
        pager.begin(first)
        pager.accept(HistoryPage(first, "saved", listOf(row("1")), 20))
        val older = request("older", 20)
        pager.begin(older)
        pager.accept(HistoryPage(older, error = "Offline"))
        assertFalse(pager.loading)
        assertEquals(listOf(row("1")), pager.page!!.messages)
        assertEquals(20, pager.page!!.nextOffset)
        pager.begin(older)
        pager.accept(HistoryPage(older, "new-tip", listOf(row("new"))))
        assertTrue(pager.page!!.messages.isEmpty())
        assertTrue(pager.page!!.error != null)
    }

    @Test
    fun resetAndRoundTripCannotRestoreAnotherOwnersPendingPage() {
        val pager = HistoryPager()
        val pending = request()
        pager.begin(pending)
        pager.reset()
        assertFalse(pager.accept(HistoryPage(pending, "saved", listOf(row("private")))))
        assertFalse(pager.loading)
        val page = HistoryPage(pending, "saved", listOf(row("1", "\r\n```code```" + "word".repeat(3000))))
        assertEquals(page, WearJson.decode<HistoryPage>(WearJson.encode(page)))
    }
    @Test
    fun adoptedConversationClearsLoadedAndPendingHistoryButUnchangedSelectionDoesNot() {
        val pager = HistoryPager()
        val first = request()
        pager.begin(first)
        pager.accept(HistoryPage(first, "saved", listOf(row("private")), 20))
        assertFalse(pager.select(owner, "saved", null))
        assertEquals(1, pager.page!!.messages.size)
        val older = request("older", 20)
        pager.begin(older)
        assertTrue(pager.select(owner, "new-chat", null))
        assertEquals(null, pager.page)
        assertFalse(pager.loading)
        assertFalse(pager.accept(HistoryPage(older, "saved", listOf(row("late")))))
        pager.begin(first)
        pager.accept(HistoryPage(first, "saved", listOf(row("old-profile"))))
        assertTrue(pager.select(owner.copy(profile = "other"), "saved", null))
        assertEquals(null, pager.page)
    }

}
