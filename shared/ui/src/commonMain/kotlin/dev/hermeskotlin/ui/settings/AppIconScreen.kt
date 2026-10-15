package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.sessions.SubpageHeader

/** Settings › App icon: every icon as the launcher draws it; the one tapped goes on the home screen with Apply. */
@Composable
fun AppIconScreen(onBack: () -> Unit) {
    val icons = rememberAppIcons()
    val sizePx = with(LocalDensity.current) { TILE.roundToPx() }
    val pictures by produceState(emptyMap<AppIcon, ImageBitmap>(), icons, sizePx) {
        value = AppIcon.entries.mapNotNull { icon -> icons.picture(icon, sizePx)?.let { icon to it } }.toMap()
    }
    PlatformBackHandler(enabled = true, onBack = onBack)
    AppIconView(
        current = icons.current,
        pictures = pictures,
        onBack = onBack,
        onApply = icons::apply,
    )
}

/** The page itself, stateless so previews can draw it. */
@Composable
internal fun AppIconView(
    current: AppIcon,
    pictures: Map<AppIcon, ImageBitmap>,
    onBack: () -> Unit,
    onApply: (AppIcon) -> Unit,
) {
    // The tapped one; Apply puts it on the home screen. Keyed on current, so it follows an apply.
    var picked by remember(current) { mutableStateOf(current) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("App icon", onBack = onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                AppIconGroup.entries.forEach { group ->
                    Section(group.title) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(10.dp).selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppIcon.entries.filter { it.group == group }.forEach { icon ->
                                IconChoice(icon, pictures[icon], selected = icon == picked, onClick = { picked = icon })
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Herald restarts to switch its icon. Some launchers take it off the home screen; add it again " +
                        "from the app list.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    textAlign = TextAlign.Center,
                )
                Button("Apply", onClick = { onApply(picked) }, enabled = picked != current, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One icon, ringed in the accent when picked, its name underneath. */
@Composable
private fun IconChoice(icon: AppIcon, picture: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .width(TILE + 20.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = icon.label }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(TILE + 10.dp)
                .border(2.5.dp, if (selected) Theme[colors][accent] else Color.Transparent, shape)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.size(TILE))
            } else {
                Box(Modifier.size(TILE).background(Theme[colors][surface2], RoundedCornerShape(18.dp)))
            }
        }
        Text(
            icon.label,
            style = Theme[typography][caption],
            color = if (selected) Theme[colors][accentText] else Theme[colors][textSecondary],
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

private val TILE = 56.dp
