package dev.hermeskotlin.core.slash

import dev.hermeskotlin.core.chat.skillInvocationText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlashTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun splitsACommandLikeTheGateway() {
        assertEquals(SlashCommand("goal", "line one\n  line two"), SlashCommand.parse("/Goal  line one\n  line two  "))
        assertEquals(SlashCommand("", ""), SlashCommand.parse("/"))
        assertNull(SlashCommand.parse("/usr/local is a path"))
        assertNull(SlashCommand.parse("run /clean"))
        assertEquals("goal status", SlashCommand("goal", "status").line)
    }

    @Test
    fun catalogKeepsCategoriesAndFilesSkillsSeparately() {
        val catalog = SlashCatalog.parse(
            obj(
                """{"pairs":[["/help","Show commands"],["/usage","Token usage"],["/work","Spin up a worktree"]],
                "categories":[{"name":"Session","pairs":[["/help","Show commands"],["/usage","Token usage"]]}],
                "commands":{"/usage":{"desktop":null},"/skin":{"desktop":"terminal"},"/model":{"desktop":"hidden"}},
                "canon":{"/u":"/usage"}}""",
            ),
        )
        assertEquals(listOf("/help", "/usage", "/work"), catalog.suggestions.map { it.text })
        assertEquals("Session", catalog.suggestions[0].group)
        assertEquals(SlashKind.Skill, catalog.suggestions[2].kind)
        assertEquals("usage", catalog.canonical("u"))
    }

    @Test
    fun routesWhatThisAppAnswersAndWhatItCannot() {
        val catalog = SlashCatalog(
            surfaces = mapOf("/voice" to "composer-voice", "/paste" to "terminal", "/model" to "hidden"),
            canon = mapOf("/q" to "/quit"),
        )
        assertEquals(SlashRoute.NewChat, SlashRoute.of("reset", catalog))
        assertEquals(SlashRoute.Compress, SlashRoute.of("compact", catalog))
        assertEquals(SlashRoute.PickModel, SlashRoute.of("model", catalog))
        assertEquals(SlashRoute.Usage, SlashRoute.of("usage", catalog))
        assertEquals(SlashRoute.Rollback, SlashRoute.of("rollback", catalog))
        // Steered through session.steer here, not the slash worker.
        assertEquals(SlashRoute.Steer, SlashRoute.of("steer", catalog))
        assertEquals(SlashRoute.Gateway, SlashRoute.of("goal", catalog))
        assertEquals(SlashRoute.Gateway, SlashRoute.of("work", null))
        assertIs<SlashRoute.Unavailable>(SlashRoute.of("paste", catalog))
        assertEquals(SlashRoute.Yolo, SlashRoute.of("yolo", catalog))
        assertEquals(SlashRoute.Branch, SlashRoute.of("fork", catalog))
        assertEquals("/wake listens on the gateway computer's microphone, a Desktop feature.", (SlashRoute.of("wake", catalog) as SlashRoute.Unavailable).message)
        assertTrue(SlashRoute.hidden("paste", catalog))
        assertTrue(SlashRoute.hidden("hatch", catalog))
        assertEquals(SlashRoute.Journey, SlashRoute.of("learning", catalog))
        assertEquals(SlashRoute.Skin, SlashRoute.of("skin", catalog))
        assertFalse(SlashRoute.hidden("model", catalog))
        assertFalse(SlashRoute.hidden("usage", catalog))
    }

    @Test
    fun readsEveryDirectiveShape() {
        assertEquals(SlashResult.Output("hi", "careful"), SlashResult.parse(obj("""{"output":"hi","warning":"careful"}""")))
        assertEquals(SlashResult.Output("done"), SlashResult.parse(obj("""{"type":"exec","output":"done"}""")))
        assertEquals(SlashResult.Alias("usage"), SlashResult.parse(obj("""{"type":"alias","target":"/usage"}""")))
        assertEquals(SlashResult.Send("go", "/goal go", "Goal set"), SlashResult.parse(obj("""{"type":"send","message":"go","display":"/goal go","notice":"Goal set"}""")))
        assertEquals(SlashResult.Prefill("again", null), SlashResult.parse(obj("""{"type":"prefill","message":"again"}""")))
        assertNull(SlashResult.parse(obj("""{"type":"send"}""")))
    }

    @Test
    fun completionsBecomeWholeLines() {
        val commands = SlashApi.parseCompletions(
            "/us",
            obj("""{"items":[{"text":"/usage","display":"/usage","meta":"Token usage","kind":"command"},{"text":"use-skill","kind":"skill"}],"replace_from":1}"""),
        )
        assertEquals(listOf("/usage", "/use-skill"), commands.map { it.text })
        assertEquals(SlashKind.Skill, commands[1].kind)

        val options = SlashApi.parseCompletions(
            "/personality al",
            obj("""{"items":[{"text":"alice","display":"alice","meta":"Warm"}],"replace_from":13}"""),
        )
        assertEquals("/personality alice", options.single().text)
        assertEquals("alice", options.single().label)
        assertEquals(SlashKind.Option, options.single().kind)
    }

    @Test
    fun projectsASkillTurnToItsInvocation() {
        val body = "SPIN UP A WORKTREE.\n".repeat(5)
        val single = listOf(
            "[IMPORTANT: The user has invoked the \"work\" skill, indicating they want you to follow its instructions.",
            "The full skill content is loaded below.]",
            "",
            body,
            "",
            "The user has provided the following instruction alongside the skill invocation: fix the\n\nleak",
        ).joinToString("\n")
        assertEquals("/work fix the leak", skillInvocationText(single))
        val bundle = listOf(
            "[IMPORTANT: The user has invoked the \"/clean /work\" stacked skill bundle, loading 2 skills together.]",
            "",
            "User instruction: ship it",
            "",
            "[Loaded as part of the stacked skill invocation \"clean\".]",
            body,
        ).joinToString("\n")
        assertEquals("/clean /work ship it", skillInvocationText(bundle))
        assertNull(skillInvocationText("[IMPORTANT: read the docs]"))
    }
}
