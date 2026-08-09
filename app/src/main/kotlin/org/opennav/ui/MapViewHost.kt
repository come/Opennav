/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.opennav.chart.DepthLayers
import org.opennav.chart.PmtilesHeader
import org.opennav.core.depth.BoatProfile
import org.opennav.core.geo.LatLon
import org.opennav.diag.CrashLog
import java.io.File

/**
 * The MapLibre `MapView`, wrapped for Compose and driven by the activity lifecycle.
 *
 * Pinch-zoom, rotate and fling are MapLibre's own gesture handling. The only chrome
 * turned off is the logo and the attribution "i", because this app renders the SHOM and
 * OpenStreetMap credits properly on its own sources screen instead.
 *
 * Everything here crosses into the native library -- constructing the view, loading a
 * style, parsing an expression -- and a throw on that side takes the process with it.
 * The two places where that can happen are wrapped, so a map that will not load leaves an
 * app that says why instead of one that vanishes.
 */
@Composable
fun MapViewHost(
    /** Bathymetry, or null to draw buoyage and position without any depths. */
    archive: File?,
    seamarks: File?,
    header: PmtilesHeader?,
    boat: BoatProfile,
    tideHeightMeters: Double,
    deepRangeMeters: Double,
    onMapReady: (MapLibreMap, Style) -> Unit,
    onCameraChanged: (CameraPosition) -> Unit,
    onUserPannedMap: () -> Unit,
    onFrameRendered: (Double) -> Unit,
    onTap: (LatLon) -> Boolean,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnCamera by rememberUpdatedState(onCameraChanged)
    val currentOnPan by rememberUpdatedState(onUserPannedMap)
    val currentOnFrame by rememberUpdatedState(onFrameRendered)
    val currentOnReady by rememberUpdatedState(onMapReady)
    val currentOnError by rememberUpdatedState(onError)

    // The MapView outlives recomposition; only the first composition builds the style.
    // Everything after that is a property update through DepthLayers.
    val mapView = remember {
        runCatching {
            val options = MapLibreMapOptions.createFromAttributes(context)
                .logoEnabled(false)
                .attributionEnabled(false)
                .compassEnabled(true)

            MapView(context, options).apply {
                onCreate(null)

                addOnDidFailLoadingMapListener { reason -> currentOnError(reason) }
                addOnDidFinishRenderingFrameListener { _, encodingTime, renderingTime ->
                    // MapLibre reports the two halves of its own frame, in seconds. Their
                    // sum is what has to stay under 16.6 ms for the Phase 0 exit
                    // criterion, and it deliberately excludes Compose's work: this number
                    // is about the depth shader, not about the buttons on top of it.
                    currentOnFrame((encodingTime + renderingTime) * 1000.0)
                }

                getMapAsync { map ->
                    // A second guard: this lambda runs long after the one above returned,
                    // so nothing outside it can catch what it throws.
                    runCatching {
                        configure(map, header)
                        map.addOnMapClickListener { latLng ->
                            currentOnTap(LatLon(latLng.latitude, latLng.longitude))
                        }
                        map.addOnCameraMoveListener { currentOnCamera(map.cameraPosition) }
                        map.addOnCameraIdleListener { currentOnCamera(map.cameraPosition) }
                        map.addOnCameraMoveStartedListener { reason ->
                            // Only a real gesture takes the helm back. Comparing the
                            // camera to the fix instead would break follow mode on its
                            // very first frame, since the recentre animation necessarily
                            // starts away from it.
                            if (reason ==
                                MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                            ) {
                                currentOnPan()
                            }
                        }

                        map.setStyle(
                            DepthLayers.styleBuilder(
                                archive, seamarks, boat, tideHeightMeters, deepRangeMeters,
                            ),
                        ) { style ->
                            currentOnReady(map, style)
                            currentOnCamera(map.cameraPosition)
                        }
                    }.onFailure {
                        CrashLog.record(context, "chargement du style", it)
                        currentOnError("Style illisible : ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            }
        }.onFailure { CrashLog.record(context, "création de la carte", it) }.getOrNull()
    }

    if (mapView == null) {
        LaunchedEffect(Unit) {
            currentOnError("La carte n'a pas pu démarrer — voir le rapport de plantage.")
        }
        return
    }

    AndroidView(modifier = modifier, factory = { mapView })

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
}

/** Gestures, chrome and the camera envelope the loaded archive allows. */
private fun configure(map: MapLibreMap, header: PmtilesHeader?) {
    map.uiSettings.apply {
        isRotateGesturesEnabled = true
        isTiltGesturesEnabled = false
        isAttributionEnabled = false
        isLogoEnabled = false
        setCompassMargins(0, 340, 44, 0)
    }
    if (header == null) return

    // Below the archive's own minimum zoom there are no tiles at all. The ceiling depends
    // on what is being overzoomed: stretching a depth raster invents water between
    // samples, so it gets one level; vector buoyage is geometry scaled up and loses
    // nothing, so it gets room to actually look at a mark.
    val headroom =
        if (header.tileType == PmtilesHeader.TILE_TYPE_MVT) VECTOR_OVERZOOM else RASTER_OVERZOOM
    map.setMinZoomPreference(header.minZoom.toDouble())
    map.setMaxZoomPreference((header.maxZoom + headroom).toDouble())
    map.moveCamera(
        CameraUpdateFactory.newLatLngZoom(
            LatLng(header.centerLat, header.centerLon),
            header.centerZoom.toDouble(),
        ),
    )
}

private const val RASTER_OVERZOOM = 1
private const val VECTOR_OVERZOOM = 4
