package dev.hermeskotlin.wear

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Calls the phone’s current default target; artwork is read from the background-synced cache. */
class AskComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = data(request.complicationType, preview = false)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, preview = true)

    private fun data(type: ComplicationType, preview: Boolean): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_call)
        val picture = if (preview) null else HeraldWatch.of(this).callDisplay.icon()
        val imageType = if (picture == null) SmallImageType.ICON else SmallImageType.PHOTO
        val description = PlainComplicationText.Builder(if (preview) "Call Hermes" else "Call ${HeraldWatch.of(this).callDisplay.title}").build()
        val tap = PendingIntent.getActivity(this, 0, MainActivity.callIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return when (type) {
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(picture ?: icon, imageType).build(), description)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }
}
