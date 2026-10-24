package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.ui.bots.BotFaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Which bot a chat's replies are named after. */
class ReplySpeakerTest {

    private val main = Bot(name = "default", isDefault = true, displayName = "Ada")
    private val writer = Bot(name = "writer", displayName = "Quill")
    private val faces = BotFaces(listOf(main, writer))

    @Test
    fun aChatInAProfileIsAnsweredByThatProfilesBot() {
        assertEquals("Quill", replySpeaker(faces, botName = null, profile = "writer", launchProfile = "default")?.name)
    }

    @Test
    fun aChatNamingNoProfileIsAnsweredByTheProfileTheGatewayWasLaunchedAs() {
        assertEquals("Quill", replySpeaker(faces, botName = null, profile = null, launchProfile = "writer")?.name)
        assertEquals("Ada", replySpeaker(faces, botName = null, profile = null, launchProfile = "default")?.name)
    }

    @Test
    fun aBotChatIsAnsweredByItsOwnBot() {
        assertEquals("Quill", replySpeaker(faces, botName = "writer", profile = null, launchProfile = "default")?.name)
    }

    @Test
    fun withoutTheLaunchProfileOrTheBotTheRepliesStayHermes() {
        // Not known yet: no guess at the default row, which may not be the profile answering.
        assertNull(replySpeaker(faces, botName = null, profile = null, launchProfile = null))
        assertNull(replySpeaker(faces, botName = null, profile = "gone", launchProfile = "default"))
        assertNull(replySpeaker(BotFaces(), botName = null, profile = null, launchProfile = "default"))
    }

    @Test
    fun aBotWithNoNameOfItsOwnIsHermesForTheDefaultProfile() {
        val bare = BotFaces(listOf(Bot(name = "default", isDefault = true)))
        assertEquals("Hermes", replySpeaker(bare, botName = null, profile = null, launchProfile = "default")?.name)
    }

    @Test
    fun profileNamesMatchWhateverTheirCase() {
        assertEquals("Quill", replySpeaker(faces, botName = null, profile = "Writer", launchProfile = null)?.name)
    }

    @Test
    fun aRosterRefreshThatChangesNothingDrawnGivesAnEqualSpeaker() {
        // A bot's latest chat moving on rebuilds the roster; the replies mustn't be redrawn for it.
        val busier = BotFaces(listOf(main.copy(description = "now busy"), writer))
        assertEquals(
            replySpeaker(faces, botName = null, profile = null, launchProfile = "default"),
            replySpeaker(busier, botName = null, profile = null, launchProfile = "default"),
        )
        val renamed = BotFaces(listOf(main.copy(displayName = "Grace"), writer))
        assertNotEquals(
            replySpeaker(faces, botName = null, profile = null, launchProfile = "default"),
            replySpeaker(renamed, botName = null, profile = null, launchProfile = "default"),
        )
    }
}
