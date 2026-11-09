package dev.hermeskotlin.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.DEFAULT_ACCENT
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.AgentScreenSurface
import dev.hermeskotlin.ui.chat.agentScreenProblem
import dev.hermeskotlin.ui.LocalAppSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/** One of A2UI's own sample screens: its [name], and the messages that draw it, as `phone_show_ui` takes them. */
class AgentScreenExample(val name: String, val messages: List<String>) {
    companion object {
        /** A sample file as the A2UI specification keeps them: `{name, description, messages: [...]}`. */
        fun parse(file: String, json: String): AgentScreenExample {
            val root = HermesJson.parseToJsonElement(json) as JsonObject
            val name = root["name"]?.toString()?.trim('"') ?: file
            return AgentScreenExample(name, root.getValue("messages").jsonArray.map { it.toString() })
        }
    }
}

/**
 * Dev builds only: [examples] drawn as the chat draws an agent's screen, each taking taps, to try the renderer on
 * A2UI's own samples. Under each: what a tap would hand the agent, or why the screen was turned down.
 */
@Composable
fun AgentScreenExamples(examples: List<AgentScreenExample>, dark: Boolean) {
    val palette = AccentPalette.named(DEFAULT_ACCENT)
    hermesTheme(palette)(if (dark) ColorScheme.Dark else ColorScheme.Light) {
        CompositionLocalProvider(
            LocalAccentPalette provides palette,
            LocalAppSettings provides AppSettings(showPet = false),
        ) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            LazyColumn(
                Modifier.fillMaxSize().background(Theme[colors][background]),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 16.dp,
                    bottom = insets.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(examples, key = { it.name }) { ExampleCard(it) }
            }
        }
    }
}

@Composable
private fun ExampleCard(example: AgentScreenExample) {
    val screen = remember(example) {
        AgentScreen.from(SHOW_UI_TOOL, HermesJson.parseToJsonElement("""{"messages":[${example.messages.joinToString(",")}]}"""))
    }
    val context = LocalContext.current
    var problem by remember(example) { mutableStateOf<String?>(null) }
    var answer by remember(example) { mutableStateOf<JsonElement?>(null) }
    LaunchedEffect(screen) { problem = screen?.let { agentScreenProblem(context, it) } }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(example.name, style = Theme[typography][heading], color = Theme[colors][text])
        if (screen == null) {
            Text("Kyllene doesn't read these messages as a screen.", style = Theme[typography][caption], color = Theme[colors][danger])
            return@Column
        }
        AgentScreenSurface(
            key = "example:${example.name}",
            screen = screen,
            live = true,
            takesTaps = true,
            onAnswer = { _, result -> answer = result },
            onFail = { _, message -> problem = message },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(Theme[colors][surface])
                .border(1.dp, Theme[colors][accentSoft], shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        problem?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][danger]) }
        answer?.let {
            Text("The agent would get:", style = Theme[typography][caption], color = Theme[colors][accentText])
            Text(it.toString(), style = Theme[typography][code], color = Theme[colors][textSecondary])
        }
    }
}
