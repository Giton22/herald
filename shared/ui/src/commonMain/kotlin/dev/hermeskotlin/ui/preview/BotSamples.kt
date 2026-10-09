package dev.hermeskotlin.ui.preview

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.core.rooms.RoomMember
import dev.hermeskotlin.ui.bots.BotsUiState
import dev.hermeskotlin.ui.bots.NeedsYou
import dev.hermeskotlin.ui.rooms.OpenRoom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/** A made-up bot roster and room for the Bots scenes: no real gateway's bots or chats. */
internal object BotSamples {
    /** Read once, so every time on a scene is drawn against the same moment. */
    val now: Double = Clock.System.now().epochSeconds.toDouble()

    // Marked custom, so the default bot wears its color instead of the stock violet.
    private fun look(title: String, shape: String, color: String) = JsonObject(
        mapOf(
            "hermes-bots" to JsonObject(
                mapOf(
                    "title" to JsonPrimitive(title),
                    "shape" to JsonPrimitive(shape),
                    "color" to JsonPrimitive(color),
                    "custom" to JsonPrimitive(true),
                ),
            ),
        ),
    )

    private fun chat(id: String, preview: String, minutesAgo: Int) =
        BotSession(id = id, title = preview, preview = preview, lastActive = now - minutesAgo * 60)

    val hermes = Bot(
        name = Bot.DEFAULT,
        isDefault = true,
        uiMeta = look("Hermes", "squircle", "#2F6BF5"),
        canonicalSession = chat("b-hermes", "Backup finished: 42 GB, no errors", 4),
        // A worker heartbeat a moment ago: it's busy in the background.
        workerSession = BotSession(id = "w-hermes", lastActive = now - 30),
    )
    val ops = Bot(
        name = "ops",
        uiMeta = look("Ops", "hexagon", "#F0883E"),
        canonicalSession = chat("b-ops", "Restart the media server?", 12),
    )
    val scribe = Bot(
        name = "scribe",
        uiMeta = look("Scribe", "circle", "#8B5CF6"),
        canonicalSession = chat("b-scribe", "Meeting notes are in your inbox", 95),
    )
    val cadence = Bot(
        name = "cadence",
        uiMeta = look("Cadence", "pill", "#22D3EE"),
        canonicalSession = chat("b-cadence", "Next run tomorrow at 7:00", 60 * 26),
    )

    val needsYou: List<NeedsYou> = listOf(NeedsYou.Answer(ops, Waiting.Approval))

    val roster = BotsUiState(
        bots = listOf(hermes, ops, scribe, cadence),
        loading = false,
        unread = setOf("scribe"),
    )

    private fun member(bot: Bot) = RoomMember(memberId = bot.name, profile = bot.name, handle = bot.name, displayName = bot.label)

    val weekend = Room(
        roomId = "r-weekend",
        name = "Weekend plans",
        members = listOf(member(hermes), member(scribe), member(cadence)),
        latestSeq = 5,
        updatedAt = now - 8 * 60,
    )
    val homelab = Room(
        roomId = "r-homelab",
        name = "Homelab upgrades",
        members = listOf(member(ops), member(hermes)),
        latestSeq = 12,
        updatedAt = now - 60 * 60 * 3,
    )
    val rooms = listOf(weekend, homelab)

    val openRoom = OpenRoom(
        room = weekend,
        loading = false,
        lines = listOf(
            RoomLine.Message(1, fromUser = true, speaker = null, text = "We're in Vienna Saturday. Ideas for the afternoon?", eventId = "e1", createdAt = now - 14 * 60),
            RoomLine.Message(
                2,
                fromUser = false,
                speaker = "Hermes",
                text = "The Albertina has a new exhibition, and it's a short walk from the Naschmarkt for lunch.",
                eventId = "e2",
                profile = "default",
                createdAt = now - 13 * 60,
            ),
            RoomLine.Message(
                3,
                fromUser = false,
                speaker = "Scribe",
                text = "I'll keep a list. Saturday so far: Naschmarkt lunch, then the Albertina.",
                eventId = "e3",
                profile = "scribe",
                createdAt = now - 12 * 60,
            ),
            RoomLine.Message(4, fromUser = true, speaker = null, text = "@cadence can you check the opening hours?", eventId = "e4", createdAt = now - 9 * 60),
        ),
        working = true,
        waiting = listOf(member(cadence)),
    )
}
