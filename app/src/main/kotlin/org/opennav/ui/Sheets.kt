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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.opennav.chart.PmtilesHeader
import org.opennav.core.depth.BoatProfile
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    boat: BoatProfile,
    deepRangeMeters: Double,
    showHud: Boolean,
    onBoatChange: (BoatProfile) -> Unit,
    onDeepRangeChange: (Double) -> Unit,
    onShowHudChange: (Boolean) -> Unit,
    onOpenSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Bateau", style = MaterialTheme.typography.titleMedium)

            LabelledSlider(
                label = "Tirant d'eau",
                value = boat.draftMeters,
                range = 0.2f..5.0f,
                onChange = { onBoatChange(boat.copy(draftMeters = it)) },
            )
            LabelledSlider(
                label = "Marge de sécurité",
                value = boat.safetyMarginMeters,
                range = 0.0f..4.0f,
                onChange = { onBoatChange(boat.copy(safetyMarginMeters = it)) },
            )
            Text(
                "L'app ne colore en bleu que l'eau où il reste " +
                    "${format2(boat.requiredWaterMeters)} m sous la sonde, marge comprise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Divider(Modifier.padding(vertical = 10.dp))
            Text("Affichage", style = MaterialTheme.typography.titleMedium)

            LabelledSlider(
                label = "Fin du dégradé bleu",
                value = deepRangeMeters,
                range = 5.0f..60.0f,
                onChange = onDeepRangeChange,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Compteur de performances", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showHud, onCheckedChange = onShowHudChange)
            }

            Divider(Modifier.padding(vertical = 10.dp))
            Text("Légende", style = MaterialTheme.typography.titleMedium)
            LEGEND_BANDS.forEach { (label, argb) -> LegendRow(label, argb) }

            Divider(Modifier.padding(vertical = 10.dp))
            Text(
                "L'écran reste allumé tant que l'app est au premier plan. Carte plein " +
                    "écran + GPS vident une batterie en quelques heures : prévoyez une " +
                    "alimentation à bord.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSources) { Text("Sources et mentions légales") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(
    chartName: String?,
    header: PmtilesHeader?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sources des données", style = MaterialTheme.typography.titleLarge)

            Text("Bathymétrie", style = MaterialTheme.typography.titleSmall)
            Text(
                "Shom - IGN, 2024. https://doi.org/10.17183/LITTO3D_BZH_2018_2021\n" +
                    "Licence Ouverte 2.0 (Etalab).",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Balisage et trait de côte", style = MaterialTheme.typography.titleSmall)
            Text(
                "© OpenSeaMap / OpenStreetMap contributors, ODbL.",
                style = MaterialTheme.typography.bodySmall,
            )

            Divider()
            Text("Carte chargée", style = MaterialTheme.typography.titleSmall)
            Text(chartName ?: "aucune", style = MaterialTheme.typography.bodySmall)
            header?.let {
                Text(
                    String.format(
                        Locale.ROOT,
                        "zooms %d–%d · %d tuiles · %.3f, %.3f → %.3f, %.3f",
                        it.minZoom, it.maxZoom, it.addressedTiles,
                        it.minLat, it.minLon, it.maxLat, it.maxLon,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Divider()
            Text("Ce que l'app ne sait pas", style = MaterialTheme.typography.titleSmall)
            Text(
                "• Le levé date de 2018-2021. Les bancs de sable bougent.\n" +
                    "• Le lidar bathymétrique ne pénètre que 10 à 20 m selon la " +
                    "turbidité ; au-delà il n'y a pas de donnée, affichée en violet et " +
                    "jamais interpolée.\n" +
                    "• Un caillou isolé peut disparaître dans une maille de 10 m. Le " +
                    "rééchantillonnage garde le point le moins profond de chaque maille, " +
                    "mais il ne crée pas ce que le levé n'a pas vu.\n" +
                    "• Les hauteurs d'eau supposent 1013 hPa et pas de vent. Une " +
                    "dépression ou un coup de vent d'ouest peut décaler le niveau réel " +
                    "de plusieurs dizaines de centimètres.\n" +
                    "• Aucun moteur de marée n'est encore embarqué : la hauteur d'eau " +
                    "est celle que vous réglez à la main.",
                style = MaterialTheme.typography.bodySmall,
            )

            Divider()
            Text("Licence", style = MaterialTheme.typography.titleSmall)
            Text(
                "Opennav est un logiciel libre distribué sous GNU GPL v3 ou " +
                    "ultérieure, sans aucune garantie.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* deliberately not dismissible: it has to be read once */ },
        title = { Text("Avant de partir") },
        text = {
            Text(
                "Opennav est un outil d'aide à la décision. Il ne remplace ni les " +
                    "cartes marines officielles du SHOM, ni le sondeur, ni la veille.\n\n" +
                    "Les profondeurs affichées viennent d'un levé de 2018-2021 " +
                    "rééchantillonné à 10 m, corrigé d'une hauteur d'eau que vous " +
                    "saisissez vous-même. Elles peuvent être fausses.\n\n" +
                    "Ne l'utilisez pas comme unique moyen de navigation.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("J'ai compris") } },
    )
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Double) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${format2(value)} m", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
        )
    }
}

@Composable
private fun LegendRow(label: String, argb: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier
                .size(width = 28.dp, height = 16.dp)
                .background(Color(argb), RoundedCornerShape(3.dp)),
        ) {}
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
