package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.showsSidebarPage
import dev.hermeskotlin.core.settings.withSidebarPage
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.ui.sessions.navIcon
import dev.hermeskotlin.ui.sessions.navLabel
import dev.hermeskotlin.ui.sessions.navPages
import dev.hermeskotlin.ui.sessions.shownNavPages
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Settings › Sidebar pages, over the settings page: which page buttons the sidebar has above the chats. */
@Composable
fun SidebarPagesScreen(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    SidebarPagesView(settings, onUpdate, onBack)
}

/** The page itself, stateless so previews can draw it. */
@Composable
internal fun SidebarPagesView(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    desktop: Boolean = isDesktop,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader(stringResource(Res.string.settings_sidebar_pages), onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    stringResource(Res.string.settings_sidebar_pages_intro),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Section(stringResource(Res.string.settings_sidebar_pages)) {
                    SwitchRow(
                        title = stringResource(Res.string.settings_sidebar_pages_show),
                        detail = stringResource(Res.string.settings_sidebar_pages_show_detail),
                        checked = settings.sidebarNav,
                        // Off keeps which pages were left out, for when it's back on.
                        onCheckedChange = { on -> onUpdate { it.copy(sidebarNav = on) } },
                        icon = Lucide.LayoutList,
                    )
                }
                if (settings.sidebarNav) {
                    Section(stringResource(Res.string.settings_sidebar_pages_each)) {
                        navPages(desktop).forEachIndexed { i, page ->
                            if (i > 0) Divider()
                            SwitchRow(
                                title = stringResource(navLabel(page)),
                                detail = null,
                                checked = settings.showsSidebarPage(page.name),
                                onCheckedChange = { on -> onUpdate { it.withSidebarPage(page.name, on) } },
                                icon = navIcon(page),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The Sidebar pages row's line in Settings: all of them, none, or how many of them show. */
@Composable
internal fun sidebarPagesSummary(settings: AppSettings, desktop: Boolean = isDesktop): String {
    val all = navPages(desktop).size
    val shown = shownNavPages(settings, desktop).size
    return when (shown) {
        0 -> stringResource(Res.string.settings_sidebar_pages_off)
        all -> stringResource(Res.string.settings_sidebar_pages_all)
        else -> pluralStringResource(Res.plurals.settings_sidebar_pages_count, shown, shown, all)
    }
}
