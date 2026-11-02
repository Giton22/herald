package dev.hermeskotlin.android.pebble

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Which Pebble phone app Kyllene talks through. PebbleKit would take any app that says it's one, and the watch's
 * asks start real turns on the gateway, so Kyllene picks only one it knows by name, and [PebbleBridgeService] hears
 * only from that one. A Settings choice for other Pebble apps can come later.
 */
object PebbleApps {

    /** The Core Pebble app; microPebble and the like once their package names are checked. */
    private val KNOWN = listOf("coredevices.coreapp")

    fun choose(context: Context, scope: CoroutineScope) {
        val picker = DefaultPebbleAndroidAppPicker.getInstance(context)
        picker.enableAutoSelect = false
        scope.launch {
            if (picker.getCurrentlySelectedApp() != null) return@launch
            picker.getAllEligibleApps().firstOrNull { it in KNOWN }?.let { picker.selectApp(it) }
        }
    }
}
