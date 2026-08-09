/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opennav.chart.PmtilesHeader
import org.opennav.core.depth.BoatProfile
import org.opennav.core.depth.DepthPalette
import org.opennav.core.geo.Geodesy
import org.opennav.core.geo.LatLon
import org.opennav.location.Fix
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The permanent, discreet reminder required by the plan.
 *
 * Discreet, not hidden: it never scrolls away and it never needs dismissing, because the
 * moment it does either of those it stops being the thing that was promised.
 */
@Composable
fun DisclaimerBanner(modifier: Modifier = Modifier) {
    Text(
        text = "Aide à la décision — ne remplace ni les cartes SHOM ni le sondeur",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xCCFFFFFF),
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color(0xB3000000))
            .padding(vertical = 4.dp, horizontal = 8.dp),
    )
}

@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color(0xCC181820),
            contentColor = Color.White,
        ),
    ) {
        Icon(GearIcon, contentDescription = "Paramètres")
    }
}

/**
 * Spike instrumentation: the numbers the Phase 0 exit criterion is written in.
 *
 * Frame time is MapLibre's own, so 16.6 ms is the 60 fps line. Archive size answers the
 * other Phase 0 question -- whether Brittany fits in the disk budget -- by showing what
 * the loaded extract actually costs.
 */
@Composable
fun PerformanceHud(
    zoom: Double,
    frameMillis: Double,
    archiveBytes: Long,
    header: PmtilesHeader?,
    fix: Fix?,
    modifier: Modifier = Modifier,
) {
    val fps = if (frameMillis > 0.01) 1000.0 / frameMillis else 0.0
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xB3000000)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            HudLine("z", String.format(Locale.ROOT, "%.1f", zoom))
            HudLine(
                "frame",
                String.format(Locale.ROOT, "%.1f ms (%.0f fps)", frameMillis, fps),
            )
            HudLine("carte", "${archiveBytes / 1024 / 1024} Mo")
            header?.let { HudLine("zooms", "${it.minZoom}–${it.maxZoom}") }
            fix?.let {
                HudLine(
                    "sog",
                    it.speedKnots?.let { s -> String.format(Locale.ROOT, "%.1f nd", s) } ?: "—",
                )
                HudLine(
                    "cog",
                    it.courseOverGroundDegrees
                        ?.let { c -> String.format(Locale.ROOT, "%03.0f°", c) } ?: "—",
                )
            }
        }
    }
}

@Composable
private fun HudLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0x99FFFFFF),
        )
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
    }
}

/**
 * Phase 0's stand-in for the time cursor: the water level, set by hand.
 *
 * It is labelled as simulated rather than dressed up as a prediction. There is no
 * harmonic engine yet, and a slider that said "14:30, PM+2h" while inventing the number
 * would be the exact kind of confident wrongness this app is supposed to avoid.
 */
@Composable
fun TideSlider(
    tideMeters: Double,
    boat: BoatProfile,
    onTideChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xD9181820)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Hauteur d'eau simulée",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xCCFFFFFF),
                )
                Text(
                    String.format(Locale.ROOT, "%.2f m", tideMeters),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Slider(
                value = tideMeters.toFloat(),
                onValueChange = { onTideChange(it.toDouble()) },
                valueRange = TIDE_MIN_METERS..TIDE_MAX_METERS,
            )
            Text(
                "Seuil sûr : ${format2(boat.requiredWaterMeters)} m sous la sonde " +
                    "(tirant ${format2(boat.draftMeters)} + marge ${format2(boat.safetyMarginMeters)}). " +
                    "Moteur de marée réel : phase 1.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x99FFFFFF),
            )
        }
    }
}

@Composable
fun MainToolbar(
    tapTool: TapTool,
    following: Boolean,
    tideVisible: Boolean,
    onRecenter: () -> Unit,
    onToggleMeasure: () -> Unit,
    onToggleTide: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xD9181820)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("Ma position", CrosshairIcon, active = following, onClick = onRecenter)
            ToolButton(
                "Mesurer",
                RulerIcon,
                active = tapTool == TapTool.MEASURE,
                onClick = onToggleMeasure,
            )
            ToolButton("Marée", WaveIcon, active = tideVisible, onClick = onToggleTide)
            ToolButton("Sources", InfoIcon, active = false, onClick = onOpenSources)
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else Color(0xB3FFFFFF),
        )
    }
}

/** Distance and initial bearing between the two tapped points. */
@Composable
fun LegCard(from: LatLon, to: LatLon, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val distanceNm = Geodesy.distanceNauticalMiles(from, to)
    val bearing = Geodesy.initialBearingDegrees(from, to)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xD9181820)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    String.format(Locale.ROOT, "%.2f M   %03d°", distanceNm, bearing.roundToInt() % 360),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "${(distanceNm * Geodesy.METERS_PER_NAUTICAL_MILE).roundToInt()} m — " +
                        "route fond initiale, orthodromie",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0x99FFFFFF),
                )
            }
            FilledTonalButton(onClick = onClear) { Text("Effacer") }
        }
    }
}

@Composable
fun ErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.92f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color.White)
            Button(onClick = onDismiss) { Text("OK") }
        }
    }
}

/**
 * Installs a chart from the phone itself.
 *
 * Uses the system document picker, which needs no storage permission and, crucially for
 * this app, no network: the file is already on the device.
 */
@Composable
fun ImportChartButton(importing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = !importing, modifier = modifier) {
        Text(if (importing) "Import en cours…" else "Importer une carte (.pmtiles)")
    }
}

/** Legend swatch colours, shared by the sources sheet. */
val LEGEND_BANDS: List<Pair<String, Int>> = listOf(
    "Talonnage" to DepthPalette.AGROUND_ARGB,
    "Marge entamée (0–0,5 m)" to DepthPalette.MARGIN_CONSUMED_ARGB,
    "Prudence (0,5–2 m)" to DepthPalette.CAUTION_ARGB,
    "Eau libre (> 2 m)" to DepthPalette.SAFE_SHALLOW_ARGB,
    "Pas de donnée" to DepthPalette.NO_DATA_ARGB,
)

internal fun format2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private const val TIDE_MIN_METERS = -1.0f
private const val TIDE_MAX_METERS = 14.0f
