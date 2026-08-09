/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.opennav.core.geo.LatLon

/** A GPS fix, reduced to what the map actually draws. */
data class Fix(
    val position: LatLon,
    val accuracyMeters: Float?,
    val speedKnots: Double?,
    val courseOverGroundDegrees: Double?,
)

/**
 * Position updates from the platform's own GPS provider.
 *
 * The plan's stack table names `FusedLocationProviderClient`. This uses the framework
 * [LocationManager] instead, and the reason is the constraint two rows above it in the
 * same plan: no telemetry, no trackers, no network permission. The fused provider lives
 * in Google Play Services, which is proprietary, absent on de-Googled phones, and needs
 * the network. Swapping it back in later is a change to this one file.
 *
 * What is lost is Play Services' sensor fusion and its battery-aware batching. For a
 * chart plotter that wants a raw GNSS fix once a second while the screen is on, the
 * framework provider is a fair trade.
 */
class PositionSource(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun fixes(minIntervalMillis: Long = 1_000L): Flow<Fix> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(location.toFix()) }

        val providers = buildList {
            if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            // Kept as a fallback so the recentre button does something indoors, where a
            // GNSS fix can take minutes. On the water GPS wins on accuracy anyway.
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        providers.forEach { provider ->
            runCatching {
                manager.getLastKnownLocation(provider)?.let { trySend(it.toFix()) }
                manager.requestLocationUpdates(
                    provider, minIntervalMillis, 0f, listener, Looper.getMainLooper(),
                )
            }
        }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    private fun Location.toFix() = Fix(
        position = LatLon(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedKnots = if (hasSpeed()) (speed / MPS_PER_KNOT).toDouble() else null,
        // A GPS course is only meaningful once moving; standing still it is noise, and
        // showing noise as a heading on a chart plotter is worse than showing nothing.
        courseOverGroundDegrees = if (hasBearing() && hasSpeed() && speed > MIN_COG_SPEED_MPS) {
            bearing.toDouble()
        } else {
            null
        },
    )

    private companion object {
        const val MPS_PER_KNOT = 0.514444f
        const val MIN_COG_SPEED_MPS = 0.5f
    }
}
