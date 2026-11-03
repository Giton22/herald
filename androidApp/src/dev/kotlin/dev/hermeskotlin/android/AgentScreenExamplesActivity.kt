package dev.hermeskotlin.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.hermeskotlin.ui.preview.AgentScreenExample
import dev.hermeskotlin.ui.preview.AgentScreenExamples

/**
 * Dev builds only: A2UI's own sample screens (assets/a2ui-examples, from the A2UI specification), drawn as the
 * chat draws an agent's, to try the renderer without an agent.
 * `adb shell am start -n dev.herald.android.dev/dev.hermeskotlin.android.AgentScreenExamplesActivity --ez dark true`
 * (and `--es only 13_coffee-order` for one sample).
 */
class AgentScreenExamplesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra("dark", true)
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        super.onCreate(savedInstanceState)
        val only = intent.getStringExtra("only")
        val examples = assets.list(DIR).orEmpty().sorted()
            .filter { it.endsWith(".json") && (only == null || it.startsWith(only)) }
            .map { file -> AgentScreenExample.parse(file, assets.open("$DIR/$file").bufferedReader().use { it.readText() }) }
        setContent { AgentScreenExamples(examples, dark) }
    }

    private companion object {
        const val DIR = "a2ui-examples"
    }
}
