package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.kanban.KanbanEvents
import dev.hermeskotlin.core.widget.WidgetRefreshPipelines
import org.koin.core.annotation.KoinInternalApi
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression witnesses for injected-interface Koin bindings that unit tests miss because they pass
 * fakes straight into constructors and never load the graph:
 *  - v0.16.1 startup crash: `WidgetRefresher` injects [WidgetRefreshPipelines], registered only as
 *    the concrete `WidgetDataAssembler` -> app died in `Application.onCreate`.
 *  - v0.16.2 Kanban crash: `KanbanViewModel` injects [KanbanEvents], registered only as the concrete
 *    `GatewayKanbanEvents` -> opening the Kanban page died in `koinViewModel()`.
 * Both fixes bind the interface (`single { Concrete(...) } bind Interface::class`).
 *
 * This inspects the module's DEFINITIONS rather than starting Koin: the eager singletons
 * (`AttentionTracker`, `SessionWatcher`) launch coroutines at construction, so a full `startKoin`
 * here would be a teardown race. Reading the definition index needs no instance, no platform module
 * and no Android context, so it stays deterministic on every target. The index is Koin internal API
 * (opted in below); if a Koin upgrade changes it, this test breaks loudly rather than silently.
 */
@OptIn(KoinInternalApi::class)
class WidgetKoinBindingTest {

    private fun boundTo(type: kotlin.reflect.KClass<*>): Boolean = coreModule.mappings.values.any { factory ->
        val definition = factory.beanDefinition
        definition.primaryType == type || type in definition.secondaryTypes
    }

    @Test
    fun refreshPipelinesInterfaceHasADefinition() {
        assertTrue(
            boundTo(WidgetRefreshPipelines::class),
            "coreModule must bind WidgetDataAssembler to WidgetRefreshPipelines " +
                "(single { WidgetDataAssembler(...) } bind WidgetRefreshPipelines::class); " +
                "without it the widgets crash the app at startup",
        )
    }

    @Test
    fun kanbanEventsInterfaceHasADefinition() {
        assertTrue(
            boundTo(KanbanEvents::class),
            "coreModule must bind GatewayKanbanEvents to KanbanEvents " +
                "(single { GatewayKanbanEvents(...) } bind KanbanEvents::class); " +
                "without it KanbanViewModel cannot be created and opening Kanban crashes",
        )
    }
}