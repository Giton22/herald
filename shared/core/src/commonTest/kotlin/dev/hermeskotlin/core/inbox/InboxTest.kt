package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GW = "https://gw"

class InboxTest {

    // Each time its own run, as the gateway names them: a run is its job and when it finished.
    private fun run(job: String = "006998ffc458", at: Long = 1_000, text: String = "Morning", status: String = InboxRun.OK) =
        InboxRun(bot = "default", job = job, run = "2026-10-12_${(at / 3600).toString().padStart(2, '0')}-${(at / 60 % 60).toString().padStart(2, '0')}-${(at % 60).toString().padStart(2, '0')}",
            label = "morning-briefing", status = status, at = at, text = text)

    @Test
    fun aCronRunPushBecomesARun() {
        val json = """{"id":"x","ts":1760245327,"type":"cron_run","bot":"default","job":"006998ffc458",
            "run":"2026-10-12_08-02-07","label":"morning-briefing","status":"ok","notify":true,"at":1760245326,"text":"Rain later."}"""
        val message = HermesJson.decodeFromString(PushMessage.serializer(), json)
        assertTrue(message.notify)
        val run = InboxRun.from(message)!!
        assertEquals("default/006998ffc458/2026-10-12_08-02-07", run.key)
        assertEquals("morning-briefing", run.title)
        assertEquals(1760245326, run.at)
        assertFalse(run.failed)
    }

    @Test
    fun aPushMissingWhatNamesTheRunOrNamingItOddlyIsDropped() {
        fun push(job: String?, run: String?, bot: String? = "default") =
            PushMessage(id = "x", ts = 1, type = PushMessage.CRON_RUN, bot = bot, job = job, run = run)
        assertNull(InboxRun.from(push(null, "2026-10-12_08-02-07")))
        assertNull(InboxRun.from(push("../../x", "2026-10-12_08-02-07")))
        assertNull(InboxRun.from(push("job", "yesterday")))
        // An odd profile falls back to the default one rather than being trusted.
        assertEquals("default", InboxRun.from(push("job", "2026-10-12_08-02-07", bot = "a/b"))!!.bot)
    }

    @Test
    fun aRunsLabelLosesControlCharactersAndAnUnknownStatusIsOk() {
        val message = PushMessage(id = "x", ts = 5, type = PushMessage.CRON_RUN, job = "j", run = "2026-10-12_08-02-07",
            label = "Brief\u0007ing", status = "weird")
        val run = InboxRun.from(message)!!
        assertEquals("Briefing", run.title)
        assertFalse(run.failed)
        assertEquals(5, run.at)
    }

    @Test
    fun followingAddsChangesAndDropsJobs() {
        val following = Following().with("a", Follow.Notify).with("b", Follow.Quiet)
        assertEquals(Follow.Notify, following.of("a"))
        assertEquals(Follow.Off, following.of("c"))
        assertEquals(mapOf("b" to Follow.Quiet), following.with("a", Follow.Off).jobs)
        assertTrue(following.any)
        assertFalse(Following().any)
        assertTrue(Following(failures = true).any)
        assertEquals(Follow.Off, Follow.of("loud"))
        assertEquals(Follow.Off, Follow.of(null))
    }

    @Test
    fun allOnFollowsEveryJobAndSingleJobsCanBeSwitchedOff() {
        val all = Following().with("brief", Follow.Notify).all(Follow.Quiet)
        // What was set per job is dropped; every job, new ones too, goes into the Inbox.
        assertEquals(emptyMap(), all.jobs)
        assertEquals(Follow.Quiet, all.of("anything"))
        assertTrue(all.any)

        val noisyOff = all.with("noisy", Follow.Off).with("brief", Follow.Notify)
        assertEquals(mapOf("noisy" to Follow.Off, "brief" to Follow.Notify), noisyOff.jobs)
        assertEquals(Follow.Off, noisyOff.of("noisy"))
        // Back to what the default says: not kept per job.
        assertEquals(mapOf("brief" to Follow.Notify), noisyOff.with("noisy", Follow.Quiet).jobs)

        val off = noisyOff.all(Follow.Off)
        assertEquals(Follow.Off, off.of("brief"))
        assertFalse(off.any)
    }

    @Test
    fun theFirstLookStartsReadAndLaterRunsAreUnread() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 2_000)
        store.replace(GW, listOf(run(at = 2_500), run(at = 1_500)), complete = true)
        val state = store.state.value
        assertEquals(listOf(2_500L, 1_500L), state.runs.map { it.at })
        assertEquals(1, state.unread)

        store.markRead(GW, state.runs.first())
        assertEquals(0, store.state.value.unread)
    }

    @Test
    fun aPushedRunTheGatewayHasntListedYetIsKept() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 0)
        store.add(GW, run(job = "pushed", at = 3_000))
        store.replace(GW, listOf(run(at = 2_000)), complete = true)
        assertEquals(listOf("pushed", "006998ffc458"), store.state.value.runs.map { it.job })
        // An older pushed run a whole list no longer has is dropped with the rest.
        store.replace(GW, listOf(run(at = 4_000)), complete = true)
        assertEquals(listOf(4_000L), store.state.value.runs.map { it.at })
    }

    @Test
    fun runsOfJobsNoLongerFollowedAreDropped() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 0)
        store.replace(GW, listOf(run(job = "a", at = 3_000), run(job = "b", at = 2_000)), complete = true)

        store.prune(GW, Following().with("a", Follow.Notify)::covers)
        assertEquals(listOf("a"), store.state.value.runs.map { it.job })

        store.prune(GW, Following().all(Follow.Off)::covers)
        assertEquals(emptyList(), store.state.value.runs)
        // The gateway lists nothing either, so nothing comes back.
        store.replace(GW, emptyList(), complete = true)
        assertEquals(emptyList(), store.state.value.runs)
    }

    @Test
    fun readingARunInFullReplacesItsShortCopy() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 0)
        store.add(GW, run(text = "Short"))
        store.add(GW, run(text = "Short and the rest"))
        assertEquals(listOf("Short and the rest"), store.state.value.runs.map { it.text })
    }

    @Test
    fun theInboxIsKeptPerGatewayAndAcrossRestarts() = runTest {
        val disk = InMemoryKeyValueStore()
        val first = InboxStore(disk)
        first.bind("https://one", now = 0)
        first.add("https://one", run(at = 10, text = "x".repeat(5_000)))
        first.markAllRead("https://one")

        val again = InboxStore(disk)
        again.bind("https://one", now = 99)
        assertEquals(1, again.state.value.runs.size)
        assertEquals(0, again.state.value.unread)
        // Only the start of a long run is kept on the phone.
        assertEquals(InboxStore.MAX_KEPT_TEXT, again.state.value.runs.single().text.length)

        val other = InboxStore(disk)
        other.bind("https://two", now = 0)
        assertTrue(other.state.value.runs.isEmpty())
    }

    @Test
    fun aPushForAnotherGatewayGoesToItsInboxWithoutMovingTheStore() = runTest {
        val disk = InMemoryKeyValueStore()
        val store = InboxStore(disk)
        store.bind("https://one", now = 0)
        store.add("https://one", run(job = "mine", at = 10))
        val two = InboxStore(disk)
        two.bind("https://two", now = 0)
        two.editGroups("https://two") { it.renameJob("default", "theirs", "Their name", own = "morning-briefing") }

        // The app switched to one while a push signed by two was being opened.
        val groups = store.receive("https://two", run(job = "theirs", at = 20))
        assertEquals("https://one", store.bound)
        assertEquals(listOf("mine"), store.state.value.runs.map { it.job })
        // Two's own name for the job, for the notification.
        assertEquals("Their name", groups.nameOf(run(job = "theirs", at = 20)))
        // It is there when the app goes back to two.
        store.bind("https://two")
        assertEquals(listOf("theirs"), store.state.value.runs.map { it.job })
    }

    @Test
    fun aPushStartsAnInboxJustBeforeItsRunSoTheRunIsUnread() = runTest {
        val disk = InMemoryKeyValueStore()
        InboxStore(disk).receive(GW, run(at = 5_000))
        val store = InboxStore(disk)
        store.bind(GW, now = 9_999)
        assertEquals(1, store.state.value.unread)
    }

    @Test
    fun linksAreConsumedOnceUnlessANewerOneCame() {
        val links = InboxLinks()
        links.open("a")
        val first = links.pending.value!!
        links.open("b")
        links.consume(first)
        assertEquals("b", links.pending.value?.key)
        links.consume(links.pending.value!!)
        assertNull(links.pending.value)
    }

    @Test
    fun groupsAreNamedOnceAndRenamingCarriesTheirJobs() {
        var groups = InboxGroups().add(" RUBIX ").add("rubix").add("").add("Personal")
        assertEquals(listOf("RUBIX", "Personal"), groups.names)
        groups = groups.assign("default", "pulse", "RUBIX").assign("default", "brief", "Personal")
        assertEquals("RUBIX", groups.of("default", "pulse"))
        // Another profile's job of the same id is another job.
        assertNull(groups.of("siaulietis", "pulse"))

        groups = groups.rename("RUBIX", "Bar")
        assertEquals(listOf("Bar", "Personal"), groups.names)
        assertEquals("Bar", groups.of("default", "pulse"))
        // A rename onto a taken name changes nothing.
        assertEquals(groups, groups.rename("Bar", "personal"))
    }

    @Test
    fun removingAGroupFreesItsJobsAndAJobCanLeaveItsGroup() {
        val groups = InboxGroups().add("RUBIX").assign("default", "pulse", "RUBIX").assign("default", "radar", "RUBIX")
        assertNull(groups.remove("RUBIX").of("default", "pulse"))
        assertNull(groups.assign("default", "pulse", null).of("default", "pulse"))
        assertEquals("RUBIX", groups.assign("default", "pulse", null).of("default", "radar"))
        // A group that doesn't exist can't be joined.
        assertNull(InboxGroups().assign("default", "pulse", "Nope").of("default", "pulse"))
    }

    @Test
    fun groupsAndTheChipModeAreKeptWithTheInbox() = runTest {
        val disk = InMemoryKeyValueStore()
        val first = InboxStore(disk)
        first.bind("https://gw", now = 0)
        first.editGroups(GW) { it.add("RUBIX").assign("default", "pulse", "RUBIX").copy(mode = ChipMode.Groups) }

        val again = InboxStore(disk)
        again.bind("https://gw", now = 0)
        assertEquals(ChipMode.Groups, again.state.value.groups.mode)
        assertEquals("RUBIX", again.state.value.groups.of("default", "pulse"))
    }

    @Test
    fun anInboxSavedBeforeGroupsLoadsWithNone() = runTest {
        val disk = InMemoryKeyValueStore()
        disk.put("inbox.v1.https://gw", """{"runs":[],"read":[],"since":5}""")
        val store = InboxStore(disk)
        store.bind("https://gw", now = 99)
        assertEquals(InboxGroups(), store.state.value.groups)
        assertEquals(5, store.state.value.since)
    }

    @Test
    fun aJobRenamedOnThePhoneShowsItsNewNameAndResetPutsItsOwnBack() {
        val pulse = InboxRun(bot = "default", job = "ebfdc86e6536", run = "2026-10-12_13-00-00", label = "rubix-ticket-pulse")
        var groups = InboxGroups().renameJob("default", "ebfdc86e6536", "  🎟️ Ticket pulse  ", own = pulse.title)
        assertEquals("🎟️ Ticket pulse", groups.nameOf(pulse))
        assertEquals("🎟️ Ticket pulse", groups.renamedOf("default", "ebfdc86e6536"))
        // Only that job, in that profile.
        assertEquals("rubix-ticket-pulse", groups.nameOf(pulse.copy(bot = "siaulietis")))

        groups = groups.renameJob("default", "ebfdc86e6536", "", own = pulse.title)
        assertEquals("rubix-ticket-pulse", groups.nameOf(pulse))
        // Renaming to its own name is the same as no rename.
        assertNull(InboxGroups().renameJob("default", "ebfdc86e6536", "rubix-ticket-pulse", own = pulse.title).renamedOf("default", "ebfdc86e6536"))
        assertEquals(InboxGroups.MAX_NAME, InboxGroups().renameJob("d", "j", "x".repeat(99), own = "j").renamedOf("d", "j")!!.length)
    }

    @Test
    fun aRoutinesBotTagIsLeftOutOfItsName() {
        val routine = InboxRun(bot = "dex", job = "j", run = "2026-10-12_13-00-00", label = "[bot:dex] Daily check")
        assertEquals("Daily check", routine.title)
        assertEquals("j", InboxRun(job = "j", run = "2026-10-12_13-00-00", label = "[bot:dex] ").title)
    }

    @Test
    fun renamesAreKeptAcrossRestarts() = runTest {
        val disk = InMemoryKeyValueStore()
        val first = InboxStore(disk)
        first.bind("https://gw", now = 0)
        first.editGroups(GW) { it.renameJob("default", "j", "Briefing", own = "morning-briefing") }
        val again = InboxStore(disk)
        again.bind("https://gw", now = 0)
        assertEquals("Briefing", again.state.value.groups.renamedOf("default", "j"))
    }

    @Test
    fun aChatReportKeepsItsLineBreaks() {
        val pulse = "🎫 **TICKET PULSE**\n━━━━━━\n\n**MINO** · 2026-12-04\n🎟️ 0 tickets · €0.00\n📊 Site: 0\n\n───"
        assertEquals(
            "🎫 **TICKET PULSE**  \n━━━━━━\n\n**MINO** · 2026-12-04  \n🎟️ 0 tickets · €0.00  \n📊 Site: 0\n\n───",
            keepLineBreaks(pulse),
        )
    }

    @Test
    fun codeTablesAndBreaksAlreadyThereAreLeftAlone() {
        val doc = "Before\n```\na\nb\n```\n| A | B |\n|---|---|\n| 1 | 2 |\nkept  \nlast"
        assertEquals("Before  \n```\na\nb\n```\n| A | B |\n|---|---|\n| 1 | 2 |\nkept  \nlast", keepLineBreaks(doc))
        assertEquals("", keepLineBreaks(""))
        assertEquals("one line", keepLineBreaks("one line"))
    }

    @Test
    fun noGroupCanBeNamedLikeTheOtherFolder() {
        val groups = InboxGroups().add("RUBIX")
        // "Other" is the folder of jobs in no group, and "null" reads as no group: either would make two folders one.
        listOf("Other", " other ", "OTHER", "null", "Null").forEach { name ->
            assertEquals(groups, groups.add(name), name)
            assertEquals(groups, groups.rename("RUBIX", name), name)
            assertEquals(InboxGroups.NameRefusal.Reserved, groups.refusal(name))
        }
        assertEquals(listOf("RUBIX", "Others"), groups.add("Others").names)
    }

    @Test
    fun aGroupNameIsJudgedAsItIsKept() {
        val long = "x".repeat(InboxGroups.MAX_NAME)
        val groups = InboxGroups().add(long)
        // Longer than a name keeps, it is the same name once cut, so it is taken.
        assertEquals(InboxGroups.NameRefusal.Taken, groups.refusal(long + "yyy"))
        assertEquals(groups, groups.add(long + "yyy"))
        assertNull(groups.refusal(long, renaming = long))
        assertEquals(InboxGroups.NameRefusal.Blank, groups.refusal("   "))
        assertEquals(listOf("Bar"), InboxGroups().add("RUBIX").rename("RUBIX", "  Bar ").names)
    }

    @Test
    fun aSaveTheGatewayAnswersWithLessIsNotEchoed() {
        val allOn = Following().all(Follow.Quiet)
        // Plugin 0.4.0 has no default: it answers an All on with nothing followed.
        assertFalse(allOn.echoedBy(Following()))
        assertTrue(allOn.echoedBy(Following(default = Follow.Quiet)))
        val one = Following().with("a", Follow.Notify).copy(failures = true)
        assertTrue(one.echoedBy(Following(jobs = mapOf("a" to Follow.Notify), failures = true)))
        assertFalse(one.echoedBy(Following(jobs = mapOf("a" to Follow.Notify))))
        assertFalse(one.echoedBy(Following(jobs = mapOf("a" to Follow.Quiet), failures = true)))
    }

    @Test
    fun aListKeepsTheLongerTextOfARunAndDropsOnlyWhatItSaysIsGone() {
        val long = run(at = 3_000, text = "Morning, and the whole of the briefing")
        val listed = run(at = 3_000, text = "Morning")
        // A pushed or fully read copy keeps its text when the list brings only its start.
        assertEquals(listOf(long.text), InboxStore.mergeListed(listOf(long), listOf(listed), complete = true).map { it.text })

        val pushedOld = run(job = "pushed", at = 1_000)
        val newer = run(job = "newer", at = 9_000)
        val page = listOf(run(at = 5_000), run(at = 4_000))
        // A list that stopped short says nothing of the runs before it.
        assertEquals(listOf(9_000L, 5_000L, 4_000L, 1_000L), InboxStore.mergeListed(listOf(newer, pushedOld), page, complete = false).map { it.at })
        // A whole one does: the older run is gone from the gateway; one newer than the list may only be ahead of it.
        assertEquals(listOf(9_000L, 5_000L, 4_000L), InboxStore.mergeListed(listOf(newer, pushedOld), page, complete = true).map { it.at })
        // A whole empty list is the gateway saying there is nothing.
        assertEquals(emptyList(), InboxStore.mergeListed(listOf(newer, pushedOld), emptyList(), complete = true))
    }

    @Test
    fun aReadRunStaysReadAfterItLeavesTheListAndComesBack() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind(GW, now = 0)
        val old = run(job = "old", at = 1_000)
        store.replace(GW, listOf(old), complete = true)
        store.markRead(GW, old)
        // Off the list for a while (a page further back, or a job switched off and on again)...
        store.replace(GW, listOf(run(job = "new", at = 2_000)), complete = true)
        store.markRead(GW, store.state.value.runs.first())
        // ...and back: still read.
        store.replace(GW, listOf(run(job = "new", at = 2_000), old), complete = true)
        assertEquals(0, store.state.value.unread)
    }

    @Test
    fun theReadSetForgetsTheOldestRunsFirst() {
        val read = (0 until InboxStore.MAX_READ + 5).map { "default/j/2026-10-12_00-00-${it.toString().padStart(4, '0')}" }.toSet()
        val trimmed = InboxStore.trimRead(read)
        assertEquals(InboxStore.MAX_READ, trimmed.size)
        assertFalse("default/j/2026-10-12_00-00-0000" in trimmed)
        assertTrue("default/j/2026-10-12_00-00-${(InboxStore.MAX_READ + 4).toString().padStart(4, '0')}" in trimmed)
    }

    @Test
    fun aChangeForAGatewayTheStoreHasLeftIsDropped() = runTest {
        val disk = InMemoryKeyValueStore()
        val store = InboxStore(disk)
        store.bind("https://one", now = 0)
        store.bind("https://two", now = 0)
        // A list read, or a push, that started for the first gateway lands after the switch.
        store.replace("https://one", listOf(run(at = 5)), complete = true)
        store.add("https://one", run(job = "late", at = 6))
        store.editGroups("https://one") { it.add("RUBIX") }
        assertEquals(emptyList(), store.state.value.runs)
        assertEquals(InboxGroups(), store.state.value.groups)
        store.bind("https://one", now = 0)
        assertEquals(emptyList(), store.state.value.runs)
    }

    @Test
    fun aForgottenGatewaysInboxGoesWithIt() = runTest {
        val disk = InMemoryKeyValueStore()
        val store = InboxStore(disk)
        store.bind(GW, now = 0)
        store.add(GW, run())
        store.forget(GW)
        assertNull(disk.get(InboxStore.keyFor(GW)))
        assertNull(store.bound)
        assertEquals(emptyList(), store.state.value.runs)
    }

    @Test
    fun aRunListThatNoLongerReadsKeepsTheGroupsAndNames() = runTest {
        val disk = InMemoryKeyValueStore()
        disk.put(
            InboxStore.keyFor(GW),
            """{"runs":[{"nope":1}],"read":["a"],"since":7,"groups":{"names":["RUBIX"],"renamed":{"default/j":"Brief"}}}""",
        )
        val store = InboxStore(disk)
        store.bind(GW, now = 99)
        assertEquals(emptyList(), store.state.value.runs)
        assertEquals(listOf("RUBIX"), store.state.value.groups.names)
        assertEquals("Brief", store.state.value.groups.renamedOf("default", "j"))
        assertEquals(7, store.state.value.since)
    }

    @Test
    fun theFirstPushedRunOfANewInboxIsUnread() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        val pushed = run(at = 1_760_000_000)
        // As the push listener starts an Inbox: from just before the run, on the gateway's clock.
        store.bind(GW, now = pushed.at - 1)
        store.add(GW, pushed)
        assertEquals(1, store.state.value.unread)
    }
}
