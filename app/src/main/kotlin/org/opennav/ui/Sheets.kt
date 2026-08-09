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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontFamily
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
    chartName: String?,
    basemapName: String?,
    seamarkName: String?,
    importing: Boolean,
    /** True while the bathymetry on screen is the fabricated demo, so it can be dropped. */
    canRemoveDemo: Boolean,
    onImport: () -> Unit,
    onRemoveDemo: () -> Unit,
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
            Text("Carte", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bathymétrie : ${chartName ?: "aucune"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Fond de carte : ${basemapName ?: "aucun"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Balisage : ${seamarkName ?: "aucun"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            ImportChartButton(importing = importing, onClick = onImport)
            if (canRemoveDemo) {
                TextButton(onClick = onRemoveDemo) {
                    Text("Supprimer la carte de démonstration")
                }
                Text(
                    "Utile dès que le balisage réel est installé : un fond inventé sous " +
                        "de vraies bouées est pire que pas de fond du tout, parce que ses " +
                        "couleurs se lisent comme de l'information. La carte ne " +
                        "reviendra pas toute seule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Le fichier doit déjà être sur le téléphone. Opennav n'a aucun accès " +
                    "réseau : il ne peut pas aller chercher une carte tout seul, et la " +
                    "fabrication d'une zone depuis les dalles Litto3D se fait sur un " +
                    "ordinateur (voir le README).",
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
    basemapName: String?,
    seamarkName: String?,
    header: PmtilesHeader?,
    /** True while the depths on screen are the fabricated demo rather than a survey. */
    syntheticBathymetry: Boolean,
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
            if (syntheticBathymetry) {
                // Crediting the SHOM for an invented seabed would be both a false
                // attribution and the most misleading sentence in the app.
                Text(
                    "Aucune. Les profondeurs affichées sont FICTIVES : c'est la carte " +
                        "de démonstration livrée avec l'app, un fond marin inventé posé " +
                        "sous un vrai trait de côte pour que l'écran ne soit pas vide.\n\n" +
                        "Elle ne contient aucune donnée du Shom ni de l'IGN. Le chenal, " +
                        "le haut-fond et le trou sans donnée sont des figures " +
                        "géométriques. Ne naviguez pas avec.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Pour de vraies profondeurs : fabriquez une zone depuis les dalles " +
                        "Litto3D du Shom (voir le README), puis importez-la. Elle " +
                        "remplacera la démonstration automatiquement.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Shom - IGN, 2024. https://doi.org/10.17183/LITTO3D_BZH_2018_2021\n" +
                        "Licence Ouverte 2.0 (Etalab).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Fond de carte", style = MaterialTheme.typography.titleSmall)
            Text(
                if (basemapName == null) {
                    "Aucun fond de carte installé : pas de trait de côte, pas d'îles, " +
                        "pas de jetées. Il se fabrique avec tools/build_basemap.py à " +
                        "partir d'un extrait OpenStreetMap."
                } else {
                    "© OpenStreetMap contributors, ODbL.\nSource : $basemapName"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (basemapName != null) {
                Text(
                    "⚠ Le continent n'est pas rempli, seulement tracé. Dans OSM, le " +
                        "trait de côte continental est une suite de lignes ouvertes ; " +
                        "les refermer à l'intérieur d'une emprise peut dessiner de la " +
                        "terre là où il y a de l'eau, et c'est l'erreur à ne pas " +
                        "commettre. Les îles, elles, sont remplies : leur contour est " +
                        "déjà fermé dans la donnée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Balisage", style = MaterialTheme.typography.titleSmall)
            Text(
                if (seamarkName == null) {
                    "Aucun balisage installé. La carte ne montre que la bathymétrie : " +
                        "ni bouées, ni feux, ni épaves. Une couche de balisage se " +
                        "fabrique avec tools/build_seamarks.py et s'importe comme une " +
                        "carte."
                } else {
                    "© OpenSeaMap / OpenStreetMap contributors, ODbL.\n" +
                        "Source : $seamarkName"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (seamarkName != null) {
                Text(
                    "⚠ Les marques sont dessinées en pastilles colorées, pas en " +
                        "symboles IALA. Une pastille jaune vous dit qu'une cardinale " +
                        "existe là ; elle ne vous dit pas de quel côté passer. Les " +
                        "données OSM sont contributives et inégales : une bouée peut " +
                        "manquer, être périmée, ou mal placée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

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
            val provenance = if (syntheticBathymetry) {
                "• Les profondeurs affichées sont inventées : rien de ce qui suit ne " +
                    "s'applique tant qu'une vraie carte n'est pas importée.\n"
            } else {
                "• Le levé date de 2018-2021. Les bancs de sable bougent.\n"
            }
            Text(
                provenance +
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
                    "est celle que vous réglez à la main.\n" +
                    "• Le balisage, quand il est installé, vient d'OpenStreetMap et non " +
                    "d'un service hydrographique. Une zone bleue sans pastille n'est pas " +
                    "une zone sans danger.\n" +
                    "• Aucun courant n'est modélisé.",
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

/**
 * The previous run's stack trace, on the phone that produced it.
 *
 * Shown in full rather than summarised. A trace the user can read is a trace they can
 * paste, and the first useful line is rarely the first line -- picking one to show would
 * be guessing at exactly the moment we have stopped guessing.
 */
@Composable
fun CrashReportDialog(report: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("La fois précédente, l'app s'est arrêtée") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Envoyez ce texte : c'est la seule chose qui dise pourquoi. Il ne " +
                        "contient ni position, ni identifiant — modèle d'appareil, " +
                        "version d'Android et pile d'appels.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Partager") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Effacer") } },
    )
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
