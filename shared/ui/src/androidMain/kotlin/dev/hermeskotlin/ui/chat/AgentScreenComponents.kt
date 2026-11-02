package dev.hermeskotlin.ui.chat

import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Companion.WeightProperty
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.BorderStroke
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text

/**
 * Whether this part of the screen is inside a row. A row gives each child the width the ones before it left, so a
 * child there that fills the width would leave none for the rest: only outside rows do fields and rows fill.
 */
private val LocalInRow = staticCompositionLocalOf { false }

/** Whether this part of the screen is inside a card: a card in a card is outlined, so it still stands apart. */
private val LocalInCard = staticCompositionLocalOf { false }

/**
 * A card filled a step above the screen, with no line around it: the screen's own frame is the line. A card
 * inside it has a line and no fill.
 */
internal object ScreenCard : A2uiBasicCatalogV1.Card {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val described = listOfNotNull(accessibility?.label, accessibility?.description).filter { it.isNotBlank() }
        val inner = LocalInCard.current
        Surface(
            modifier = if (described.isEmpty()) modifier else modifier.semantics { contentDescription = described.joinToString(" - ") },
            color = if (inner) Color.Transparent else Theme[colors][surface2],
            border = if (inner) BorderStroke(1.dp, Theme[colors][stroke]) else null,
            shape = RoundedCornerShape(Theme[radii][if (inner) radiusMedium else radiusLarge]),
        ) {
            CompositionLocalProvider(LocalInCard provides true) { CardChild(childId) }
        }
    }

    @Composable
    private fun A2uiComponentScope.CardChild(childId: String) {
        when (val child = observeA2uiComponentState(childId)) {
            is A2uiComponentState.Success -> A2uiComponent(child.component, Modifier.padding(16.dp))
            // The agent hears why through the screen's error; the user needn't read it.
            is A2uiComponentState.Error -> Text(
                "Couldn't draw this part",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            A2uiComponentState.Loading -> Box(Modifier.fillMaxWidth().height(48.dp))
        }
    }
}

/**
 * Material's button, with a filled one for "secondary" too: the accent for the main action, a quiet fill for the
 * others, rather than outlines.
 */
internal object ScreenButton : A2uiBasicCatalogV1.Button {
    private val material = MaterialA2uiBasicCatalogV1Defaults.button

    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String,
        variant: A2uiBasicCatalogV1.Button.Variant,
        action: Map<String, Any?>,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        if (variant != A2uiBasicCatalogV1.Button.Variant.Secondary) {
            with(material) { TypedContent(childId, variant, action, accessibility, checks, modifier) }
            return
        }
        // Drawn as Material's filled button, in the quiet fill; what's inside it keeps the app's text color.
        val scheme = MaterialTheme.colorScheme
        MaterialTheme(colorScheme = scheme.copy(primary = Theme[colors][surface3], onPrimary = Theme[colors][text])) {
            with(material) { TypedContent(childId, A2uiBasicCatalogV1.Button.Variant.Primary, action, accessibility, checks, modifier) }
        }
    }
}

/**
 * Material's field, as wide as its column, and quiet about its checks until the user types in it: a form that
 * opens all red reads as a mistake. Its buttons still say why they're held back.
 */
internal object ScreenTextField : A2uiBasicCatalogV1.TextField {
    private val material = MaterialA2uiBasicCatalogV1Defaults.textField

    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String,
        value: String?,
        variant: A2uiBasicCatalogV1.TextField.Variant,
        validationRegexp: String?,
        onValueChange: (String) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        // As long-lived as what's typed in it: both go with the drawn screen.
        var edited by remember { mutableStateOf(false) }
        val change = remember(onValueChange) {
            { text: String ->
                edited = true
                onValueChange(text)
            }
        }
        with(material) {
            TypedContent(
                label, value, variant, validationRegexp, change, enabled, accessibility,
                if (edited) checks else emptyList(), if (LocalInRow.current) modifier else modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A list. Down the screen it's a plain column: the screen already scrolls in the chat, and a scrolling list in it
 * would have no height to fill. Across, it's Material's, which scrolls sideways.
 */
internal object ScreenList : A2uiBasicCatalogV1.List {
    private val material = MaterialA2uiBasicCatalogV1Defaults.list

    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>,
        direction: A2uiBasicCatalogV1.List.Direction,
        align: A2uiBasicCatalogV1.List.Align,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        if (direction == A2uiBasicCatalogV1.List.Direction.Horizontal) {
            CompositionLocalProvider(LocalInRow provides true, LocalSideways provides true) {
                with(material) { TypedContent(children, direction, align, accessibility, modifier) }
            }
            return
        }
        val described = listOfNotNull(accessibility?.label, accessibility?.description).filter { it.isNotBlank() }
        Column(
            modifier = if (described.isEmpty()) modifier else modifier.semantics { contentDescription = described.joinToString(" - ") },
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = when (align) {
                A2uiBasicCatalogV1.List.Align.Center -> Alignment.CenterHorizontally
                A2uiBasicCatalogV1.List.Align.End -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            val item = if (align == A2uiBasicCatalogV1.List.Align.Stretch) Modifier.fillMaxWidth() else Modifier
            children.forEach { reference ->
                key(reference.id, reference.baseDataPath) { Child(observeA2uiComponentState(reference), item) }
            }
        }
    }
}

@Composable
private fun Child(state: A2uiComponentState, modifier: Modifier) {
    when (state) {
        is A2uiComponentState.Success -> A2uiComponent(state.component, modifier)
        is A2uiComponentState.Error -> Text(
            "Couldn't draw this part",
            modifier,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        A2uiComponentState.Loading -> Box(modifier.height(40.dp))
    }
}

/** Whether this part of the screen scrolls sideways (a horizontal list): there's no width to share out. */
private val LocalSideways = staticCompositionLocalOf { false }

/** Parts that are as wide as they are, as a web page's row leaves them: the rest share the width they leave. */
private val FIXED_WIDTH = setOf("Icon", "Button", "CheckBox", "Divider")

/**
 * A row that shares its width as a web page's does. Material's gives each child all the width the ones before it
 * left, so a long text took the whole row and pushed the rest out of sight; here a part without a `weight` that
 * isn't [FIXED_WIDTH] gets at most an equal share of what the fixed ones leave, and `weight` takes the rest. It
 * spreads its children across the column's width when asked to, rather than its own.
 */
internal object ScreenRow : A2uiBasicCatalogV1.Row {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>,
        justify: A2uiBasicCatalogV1.Row.Justify,
        align: A2uiBasicCatalogV1.Row.Align,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val spread = !LocalInRow.current && justify in setOf(
            A2uiBasicCatalogV1.Row.Justify.SpaceBetween,
            A2uiBasicCatalogV1.Row.Justify.SpaceAround,
            A2uiBasicCatalogV1.Row.Justify.SpaceEvenly,
        )
        val stretch = align == A2uiBasicCatalogV1.Row.Align.Stretch
        val described = listOfNotNull(accessibility?.label, accessibility?.description).filter { it.isNotBlank() }
        val sideways = LocalSideways.current
        val base = if (stretch) Modifier.fillMaxHeight() else Modifier
        Row(
            modifier = (if (spread) modifier.fillMaxWidth() else modifier)
                .then(if (stretch) Modifier.fillMaxHeight() else Modifier)
                .then(if (described.isEmpty()) Modifier else Modifier.semantics { contentDescription = described.joinToString(" - ") }),
            horizontalArrangement = when (justify) {
                A2uiBasicCatalogV1.Row.Justify.Center -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                A2uiBasicCatalogV1.Row.Justify.End -> Arrangement.spacedBy(8.dp, Alignment.End)
                A2uiBasicCatalogV1.Row.Justify.SpaceAround -> Arrangement.SpaceAround
                A2uiBasicCatalogV1.Row.Justify.SpaceBetween -> Arrangement.SpaceBetween
                A2uiBasicCatalogV1.Row.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
                else -> Arrangement.spacedBy(8.dp)
            },
            verticalAlignment = when (align) {
                A2uiBasicCatalogV1.Row.Align.Center -> Alignment.CenterVertically
                A2uiBasicCatalogV1.Row.Align.End -> Alignment.Bottom
                else -> Alignment.Top
            },
        ) {
            CompositionLocalProvider(LocalInRow provides true) {
                children.forEach { reference ->
                    key(reference.id, reference.baseDataPath) {
                        val child = observeA2uiComponentState(reference)
                        val component = (child as? A2uiComponentState.Success)?.component
                        val weight = component?.properties?.get(WeightProperty)?.toFloat()?.takeIf { it > 0f }
                            ?: 1f.takeIf { justify == A2uiBasicCatalogV1.Row.Justify.Stretch }
                        Child(
                            child,
                            when {
                                sideways -> base
                                weight != null -> base.weight(weight)
                                component?.type in FIXED_WIDTH -> base
                                else -> base.weight(1f, fill = false)
                            },
                        )
                    }
                }
            }
        }
    }
}
