package dev.hermeskotlin.lab

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Every screen the lab renders: file name and content. */
val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
    *Look.entries.flatMap { look ->
        listOf(
            "${look.ordinal}-${look.name.lowercase()}-reply" to @Composable { SignalTheme { ReplyScreen(look) } },
            "${look.ordinal}-${look.name.lowercase()}-sidebar" to @Composable { SignalTheme { SidebarScreen(look) } },
            "${look.ordinal}-${look.name.lowercase()}-newchat" to @Composable { SignalTheme { NewChatScreen(look) } },
            "${look.ordinal}-${look.name.lowercase()}-working" to @Composable { SignalTheme { WorkingScreen(look) } },
            "${look.ordinal}-${look.name.lowercase()}-approval" to @Composable { SignalTheme { ApprovalScreen(look) } },
        )
    }.toTypedArray(),
    "5-refined-reply" to { SignalTheme { RefinedReply() } },
    "5-refined-working" to { SignalTheme { RefinedWorking() } },
    "5-refined-approval" to { SignalTheme { RefinedApproval() } },
    "5-refined-newchat" to { SignalTheme { RefinedNewChat() } },
    "5-refined-sidebar" to { SignalTheme { RefinedSidebar() } },
    "6-glass-reply" to { SignalTheme { RefinedReply(glass = true) } },
    "6-glass-working" to { SignalTheme { RefinedWorking(glass = true) } },
    "6-glass-approval" to { SignalTheme { RefinedApproval(glass = true) } },
    "6-glass-newchat" to { SignalTheme { RefinedNewChat(glass = true) } },
    "6-glass-sidebar" to { SignalTheme { RefinedSidebar(glass = true) } },
)

/** Renders at a Pixel 9's 1080×2424 at 2.625x density (411×923dp). */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "out").apply { mkdirs() }
    val only = args.drop(1).toSet()
    screens.filter { only.isEmpty() || it.first in only }.forEach { (name, content) ->
        val scene = ImageComposeScene(1080, 2424, Density(2.625f)) { content() }
        // A few frames so blur layers and fonts settle.
        repeat(3) { scene.render(it * 16_000_000L) }
        val img = scene.render(64_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("rendered $name")
    }
}
