package dev.hermeskotlin.wear

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Calls the phone’s current default target; artwork is read from the background-synced cache. A small-image slot
 * shows the target's picture; text and monochrome slots (where it was placed before) keep the call glyph.
 */
class AskComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = data(request.complicationType, preview = false)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, preview = true)

    private fun data(type: ComplicationType, preview: Boolean): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_call)
        val description = PlainComplicationText.Builder(if (preview) "Call Hermes" else "Call ${HeraldWatch.of(this).callDisplay.title}").build()
        val tap = PendingIntent.getActivity(this, 0, MainActivity.callIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder("Call").build(), description)
                .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                .setTapAction(tap)
                .build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(icon).build(), description)
                .setTapAction(tap)
                .build()
            ComplicationType.SMALL_IMAGE -> {
                val picture = if (preview) null else HeraldWatch.of(this).callDisplay.icon()
                val imageType = if (picture == null) SmallImageType.ICON else SmallImageType.PHOTO
                SmallImageComplicationData.Builder(SmallImage.Builder(picture ?: icon, imageType).build(), description)
                    .setTapAction(tap)
                    .build()
            }
            else -> null
        }
    }
}
