/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.opennav.chart.ChartArchive
import org.opennav.chart.DepthLayers
import org.opennav.chart.PmtilesHeader
import org.opennav.core.depth.BoatProfile
import org.opennav.core.geo.LatLon
import org.opennav.location.Fix
import org.opennav.location.PositionSource
import org.opennav.settings.BoatSettings

/** What the bottom toolbar's tap tool is currently doing. */
enum class TapTool { NONE, MEASURE }

@Composable
fun MapScreen(
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { BoatSettings(context) }
    val located = remember { ChartArchive.locate(context) }
    val header = remember(located) { located?.file?.let { PmtilesHeader.read(it) } }

    var boat by remember { mutableStateOf(settings.profile) }
    var deepRange by remember { mutableStateOf(settings.deepRangeMeters) }
    var showHud by remember { mutableStateOf(settings.showPerformanceHud) }
    var showDisclaimer by remember { mutableStateOf(!settings.disclaimerAccepted) }

    // Phase 0 drives the water level straight from the slider. In Phase 2 this becomes a
    // +/- 12 h time cursor and the height comes from :core:tide; the rest of the pipeline
    // does not change, because everything downstream only ever sees a height in metres.
    var tideMeters by remember { mutableStateOf(INITIAL_TIDE_METERS) }
    var showTideSlider by remember { mutableStateOf(true) }

    var tapTool by remember { mutableStateOf(TapTool.NONE) }
    var routePoints by remember { mutableStateOf(emptyList<LatLon>()) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var zoom by remember { mutableStateOf(0.0) }
    var frameMillis by remember { mutableStateOf(0.0) }
    var error by remember { mutableStateOf<String?>(null) }

    var fix by remember { mutableStateOf<Fix?>(null) }
    var following by remember { mutableStateOf(false) }

    var sheet by remember { mutableStateOf<Sheet?>(null) }

    // --- side effects -----------------------------------------------------------

    LaunchedEffect(locationGranted) {
        if (!locationGranted) return@LaunchedEffect
        PositionSource(context).fixes().collectLatest { newFix ->
            fix = newFix
            style?.let { DepthLayers.updatePosition(it, newFix.position) }
            if (following) {
                map?.animateCamera(
                    CameraUpdateFactory.newLatLng(
                        LatLng(newFix.position.latitude, newFix.position.longitude),
                    ),
                    CAMERA_FOLLOW_MILLIS,
                )
            }
        }
    }

    LaunchedEffect(style, boat, tideMeters, deepRange) {
        style?.let { DepthLayers.updateDepthRamp(it, boat, tideMeters, deepRange) }
    }

    LaunchedEffect(style, routePoints) {
        style?.let { DepthLayers.updateRoute(it, routePoints) }
    }

    // --- layout -----------------------------------------------------------------

    Box(Modifier.fillMaxSize()) {
        if (located == null) {
            NoChartInstalled(
                directory = remember { ChartArchive.preferredDirectory(context).absolutePath },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MapViewHost(
                archive = located.file,
                header = header,
                boat = boat,
                tideHeightMeters = tideMeters,
                deepRangeMeters = deepRange,
                onMapReady = { readyMap, readyStyle ->
                    map = readyMap
                    style = readyStyle
                },
                onCameraChanged = { camera ->
                    zoom = camera.zoom
                    // Any camera movement that was not the follow animation means the
                    // user took the helm back.
                    if (following && !isNear(camera.target, fix?.position)) following = false
                },
                onFrameRendered = { frameMillis = it },
                onTap = { point ->
                    if (tapTool == TapTool.MEASURE) {
                        routePoints = when (routePoints.size) {
                            0, 1 -> routePoints + point
                            else -> listOf(point) // a third tap starts a new leg
                        }
                        true
                    } else {
                        false
                    }
                },
                onError = { error = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        DisclaimerBanner(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth(),
        )

        SettingsButton(
            onClick = { sheet = Sheet.SETTINGS },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 34.dp, end = 12.dp),
        )

        if (showHud) {
            PerformanceHud(
                zoom = zoom,
                frameMillis = frameMillis,
                archiveBytes = located?.file?.length() ?: 0L,
                header = header,
                fix = fix,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 34.dp, start = 12.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { ErrorCard(it) { error = null } }

            if (routePoints.size >= 2) {
                LegCard(
                    from = routePoints[0],
                    to = routePoints[1],
                    onClear = { routePoints = emptyList() },
                )
            }

            if (showTideSlider) {
                TideSlider(
                    tideMeters = tideMeters,
                    boat = boat,
                    onTideChange = { tideMeters = it },
                )
            }

            MainToolbar(
                tapTool = tapTool,
                following = following,
                tideVisible = showTideSlider,
                onRecenter = {
                    if (!locationGranted) {
                        onRequestLocation()
                    } else {
                        val position = fix?.position
                        if (position != null) {
                            following = true
                            map?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(position.latitude, position.longitude),
                                    maxOf(zoom, RECENTER_MIN_ZOOM),
                                ),
                                CAMERA_FOLLOW_MILLIS,
                            )
                        }
                    }
                },
                onToggleMeasure = {
                    tapTool = if (tapTool == TapTool.MEASURE) TapTool.NONE else TapTool.MEASURE
                    if (tapTool == TapTool.MEASURE) routePoints = emptyList()
                },
                onToggleTide = { showTideSlider = !showTideSlider },
                onOpenSources = { sheet = Sheet.SOURCES },
            )
        }
    }

    when (sheet) {
        Sheet.SETTINGS -> SettingsSheet(
            boat = boat,
            deepRangeMeters = deepRange,
            showHud = showHud,
            onBoatChange = { boat = it; settings.profile = it },
            onDeepRangeChange = { deepRange = it; settings.deepRangeMeters = it },
            onShowHudChange = { showHud = it; settings.showPerformanceHud = it },
            onOpenSources = { sheet = Sheet.SOURCES },
            onDismiss = { sheet = null },
        )
        Sheet.SOURCES -> SourcesSheet(
            chartName = located?.file?.name,
            header = header,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    if (showDisclaimer) {
        DisclaimerDialog(
            onAccept = {
                settings.disclaimerAccepted = true
                showDisclaimer = false
            },
        )
    }
}

enum class Sheet { SETTINGS, SOURCES }

@Composable
private fun NoChartInstalled(directory: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Aucune carte installée", style = MaterialTheme.typography.titleLarge)
            Text(
                "Déposez un fichier .pmtiles dans :\n$directory\n\n" +
                    "adb push ma-zone.pmtiles $directory/\n\n" +
                    "Pour un essai sans données SHOM :\n" +
                    "./tools/make_sample_pmtiles.py",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val INITIAL_TIDE_METERS = 3.0
private const val RECENTER_MIN_ZOOM = 13.0
private const val CAMERA_FOLLOW_MILLIS = 600

/** True when the camera is still sitting on the fix, i.e. the follow animation is intact. */
private fun isNear(target: LatLng?, position: LatLon?): Boolean {
    if (target == null || position == null) return false
    val dLat = target.latitude - position.latitude
    val dLon = target.longitude - position.longitude
    return dLat * dLat + dLon * dLon < FOLLOW_TOLERANCE_DEG_SQUARED
}

// About 100 m at these latitudes: loose enough not to fight the follow animation,
// tight enough that a deliberate pan drops out of follow immediately.
private const val FOLLOW_TOLERANCE_DEG_SQUARED = 1e-6
