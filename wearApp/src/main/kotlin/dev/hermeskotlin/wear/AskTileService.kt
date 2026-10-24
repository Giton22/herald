package dev.hermeskotlin.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * The "Ask Hermes" tile: a mic that opens Herald listening, and the first lines of the last reply. Updated when
 * a reply comes in ([WatchLink] asks for it).
 */
class AskTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val store = HeraldWatch.of(this).store
        val last = store.lastReply?.let(::spokenText)?.takeIf { it.isNotBlank() }
        val accent = store.accent.value
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(last, accent?.fill ?: HERALD_BLUE, accent?.onFill ?: WHITE)))
            .build()
        return done(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        done(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    MIC,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_mic).build())
                        .build(),
                )
                .build(),
        )

    /** [fill] is the accent the mic sits on (the phone's, once synced), [onFill] the mic's own color. */
    private fun layout(last: String?, fill: Int, onFill: Int): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("ask")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(MainActivity.EXTRA_LISTEN, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
                            .build(),
                    )
                    .build(),
            )
            .build()
        val mic = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(MIC_BUTTON))
            .setHeight(dp(MIC_BUTTON))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(fill))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(MIC_BUTTON / 2)).build())
                            .build(),
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Ask Hermes").build())
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(MIC)
                    .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(onFill)).build())
                    .setWidth(dp(MIC_ICON))
                    .setHeight(dp(MIC_ICON))
                    .build(),
            )
            .build()
        // A soft ring round the mic, as the app's orb has.
        val ring = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(RING))
            .setHeight(dp(RING))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(softRing(fill)))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(RING / 2)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(mic)
            .build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("Kyllene", LABEL_SIZE, MUTED, maxLines = 1))
            .addContent(spacer(6f))
            .addContent(ring)
            .addContent(spacer(8f))
            // Two lines: a third would run into the round edge.
            .addContent(text(last ?: "Ask Hermes anything", BODY_SIZE, if (last != null) TEXT else MUTED, maxLines = 2))
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

    private fun text(value: String, size: Float, color: Int, maxLines: Int) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(maxLines)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(24f)).setEnd(dp(24f)).build()).build())
        .build()

    private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun <T : Any> done(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { it.set(value) }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val MIC = "mic"
        const val MIC_BUTTON = 52f
        const val RING = 70f
        const val MIC_ICON = 26f
        const val LABEL_SIZE = 13f
        const val BODY_SIZE = 14f
        const val HERALD_BLUE = 0xFF2F6BF5.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TEXT = 0xFFF4F4F6.toInt()
        const val MUTED = 0xFF8A8A94.toInt()
    }
}

/** The soft ring round the tile's mic: [fill] at a quarter strength, as Herald blue's ring always was. */
internal fun softRing(fill: Int): Int = (fill and 0x00FFFFFF) or 0x40000000
