package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.media.LinkPreview
import dev.hermeskotlin.core.media.replyWebLinks
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalLinkPreviewLoader = staticCompositionLocalOf<suspend (String) -> LinkPreview?> { { null } }

/** Each card loads independently; its place stays in source order even when another site answers first. */
@Composable
internal fun LinkPreviewCards(text: String) {
    val urls by produceState(emptyList<String>(), text) {
        value = withContext(Dispatchers.Default) { replyWebLinks(text) }
    }
    val load = LocalLinkPreviewLoader.current
    urls.forEach { url ->
        key(url) {
            // A card already shown stays if a later try fails.
            val card by produceState<LinkPreview?>(null, url, load) { value = load(url) ?: value }
            card?.let { LinkPreviewCard(it) }
        }
    }
}

@Composable
private fun LinkPreviewCard(card: LinkPreview) {
    val uris = LocalUriHandler.current
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val bitmap = card.image?.let { rememberDecodedImage(it, maxEdge = 320).bitmap }
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface]).clickable { runCatching { uris.openUri(card.url) } }.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(80.dp).clip(shape)) {
            if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(80.dp), contentScale = ContentScale.Crop)
            else UnstyledIcon(Lucide.Globe, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.padding(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(card.domain, style = Theme[typography][caption], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.title, style = Theme[typography][bodySmall], color = Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.description.isNotBlank()) Text(card.description, style = Theme[typography][caption], color = Theme[colors][textSecondary], maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
