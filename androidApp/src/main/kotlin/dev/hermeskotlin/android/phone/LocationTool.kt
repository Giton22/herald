package dev.hermeskotlin.android.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * `phone_location`: where the phone is, from Android's own location service (no Google services): the last known
 * place when it's recent, else measured now, and the nearest address when the phone can look it up. Only as precise
 * as the user allowed, and only while Kyllene is open unless they allowed it all the time.
 */
internal class LocationTool(private val context: Context) {

    suspend fun run(args: JsonObject): JsonObject {
        val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val location = locate(fresh = args.boolean("fresh") ?: false)
        val address = address(location, approximate = !precise)
        return buildJsonObject {
            put("latitude", round(location.latitude))
            put("longitude", round(location.longitude))
            if (location.hasAccuracy()) put("accuracy_m", location.accuracy.roundToInt())
            put("measured_at", Instant.ofEpochMilli(location.time).toString())
            put("minutes_ago", (ageMillis(location) / 60_000).toInt())
            // Approximate access: Android blurs it to about 2 km, on purpose.
            put("approximate", !precise)
            if (address != null) put("address", address)
        }
    }

    /**
     * Where the phone is, as precisely as the user allowed; throws what the agent should be told when it can't say.
     * With [old] false, a last known place older than a couple of minutes is never the answer.
     */
    suspend fun locate(fresh: Boolean, old: Boolean = true): Location {
        val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!precise && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw PhoneToolException("Kyllene isn't allowed to use the phone's location. The user can allow it in Kyllene: Settings → Phone tools.")
        }
        // Android only hands an app in the background its location with "all the time" access, and then quietly.
        if (!visible() && !backgroundAllowed()) {
            throw PhoneToolException(
                "Kyllene is in the background and may only read the location while it's open. Ask the user to open Kyllene, " +
                    "or to allow location all the time in Kyllene: Settings → Phone tools.",
            )
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: throw PhoneToolException("The phone has no location service.")
        if (!LocationManagerCompat.isLocationEnabled(manager)) throw PhoneToolException("Location is turned off on the phone.")
        val known = lastKnown(manager)
        return known?.takeIf { !fresh && ageMillis(it) <= RECENT_MS }
            ?: measure(manager, precise)
            ?: known?.takeIf { old || ageMillis(it) <= RECENT_MS }
            ?: throw PhoneToolException("The phone couldn't tell where it is just now.")
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Before Android 10 the location permission covers the background too. */
    private fun backgroundAllowed() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Whether one of Kyllene's screens is showing: what "while in use" means to Android (a running service isn't). */
    private fun visible(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /** The newest place any provider remembers. */
    private fun lastKnown(manager: LocationManager): Location? =
        manager.getProviders(true).mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.elapsedRealtimeNanos }

    /** Measures now, through the best provider that's on; null when it doesn't settle in time. */
    private suspend fun measure(manager: LocationManager, precise: Boolean): Location? {
        val on = manager.getProviders(true)
        val provider = listOfNotNull(
            // A constant string ("fused"), only offered where Android has that provider (12 and later).
            @SuppressLint("InlinedApi") LocationManager.FUSED_PROVIDER.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
            // Android 12 and later blur the GPS for approximate access; before, approximate apps can't use it at all.
            LocationManager.GPS_PROVIDER.takeIf { precise || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
            LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { it in on } ?: return null
        return withTimeoutOrNull(MEASURE_MS) {
            suspendCancellableCoroutine { done ->
                val cancel = CancellationSignal()
                done.invokeOnCancellation { cancel.cancel() }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // The plain call asks for balanced power, which may never turn the GPS on: with precise access
                        // the user wants the phone to really look.
                        val quality = if (precise) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                        val request = LocationRequest.Builder(0).setQuality(quality).setDurationMillis(MEASURE_MS).build()
                        manager.getCurrentLocation(provider, request, cancel, executor) { done.resume(it) }
                    } else {
                        LocationManagerCompat.getCurrentLocation(manager, provider, cancel, executor) { done.resume(it) }
                    }
                } catch (e: SecurityException) {
                    done.resume(null)
                }
            }
        }
    }

    /** The nearest address, one line; null when the phone has no geocoder or it doesn't answer quickly. */
    private suspend fun address(location: Location, approximate: Boolean): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val found: Address = withTimeoutOrNull<Address?>(ADDRESS_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { done ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) = done.resume(addresses.firstOrNull())
                            override fun onError(errorMessage: String?) = done.resume(null)
                        },
                    )
                }
            } else {
                // A blocking binder call that ignores interrupts: run apart and stop waiting for it, so a slow geocoder
                // can't hold the answer past the gateway's wait.
                suspendCancellableCoroutine { done ->
                    geocoding.execute {
                        @Suppress("DEPRECATION") // The listener form is Android 13 and later.
                        val first = runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                        if (done.isActive) done.resume(first)
                    }
                }
            }
        } ?: return null
        // Approximate access gets the area only: a street address of a blurred point would look precise.
        if (approximate) return listOfNotNull(found.locality, found.adminArea, found.countryName).distinct().joinToString(", ").ifBlank { null }
        return found.getAddressLine(0)?.takeIf { it.isNotBlank() }
    }

    private fun ageMillis(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    /** About 10 cm: as fine as any phone measures. */
    private fun round(degrees: Double) = (degrees * 1e6).roundToInt() / 1e6

    private companion object {
        /** A last known place this recent is used as it is, unless the agent asks for a fresh one. */
        const val RECENT_MS = 2 * 60_000L

        /** The gateway waits 30 s for a read; a measurement gets most of that, the address a little. */
        const val MEASURE_MS = 18_000L
        const val ADDRESS_MS = 5_000L
        val executor = Executors.newSingleThreadExecutor()

        /** Apart from [executor]: a geocoder stuck on one call mustn't hold up the next measurement. */
        val geocoding = Executors.newSingleThreadExecutor()
    }
}
