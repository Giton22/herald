package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Image as ImageIcon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.chat.AttachmentKind
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.ShownAttachment
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** The files waiting in the composer, each with a remove button. */
@Composable
internal fun ComposerTray(attachments: List<OutgoingAttachment>, onRemove: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Box {
                AttachmentTile(attachment.name, attachment.kind, attachment.thumbnail, size = 64.dp)
                UnstyledButton(
                    onClick = { onRemove(attachment.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    indication = rememberColoredIndication(Color.White),
                ) {
                    UnstyledIcon(Lucide.X, contentDescription = "Remove ${attachment.name}", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** What went out with a prompt, above its bubble; pictures open full size. */
@Composable
internal fun SentAttachments(attachments: List<ShownAttachment>) {
    val load = LocalMediaLoader.current
    val open = LocalOpenImage.current
    FlowRow(
        Modifier.padding(start = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            // History knows only where the gateway keeps an image; fetch it to show the same thumbnail.
            val bytes by produceState(attachment.thumbnail, attachment.gatewayPath) {
                if (value == null) attachment.gatewayPath?.let { value = load(it) }
            }
            val viewable = attachment.kind == AttachmentKind.Image && bytes != null
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = viewable) {
                    open(ViewerImage(attachment.name, source = attachment.gatewayPath, bytes = attachment.original))
                },
            ) {
                AttachmentTile(attachment.name, attachment.kind, bytes, size = 96.dp)
            }
        }
    }
}

/** A photo thumbnail, or for files (and history images without bytes) an icon card with the name. */
@Composable
private fun AttachmentTile(name: String, kind: AttachmentKind, thumbnail: ByteArray?, size: Dp) {
    val shape = RoundedCornerShape(14.dp)
    val bitmap = thumbnail?.let { rememberImageBitmap(it) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape).border(1.dp, Theme[colors][stroke], shape),
        )
        return
    }
    Row(
        Modifier
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .widthIn(max = 220.dp)
            .padding(horizontal = 12.dp, vertical = if (size > 64.dp) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UnstyledIcon(kind.icon, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(22.dp))
        Column(Modifier.padding(end = if (size > 64.dp) 0.dp else 18.dp)) {
            Text(name, style = Theme[typography][bodySmall], color = Theme[colors][textColor], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind.label, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
        }
    }
}

private val AttachmentKind.icon: ImageVector
    get() = when (this) {
        AttachmentKind.Image -> Lucide.ImageIcon
        AttachmentKind.Pdf -> Lucide.FileText
        AttachmentKind.File -> Lucide.File
    }

private val AttachmentKind.label: String
    get() = when (this) {
        AttachmentKind.Image -> "Image"
        AttachmentKind.Pdf -> "PDF"
        AttachmentKind.File -> "File"
    }

/** The + button's choices. */
@Composable
internal fun AttachSheet(visible: Boolean, onDismiss: () -> Unit, picker: AttachmentPicker) {
    fun pick(action: () -> Unit) {
        onDismiss()
        action()
    }
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader("Add to message", "Up to ${OutgoingAttachment.MAX_COUNT} photos or files, 25 MB each")
        SheetAction("Photos", Lucide.ImageIcon, onClick = { pick(picker::pickPhotos) })
        SheetAction("Camera", Lucide.Camera, onClick = { pick(picker::takePhoto) })
        SheetAction("Files", Lucide.Paperclip, onClick = { pick(picker::pickFiles) })
    }
}
