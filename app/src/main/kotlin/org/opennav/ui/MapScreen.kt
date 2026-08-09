/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.opennav.chart.ChartArchive
import org.opennav.chart.DepthLayers
import org.opennav.chart.MapEngine
import org.opennav.diag.CrashLog
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
    val scope = rememberCoroutineScope()
    val settings = remember { BoatSettings(context) }
    var error by remember { mutableStateOf<String?>(null) }

    var located by remember {
        mutableStateOf(ChartArchive.locate(context, settings.selectedChartPath))
    }
    val header = remember(located) { located.bathymetry?.header ?: located.seamarks?.header }
    var importing by remember { mutableStateOf(false) }

    // True for the second or so of the very first launch, while the demo chart is copied
    // out of the APK. Decided synchronously here rather than inside the effect, so the
    // empty state never flashes up before the map it is about to be replaced by.
    var unpacking by remember {
        mutableStateOf(located.bathymetry == null && !settings.bundledChartSeeded)
    }
    val demoAvailable = remember { ChartArchive.hasBundled(context) }

    /**
     * Unpacks the demo out of the APK and displays it.
     *
     * Marked as done whether or not it worked. A build without the asset, or a phone with
     * no room left, would otherwise retry on every launch and delay every one of them;
     * the empty state keeps offering the button, which is the right place for a retry.
     */
    suspend fun installDemo() {
        unpacking = true
        val seeded = withContext(Dispatchers.IO) { ChartArchive.seedBundled(context) }
        settings.bundledChartSeeded = true
        unpacking = false
        if (seeded == null) {
            error = "Carte de démonstration indisponible dans cette version."
            return
        }
        // Deliberately not recorded as the selected chart: the demo is a placeholder, and
        // the first real survey imported over it has to win without the user having to
        // say so twice.
        located = ChartArchive.locate(context, settings.selectedChartPath)
        error = null
    }

    LaunchedEffect(Unit) {
        if (unpacking) installDemo()
    }

    // The system picker, so a chart can be installed from the phone itself rather than
    // over adb. It hands back a content URI, which ChartArchive copies into the charts
    // directory -- MapLibre needs a plain path it can range-read for months.
    val pickChart = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { ChartArchive.importFrom(context, uri) }
            importing = false
            result
                .onSuccess { file ->
                    settings.selectedChartPath = file.absolutePath
                    located = ChartArchive.locate(context, file.absolutePath)
                    error = null
                }
                .onFailure { error = "Import impossible : ${it.message ?: "fichier illisible"}" }
        }
    }

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

    var fix by remember { mutableStateOf<Fix?>(null) }
    var following by remember { mutableStateOf(false) }

    var sheet by remember { mutableStateOf<Sheet?>(null) }

    // Read once, at launch, from the file the uncaught-exception handler wrote. This is
    // the only way a failure that happened on the water gets back to anyone.
    var crashReport by remember { mutableStateOf(CrashLog.read(context)) }

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
        val chart = located.any
        if (!MapEngine.isReady) {
            MapEngineFailed(modifier = Modifier.fillMaxSize())
        } else if (chart == null) {
            NoChartInstalled(
                directory = remember { ChartArchive.preferredDirectory(context).absolutePath },
                importing = importing,
                unpacking = unpacking,
                canRestoreDemo = demoAvailable && !unpacking,
                onImport = { pickChart.launch(CHART_PICKER_MIME_TYPES) },
                onRestoreDemo = { scope.launch { installDemo() } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Keyed on the archives: MapViewHost builds its MapView and its style once, so
            // importing a different chart has to give it a fresh scope to build them in.
            key(
                located.bathymetry?.file?.absolutePath,
                located.basemap?.file?.absolutePath,
                located.seamarks?.file?.absolutePath,
            ) {
            MapViewHost(
                archive = located.bathymetry?.file,
                basemap = located.basemap?.file,
                seamarks = located.seamarks?.file,
                header = header,
                boat = boat,
                tideHeightMeters = tideMeters,
                deepRangeMeters = deepRange,
                onMapReady = { readyMap, readyStyle ->
                    map = readyMap
                    style = readyStyle
                },
                onCameraChanged = { camera -> zoom = camera.zoom },
                onUserPannedMap = { following = false },
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
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth(),
        ) {
            DisclaimerBanner(modifier = Modifier.fillMaxWidth())
            if (located.bathymetry?.header?.synthetic == true) {
                SyntheticChartBanner(modifier = Modifier.fillMaxWidth())
            } else if (located.bathymetry == null && located.any != null) {
                NoBathymetryBanner(modifier = Modifier.fillMaxWidth())
            }
        }

        SettingsButton(
            onClick = { sheet = Sheet.SETTINGS },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = TOP_BANNER_INSET, end = 12.dp),
        )

        if (showHud) {
            PerformanceHud(
                zoom = zoom,
                frameMillis = frameMillis,
                archiveBytes = located.bathymetry?.file?.length() ?: 0L,
                header = header,
                fix = fix,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = TOP_BANNER_INSET, start = 12.dp),
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
            error?.let { ErrorCard(message = it, onDismiss = { error = null }) }

            if (routePoints.size >= 2) {
                LegCard(
                    from = routePoints[0],
                    to = routePoints[1],
                    onClear = { routePoints = emptyList() },
                )
            }

            // Without depths the slider moves a colour ramp that is not on screen. Hiding
            // it is not tidiness: a control that visibly does nothing invites the reading
            // that the water level has been taken into account.
            if (showTideSlider && located.bathymetry != null) {
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
            chartName = located.bathymetry?.file?.name,
            basemapName = located.basemap?.file?.name,
            seamarkName = located.seamarks?.file?.name,
            importing = importing,
            canRemoveDemo = located.bathymetry?.header?.synthetic == true,
            onImport = { pickChart.launch(CHART_PICKER_MIME_TYPES) },
            onRemoveDemo = {
                scope.launch {
                    withContext(Dispatchers.IO) { ChartArchive.removeBundled(context) }
                    located = ChartArchive.locate(context, settings.selectedChartPath)
                    sheet = null
                }
            },
            onDismiss = { sheet = null },
        )
        Sheet.SOURCES -> SourcesSheet(
            chartName = located.bathymetry?.file?.name,
            basemapName = located.basemap?.file?.name,
            seamarkName = located.seamarks?.file?.name,
            header = header,
            syntheticBathymetry = located.bathymetry?.header?.synthetic == true,
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

    // Declared last so it sits above the disclaimer: if the previous run died, that is
    // the more urgent thing on this launch.
    crashReport?.let { report ->
        CrashReportDialog(
            report = report,
            onShare = {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(CrashLog.shareIntent(report), "Envoyer le rapport"),
                    )
                }
            },
            onDismiss = {
                CrashLog.clear(context)
                crashReport = null
            },
        )
    }
}

enum class Sheet { SETTINGS, SOURCES }

/**
 * Shown when MapLibre itself refused to start.
 *
 * There is nothing to retry and nothing to configure; the only useful action left is
 * getting the recorded reason off the phone, which the crash dialog on top of this offers.
 */
@Composable
private fun MapEngineFailed(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Le moteur de carte n'a pas démarré", style = MaterialTheme.typography.titleLarge)
            Text(
                "MapLibre n'a pas pu s'initialiser sur cet appareil. Le détail a été " +
                    "enregistré : utilisez « Partager » sur le rapport de plantage pour " +
                    "l'envoyer, c'est la seule information exploitable.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                MapEngine.failure?.let { "${it.javaClass.name}: ${it.message}" } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NoChartInstalled(
    directory: String,
    importing: Boolean,
    unpacking: Boolean,
    canRestoreDemo: Boolean,
    onImport: () -> Unit,
    onRestoreDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (unpacking) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Préparation de la carte de démonstration…",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Aucune carte installée", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choisissez un fichier .pmtiles déjà présent sur le téléphone — " +
                    "téléchargé, reçu, ou copié par câble. Bathymétrie ou balisage : " +
                    "l'un des deux suffit à afficher une carte.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ImportChartButton(importing = importing, onClick = onImport)
            if (canRestoreDemo) {
                TextButton(onClick = onRestoreDemo) {
                    Text("Remettre la carte de démonstration")
                }
            }
            Text(
                "Vous pouvez aussi le déposer directement dans :\n$directory\n\n" +
                    "adb push ma-zone.pmtiles $directory/\n\n" +
                    "Fabriquer une carte : voir le README du projet. Pour un essai sans " +
                    "données SHOM : ./tools/make_sample_pmtiles.py",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** MIME types offered to the system picker. PMTiles has no registered type of its own. */
private val CHART_PICKER_MIME_TYPES = arrayOf("application/octet-stream", "*/*")

/** Clears the disclaimer band, and the fictitious-chart band when it is showing. */
private val TOP_BANNER_INSET = 56.dp

private const val INITIAL_TIDE_METERS = 3.0
private const val RECENTER_MIN_ZOOM = 13.0
private const val CAMERA_FOLLOW_MILLIS = 600
