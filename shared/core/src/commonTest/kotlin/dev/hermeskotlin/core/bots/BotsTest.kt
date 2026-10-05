package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.network.HermesJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotsTest {

    // Trimmed from a live v0.21.5 reply: Desktop wrote a look for test2 only.
    private val reply = HermesJson.parseToJsonElement(
        """{"profiles":[
          {"name":"default","path":"/opt/data","is_default":true,"model":"m","provider":"p","description":"","display_name":"",
           "skill_count":63,"previous_names":[],"role":null,
           "last_session":{"id":"cron_1","title":"Tickets","preview":"x","started_at":1791162032.7,"last_active":1791162154.9,"message_count":14},
           "worker_session":null,
           "canonical_session":{"id":"20261003_080246_714f77","resolved_id":"20261003_080246_714f77","root_title":"Bot Chat",
             "title":"Bot Chat","preview":"**Everything** in `one` bundle","started_at":1791014566.4,"last_active":1791162918.7,"message_count":81},
           "ui_meta_revisions":{},"has_avatar":true},
          {"name":"test1","is_default":false,"last_session":null,"worker_session":{"id":"w","source":"kanban","last_active":1791162900.0},
           "canonical_session":{"id":"a","resolved_id":"b","last_active":1791162629.0},"ui_meta_revisions":{},"has_avatar":true},
          {"name":"test2","is_default":false,"canonical_session":null,
           "ui_meta_revisions":{"hermes-bots":1},
           "ui_meta":{"hermes-bots":{"shape":"squircle","color":"hsl(210 68% 58%)","imageKind":"shape","title":"","created":1791161923706}},
           "has_avatar":true},
          {"name":"old-helper","ui_meta":{"hermes-bots":{"title":"Old Helper","hidden":true}}},
          "not a profile"
        ],"bot_mode_protocol":true}""",
    )

    @Test
    fun readsTheRosterAndSkipsWhatIsntAProfile() {
        val roster = parseBotRoster(reply)
        assertTrue(roster.teammateProtocol)
        assertEquals(listOf("default", "test1", "test2", "old-helper"), roster.bots.map { it.name })
        val default = roster.bots[0]
        assertEquals("20261003_080246_714f77", default.canonicalSession?.openId)
        assertEquals("cron_1", default.lastSession?.id)
        assertTrue(default.hasAvatar)
    }

    @Test
    fun theChatOpensAtTheLiveEndOfItsLineage() {
        val test1 = parseBotRoster(reply).bots[1]
        assertEquals("a", test1.canonicalSession?.id)
        assertEquals("b", test1.canonicalSession?.openId)
        assertNull(parseBotRoster(reply).bots[2].canonicalSession)
    }

    @Test
    fun namesFollowDesktop() {
        val bots = parseBotRoster(reply).bots
        assertEquals("Hermes", bots[0].label)
        assertEquals("Test1", bots[1].label)
        // An empty Bot Mode title doesn't hide the name.
        assertEquals("Test2", bots[2].label)
        assertEquals("Old Helper", bots[3].label)
        val renamed = HermesJson.decodeFromString(Bot.serializer(), """{"name":"code-review_bot","display_name":" Reviewer "}""")
        assertEquals("Reviewer", renamed.label)
        assertEquals("Code Review Bot", HermesJson.decodeFromString(Bot.serializer(), """{"name":"code-review_bot"}""").label)
    }

    @Test
    fun metaIsReadLeniently() {
        val meta = BotMeta.of(HermesJson.parseToJsonElement("""{"title":42,"shape":"hexagon","hidden":"yes","custom":true,"imageKind":"photo"}"""))
        assertEquals(BotMeta(title = null, shape = "hexagon", custom = true, hidden = false, imageKind = "photo"), meta)
        assertEquals(BotMeta(), BotMeta.of(HermesJson.parseToJsonElement("[]")))
    }

    @Test
    fun rosterPutsPinnedFirstThenTheNewest() {
        val bots = parseBotRoster(reply).bots
        // Hidden bots keep their place in the order; the roster shows them in their own section.
        assertEquals(listOf("default", "test1", "test2", "old-helper"), bots.forRoster().map { it.name })
        assertTrue(bots.forRoster().last().meta.hidden)
        // A bot just made outranks older chats; a pinned one outranks everything.
        val fresh = HermesJson.decodeFromString(Bot.serializer(), """{"name":"fresh","ui_meta":{"hermes-bots":{"created":1791170000000}}}""")
        val pinned = HermesJson.decodeFromString(Bot.serializer(), """{"name":"zed","ui_meta":{"hermes-bots":{"pinned":true}}}""")
        assertEquals(listOf("zed", "fresh", "default", "test1", "test2", "old-helper"), (bots + fresh + pinned).forRoster().map { it.name })
        // Background work counts as activity.
        assertEquals(1791162900.0, bots[1].lastActivity())
    }

    @Test
    fun workingMeansAWorkerHeartbeatInTheLastTwoMinutes() {
        val test1 = parseBotRoster(reply).bots[1]
        assertTrue(test1.isWorking(nowSeconds = 1791162960.0))
        assertFalse(test1.isWorking(nowSeconds = 1791163100.0))
        assertFalse(parseBotRoster(reply).bots[0].isWorking(nowSeconds = 1791162960.0))
    }

    @Test
    fun previewDropsMarkdown() {
        assertEquals("Everything in one bundle", parseBotRoster(reply).bots[0].previewLine())
        assertNull(parseBotRoster(reply).bots[2].previewLine())
    }
}
