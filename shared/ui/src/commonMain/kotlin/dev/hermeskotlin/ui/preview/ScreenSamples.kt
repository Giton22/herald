package dev.hermeskotlin.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.ScreenStage
import dev.hermeskotlin.ui.screen.ScreenUiState

/** The bot's screen, drawn from a made-up desktop. */
object ScreenSamples {

    /**
     * Where the viewer opens the screen. A preview never opens it — it draws a made-up picture instead —
     * but a screen that is being watched has one, and the pane only draws the picture when it has.
     */
    private const val SAMPLE_STREAM = "ws://screen.example/api/display/ws?display_ticket=sample"

    /** The agent is working and the user is watching. */
    val watching = ScreenUiState(
        status = status(DisplayLease.AGENT),
        lease = DisplayLease(DisplayLease.AGENT),
        streamUrl = SAMPLE_STREAM,
        stage = ScreenStage.Connected,
    )

    /** The user has taken the screen over. */
    val inControl = ScreenUiState(
        status = status(DisplayLease.HUMAN),
        lease = DisplayLease(DisplayLease.HUMAN, viewerHash = "0f2c"),
        streamUrl = SAMPLE_STREAM,
        stage = ScreenStage.Connected,
        holding = true,
    )

    /** The screen, looked at closer than the whole of it. */
    val zoomed = watching.copy(zoom = 2f)

    /** The screen is stopped, so there is nothing to watch. */
    val stopped = ScreenUiState(
        status = status(DisplayLease.AGENT).copy(running = false),
        lease = DisplayLease(DisplayLease.AGENT),
        stage = ScreenStage.Idle,
    )

    private fun status(holder: String) = DisplayStatus(
        profile = "scribe",
        supported = true,
        installed = true,
        running = true,
        display = ":20",
        geometry = "1440x900",
        placement = "gateway",
        lease = DisplayLease(holder),
        profileKey = "scribe",
    )
}

/**
 * The picture the viewer draws, made up: a desktop with a sign-in window on it, the way a bot waiting for a
 * human looks. The live screen is drawn by the viewer itself, which a preview cannot run.
 *
 * The desktop is wider than the pane, so it sits in a band in the middle of it, the way the viewer shows it.
 * [zoom] draws it larger and cuts off what does not fit, the way the viewer looks at a part of the screen.
 */
@Composable
fun SampleScreen(modifier: Modifier = Modifier, zoom: Float = 1f) {
    Box(
        modifier.background(Color(0xFF0B0B0D)).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(Color(0xFF1B1E24))
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The window on the desktop.
            Column(
                Modifier.fillMaxWidth(0.55f).aspectRatio(1.4f).background(Color(0xFFF2F4F8)),
            ) {
                // The window's title bar.
                Box(Modifier.fillMaxWidth().height(22.dp).background(Color(0xFF3B6EA5)))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The field the user has to fill in.
                    Box(Modifier.fillMaxWidth().height(26.dp).background(Color.White))
                    // The button.
                    Box(Modifier.width(96.dp).height(28.dp).background(Color(0xFF3B6EA5)))
                }
            }
        }
    }
}
