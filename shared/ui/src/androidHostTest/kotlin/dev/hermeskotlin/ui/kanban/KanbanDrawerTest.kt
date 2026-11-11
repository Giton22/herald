package dev.hermeskotlin.ui.kanban

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.kanban.KanbanBoardResponse
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.KanbanTaskDetailResponse
import dev.hermeskotlin.designsystem.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * The drawer's reachable states (M1/M2): opening a task owns the whole page while the detail loads, shows
 * its own error with a retry aimed at the detail (never the log), and a gateway with no boards says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KanbanDrawerTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library has no manifest declaring the activity that hosts the page.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private val board = KanbanBoardResponse(
        columns = listOf(KanbanColumn("ready", listOf(KanbanTask(id = "t_1", title = "A board card", status = "ready")))),
        tenants = emptyList(),
        assignees = emptyList(),
        latestEventId = 1,
        now = 1,
    )

    private fun state(
        openTaskId: String? = null,
        detail: KanbanTaskDetailResponse? = null,
        detailLoading: Boolean = false,
        detailError: String? = null,
        boardsEmpty: Boolean = false,
    ) = KanbanUiState(
        boards = if (boardsEmpty) emptyList() else listOf(dev.hermeskotlin.core.kanban.KanbanBoardMeta(slug = "main", name = "Main", total = 1)),
        boardsLoading = false,
        board = if (boardsEmpty) null else "main",
        response = if (boardsEmpty) null else board,
        loading = false,
        openTaskId = openTaskId,
        detail = detail,
        detailLoading = detailLoading,
        detailError = detailError,
    )

    private fun show(state: KanbanUiState, onReloadDetail: () -> Unit = {}, onReloadLog: () -> Unit = {}) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                KanbanView(
                    state = state,
                    searchField = remember { TextFieldState("") },
                    onBack = {},
                    onSelectBoard = {},
                    onSearch = {},
                    onTenant = {},
                    onAssignee = {},
                    onIncludeArchived = {},
                    onOpenTask = {},
                    onCloseTask = {},
                    onRetry = {},
                    onReloadDetail = onReloadDetail,
                    onReloadLog = onReloadLog,
                )
            }
        }
        settle()
    }

    @Test
    fun aLoadingDetailOwnsThePage() {
        show(state(openTaskId = "t_1", detailLoading = true))
        // The board is gone, and neither the error nor its retry is offered while loading.
        composeRule.onNodeWithText("A board card").assertDoesNotExist()
        composeRule.onNodeWithText("Couldn’t load Kanban").assertDoesNotExist()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun aFailedDetailShowsItsErrorAndRetriesTheDetail() {
        var detailRetries = 0
        var logRetries = 0
        show(state(openTaskId = "t_1", detailError = "HTTP 404"), onReloadDetail = { detailRetries++ }, onReloadLog = { logRetries++ })
        composeRule.onNodeWithText("A board card").assertDoesNotExist()
        composeRule.onNodeWithText("HTTP 404").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        settle()
        assertEquals(1, detailRetries)
        assertEquals(0, logRetries)
    }

    @Test
    fun aLoadedDetailShowsTheDrawerNotTheBoard() {
        show(
            state(
                openTaskId = "t_1",
                detail = KanbanTaskDetailResponse(task = KanbanTask(id = "t_1", title = "The drawer task", status = "ready")),
            ),
        )
        composeRule.onNodeWithText("The drawer task").assertIsDisplayed()
        composeRule.onNodeWithText("A board card").assertDoesNotExist()
    }

    @Test
    fun aStaleLoadedDetailWarnsAboveTheKeptTask() {
        show(
            state(
                openTaskId = "t_1",
                detail = KanbanTaskDetailResponse(task = KanbanTask(id = "t_1", title = "The drawer task", status = "ready")),
                detailError = "HTTP 500",
            ),
        )
        // The quiet-failure strip speaks of the task (never the board), and the loaded task
        // stays visible under it.
        composeRule.onNodeWithText("Couldn’t refresh this task — showing the last load.").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn’t refresh the board — showing the last load.").assertDoesNotExist()
        composeRule.onNodeWithText("The drawer task").assertIsDisplayed()
        // The strip is a fixed warning, not the raw error: the failure message stays out of the drawer.
        composeRule.onNodeWithText("HTTP 500").assertDoesNotExist()
    }

    @Test
    fun aGatewayWithNoBoardsSaysSo() {
        show(state(boardsEmpty = true))
        composeRule.onNodeWithText("No boards").assertIsDisplayed()
        composeRule.onNodeWithText("No Kanban board is on this gateway yet.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theBoardSwitcherListsEveryBoardWithItsCount() {
        show(
            KanbanUiState(
                boards = listOf(
                    dev.hermeskotlin.core.kanban.KanbanBoardMeta(slug = "a", name = "Alpha", total = 3),
                    dev.hermeskotlin.core.kanban.KanbanBoardMeta(slug = "b", name = "Beta", total = 7),
                ),
                boardsLoading = false,
                board = "a",
                response = board,
                loading = false,
            ),
        )
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        // KanbanBoardMeta.total rides the chips' count slot, as on the dashboard.
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("7").assertIsDisplayed()
    }

    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.waitForIdle()
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }
}
