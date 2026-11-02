package dev.hermeskotlin.desktop

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class WarmupTest {

    // The app swallows a scene that fails to draw, so a broken one would only quietly stop warming its page.
    @Test
    fun everyWarmedSceneDrawsOffScreen() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val failed = Warmup.SCENES.mapNotNull { scene ->
                executor.submit<Throwable?> { runCatching { Warmup.draw(scene, dispatcher) }.exceptionOrNull() }.get()
                    ?.let { "$scene: $it" }
            }
            assertEquals(emptyList(), failed)
        } finally {
            executor.shutdownNow()
        }
    }

    // The pages readied first are drawn from the same list; one missing from it would never be readied at all.
    @Test
    fun thePagesReadiedFirstAreWarmedScenes() {
        assertEquals(emptyList(), Warmup.FIRST - Warmup.SCENES.toSet())
    }
}
