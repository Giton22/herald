package dev.hermeskotlin.ui.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.pet.PetChoice
import dev.hermeskotlin.core.pet.PetSprite
import dev.hermeskotlin.core.pet.PetState
import dev.hermeskotlin.core.pet.petStateOf
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.surface2
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.rememberImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The pose for [chat], Desktop's way: a brief wave when a turn finishes (a slump when it fails), then
 * waiting on an answer, running a tool, thinking, or working.
 */
@Composable
fun rememberPetState(chat: ChatState): PetState {
    var flash by remember { mutableStateOf<PetState?>(null) }
    var wasRunning by remember { mutableStateOf(chat.running) }
    LaunchedEffect(chat.running) {
        val finished = wasRunning && !chat.running
        wasRunning = chat.running
        if (!finished) {
            flash = null
            return@LaunchedEffect
        }
        val last = chat.messages.lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
        flash = when (last?.outcome) {
            TurnOutcome.Error -> PetState.Failed
            TurnOutcome.Interrupted -> null
            else -> PetState.Wave
        }
        delay(FLASH_MS)
        flash = null
    }
    val reply = chat.messages.lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
    return petStateOf(
        failed = flash == PetState.Failed,
        justFinished = flash == PetState.Wave,
        awaitingInput = chat.inputRequests.isNotEmpty(),
        toolRunning = reply?.tools?.any { it.running } == true,
        reasoning = reply?.streaming == true && reply.text.isEmpty() && reply.reasoning.isNotEmpty(),
        busy = chat.running,
    )
}

/** The pet, stepping through its spritesheet row for [state]. */
@Composable
fun PetView(sprite: PetSprite, state: PetState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Decoded whole: the frame grid is in sheet pixels.
    val sheet = rememberImageBitmap(sprite.sheet, maxEdge = 8192) ?: return
    val (wantedRow, count) = sprite.rowFor(state)
    // A sheet shorter than its row list draws the first row rather than nothing.
    val row = if ((wantedRow + 1) * sprite.frameH <= sheet.height) wantedRow else 0
    val columns = (sheet.width / sprite.frameW).coerceAtLeast(1)
    val frames = count.coerceIn(1, columns)
    var frame by remember(sprite, row, frames) { mutableIntStateOf(0) }
    LaunchedEffect(sprite, row, frames) {
        val step = (sprite.loopMs / frames).coerceAtLeast(MIN_STEP_MS).toLong()
        while (true) {
            delay(step)
            frame = (frame + 1) % frames
        }
    }
    // Desktop's size (frame × scale), kept within what a phone's composer can carry.
    val height = (sprite.frameH * sprite.scale).coerceIn(48.0, 96.0)
    val width = height * sprite.frameW / sprite.frameH
    Canvas(
        modifier
            .size(width.dp, height.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        drawImage(
            sheet,
            srcOffset = IntOffset(frame * sprite.frameW, row * sprite.frameH),
            srcSize = IntSize(sprite.frameW, sprite.frameH),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** `/pet`: the Petdex gallery. Tapping a pet adopts it for the profile, as Desktop's picker does. */
@Composable
fun PetSheet(visible: Boolean, controller: PetController, onDismiss: () -> Unit, onAdopted: (String) -> Unit) {
    val state = controller.gallery.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) { if (visible) controller.loadGallery() }
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader("Pets", "A pet belongs to the profile, so Desktop shows the same one.")
        val gallery = state.gallery
        when {
            gallery == null && state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            gallery != null && gallery.pets.isEmpty() -> Note("The gateway has no pets to offer.")
            gallery != null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(gallery.pets, key = { it.slug }) { pet ->
                    PetCell(
                        pet = pet,
                        controller = controller,
                        active = gallery.enabled && pet.slug == gallery.active,
                        adopting = state.adopting == pet.slug,
                        onClick = {
                            scope.launch { runCatching { controller.adopt(pet) }.onSuccess(onAdopted) }
                        },
                    )
                }
            }
        }
        state.error?.let { Note(it, error = true) }
        if (gallery?.enabled == true) {
            SheetAction("Put the pet away", Lucide.EyeOff, onClick = { scope.launch { runCatching { controller.hide() } } })
        }
    }
}

@Composable
private fun PetCell(pet: PetChoice, controller: PetController, active: Boolean, adopting: Boolean, onClick: () -> Unit) {
    val thumbnail by produceState<ByteArray?>(null, pet.slug) { value = controller.thumbnail(pet) }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    // The adopted pet sits on the accent tint with an accent edge, as a picked choice does elsewhere.
    Column(
        Modifier
            .clip(shape)
            .background(Theme[colors][if (active) accentSoft else surface2], shape)
            .border(if (active) 1.5.dp else 1.dp, Theme[colors][if (active) accentText else stroke], shape)
            .clickable(enabled = !adopting, onClick = onClick)
            .semantics { selected = active }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val bitmap = thumbnail?.let { rememberImageBitmap(it, maxEdge = 256) }
            when {
                adopting -> Spinner()
                bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            pet.displayName,
            style = Theme[typography][caption],
            color = Theme[colors][textColor],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = Theme[typography][bodySmall],
        color = Theme[colors][if (error) danger else textTertiary],
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** How long the wave (or slump) after a turn lasts, Desktop's beat. */
private const val FLASH_MS = 1_600L

private const val MIN_STEP_MS = 40
