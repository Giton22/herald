package dev.hermeskotlin.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.hermeskotlin.ui.preview.HeraldPreview
import dev.hermeskotlin.ui.preview.PreviewScene
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import java.util.jar.JarFile

/**
 * Readies the app on a background thread soon after launch, so a page opens as quickly the first time as it does
 * the second. The first opening of a page (Settings, Config, a dialog) otherwise waits on the JVM: loading and
 * verifying some hundreds of classes, then running the page's code for the first time, slowly, before it's compiled.
 *
 * Two steps: the app's classes are loaded and linked (verified, no code of theirs run), then its screens are drawn
 * once each from sample data, off screen, which runs the same code the real pages do.
 */
internal object Warmup {
    /**
     * One class from each module to ready, with the packages to take from its jar: the app's own, and Compose's
     * layout, gestures and animation, which every page draws with.
     */
    private val ANCHORS = listOf(
        "dev.hermeskotlin.designsystem.HermesThemeKt" to "dev/hermeskotlin/",
        "dev.hermeskotlin.ui.AppKt" to "dev/hermeskotlin/",
        "dev.hermeskotlin.core.settings.SettingsStore" to "dev/hermeskotlin/",
        "androidx.compose.foundation.ClickableKt" to "androidx/compose/foundation/",
        "androidx.compose.foundation.layout.RowKt" to "androidx/compose/foundation/layout/",
        "androidx.compose.animation.AnimatedVisibilityKt" to "androidx/compose/animation/",
    )

    /** The screens a desktop reaches; the phone's own (voice, the assistant, the Inbox) are left out. */
    internal val SCENES = listOf(
        PreviewScene.Settings, PreviewScene.Config, PreviewScene.ConfigSearch, PreviewScene.ConfigPicker,
        PreviewScene.Insights, PreviewScene.Capabilities, PreviewScene.Plugins, PreviewScene.Scheduled,
        PreviewScene.ScheduledJob, PreviewScene.JobEditor, PreviewScene.Bots, PreviewScene.BotEditor,
        PreviewScene.Room, PreviewScene.NewRoom, PreviewScene.Journey, PreviewScene.Gateways,
        PreviewScene.StartInstruction, PreviewScene.Archived, PreviewScene.Threads, PreviewScene.ProjectOptions,
        PreviewScene.NewProject, PreviewScene.Models, PreviewScene.ModelSearch, PreviewScene.Usage,
        PreviewScene.Processes, PreviewScene.Goal, PreviewScene.Checkpoints, PreviewScene.Comments,
        PreviewScene.Approval, PreviewScene.Tables, PreviewScene.FileMarkdown, PreviewScene.FileCode,
        PreviewScene.FileTable, PreviewScene.ConnectionCheck, PreviewScene.EmptyPage, PreviewScene.LoadError,
    )

    /**
     * The pages opened most, readied before everything else: linking every class first takes some two seconds, which
     * left Settings cold for about four after launch.
     */
    internal val FIRST = listOf(PreviewScene.Settings, PreviewScene.Config, PreviewScene.Plugins, PreviewScene.Scheduled, PreviewScene.Bots)

    /** Starts after [delayMs], once the window is up, so launch itself isn't slowed. */
    fun start(delayMs: Long = 1_500) {
        // One thread for all of it: the scenes' effects resume on their dispatcher, and Compose wants a scene's
        // layout and drawing on one thread only, never the window's.
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "warmup").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        executor.execute { Thread.sleep(delayMs) }
        // A task each, so what a scene's effects queue runs before the next one. The thread is kept (it waits,
        // idle), so nothing a closed scene still hands it is ever refused.
        for (scene in FIRST) executor.execute { drawOnce(scene, dispatcher) }
        executor.execute { linkClasses() }
        for (scene in SCENES - FIRST.toSet()) executor.execute { drawOnce(scene, dispatcher) }
    }

    /** All of it at once, returning when it's done: for [ClassArchive]'s run, which records the classes it loads. */
    fun runNow() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            linkClasses()
            for (scene in SCENES) executor.submit { drawOnce(scene, dispatcher) }.get()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun linkClasses() {
        val loader = Warmup::class.java.classLoader
        for ((anchor, prefix) in ANCHORS) {
            val names = runCatching { classesBeside(Class.forName(anchor, false, loader), prefix) }.getOrDefault(emptyList())
            for (name in names) {
                // Asking for the constructors makes the JVM link the class without running its initialiser.
                runCatching { Class.forName(name, false, loader).declaredConstructors }
            }
        }
    }

    /** [scene] composed, laid out and drawn twice (the second frame runs what the first one's effects changed). */
    private fun drawOnce(scene: PreviewScene, dispatcher: CoroutineDispatcher) {
        // A scene that fails costs only its own warming: the page itself opens as it would have.
        runCatching { draw(scene, dispatcher) }
    }

    internal fun draw(scene: PreviewScene, dispatcher: CoroutineDispatcher) {
        val composed = ImageComposeScene(width = 1280, height = 800, density = Density(1f), coroutineContext = dispatcher) {
            HeraldPreview(scene)
        }
        try {
            composed.render(0).close()
            composed.render(FRAME_NANOS).close()
        } finally {
            composed.close()
        }
    }

    private const val FRAME_NANOS = 16_000_000L

    /** The classes under [prefix] in the jar or folder [anchor] came from. */
    private fun classesBeside(anchor: Class<*>, prefix: String): List<String> {
        val source = File(anchor.protectionDomain.codeSource.location.toURI())
        val paths = if (source.isDirectory) {
            source.walk().filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }.toList()
        } else {
            JarFile(source).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return paths
            .filter { it.startsWith(prefix) && it.endsWith(".class") && !it.endsWith("module-info.class") }
            .map { it.removeSuffix(".class").replace('/', '.') }
    }
}
