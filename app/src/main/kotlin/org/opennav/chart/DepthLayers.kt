/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.chart

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.ColorReliefLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.opennav.core.depth.BoatProfile
import org.opennav.core.depth.DepthPalette
import org.opennav.core.geo.LatLon
import java.io.File

/**
 * Builds and updates the map style.
 *
 * The depth colouring is a `color-relief` layer over a `raster-dem` source. That
 * combination is the whole reason this app needs no native code: MapLibre's shader
 * decodes Terrain-RGB on the GPU and binary-searches a ramp of exact float stops, so
 * changing the tide only means handing it a new ramp -- no tile re-decoding, no bitmap
 * work, nothing that touches the CPU per pixel.
 */
object DepthLayers {

    const val SOURCE_BATHY = "bathy"
    const val SOURCE_ROUTE = "route"
    const val SOURCE_POSITION = "position"

    const val LAYER_BACKGROUND = "background"
    const val LAYER_DEPTH = "depth"
    const val LAYER_ROUTE_LINE = "route-line"
    const val LAYER_ROUTE_POINTS = "route-points"
    const val LAYER_POSITION = "position-dot"

    /** Tile size of the archives the pipeline produces. */
    const val TILE_SIZE = 256

    private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[]}"""

    /** Colour behind everything: not blue, so "no tile here" cannot read as water. */
    private const val BACKGROUND_ARGB = 0xFF1A1A1E.toInt()

    fun styleBuilder(
        archive: File,
        boat: BoatProfile,
        tideHeightMeters: Double,
        deepRangeMeters: Double,
    ): Style.Builder {
        val background = org.maplibre.android.style.layers.BackgroundLayer(LAYER_BACKGROUND)
            .withProperties(PropertyFactory.backgroundColor(BACKGROUND_ARGB))

        val depth = ColorReliefLayer(LAYER_DEPTH, SOURCE_BATHY).apply {
            setProperties(
                PropertyFactory.colorReliefColor(
                    colorRamp(boat, tideHeightMeters, deepRangeMeters),
                ),
                PropertyFactory.colorReliefOpacity(1.0f),
            )
        }

        val routeLine = LineLayer(LAYER_ROUTE_LINE, SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor(0xFFFFFFFF.toInt()),
            PropertyFactory.lineWidth(3.0f),
            PropertyFactory.lineDasharray(arrayOf(2.0f, 1.5f)),
        )
        val routePoints = CircleLayer(LAYER_ROUTE_POINTS, SOURCE_ROUTE).withProperties(
            PropertyFactory.circleRadius(6.0f),
            PropertyFactory.circleColor(0xFFFFFFFF.toInt()),
            PropertyFactory.circleStrokeWidth(2.0f),
            PropertyFactory.circleStrokeColor(0xFF101010.toInt()),
        )
        val position = CircleLayer(LAYER_POSITION, SOURCE_POSITION).withProperties(
            PropertyFactory.circleRadius(8.0f),
            PropertyFactory.circleColor(0xFF2196F3.toInt()),
            PropertyFactory.circleStrokeWidth(3.0f),
            PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
        )

        return Style.Builder()
            .fromJson(EMPTY_STYLE)
            .withSource(RasterDemSource(SOURCE_BATHY, ChartArchive.sourceUri(archive), TILE_SIZE))
            .withSource(GeoJsonSource(SOURCE_ROUTE, emptyFeatureCollection()))
            .withSource(GeoJsonSource(SOURCE_POSITION, emptyFeatureCollection()))
            .withLayer(background)
            .withLayer(depth)
            .withLayer(routeLine)
            .withLayer(routePoints)
            .withLayer(position)
    }

    /** Re-colours the depth layer. Cheap enough to call on every slider frame. */
    fun updateDepthRamp(
        style: Style,
        boat: BoatProfile,
        tideHeightMeters: Double,
        deepRangeMeters: Double,
    ) {
        val layer = style.getLayerAs<ColorReliefLayer>(LAYER_DEPTH) ?: return
        layer.setProperties(
            PropertyFactory.colorReliefColor(colorRamp(boat, tideHeightMeters, deepRangeMeters)),
        )
    }

    fun updateRoute(style: Style, points: List<LatLon>) {
        style.getSourceAs<GeoJsonSource>(SOURCE_ROUTE)?.setGeoJson(routeGeoJson(points))
    }

    fun updatePosition(style: Style, position: LatLon?) {
        val json = if (position == null) {
            emptyFeatureCollection()
        } else {
            """{"type":"FeatureCollection","features":[${pointFeature(position)}]}"""
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_POSITION)?.setGeoJson(json)
    }

    private fun colorRamp(
        boat: BoatProfile,
        tideHeightMeters: Double,
        deepRangeMeters: Double,
    ): Expression = Expression.raw(
        DepthPalette.toColorReliefColorJson(
            DepthPalette.stops(tideHeightMeters, boat, deepRangeMeters),
        ),
    )

    private fun emptyFeatureCollection() = """{"type":"FeatureCollection","features":[]}"""

    private fun routeGeoJson(points: List<LatLon>): String {
        if (points.isEmpty()) return emptyFeatureCollection()
        val features = mutableListOf<String>()
        points.forEach { features += pointFeature(it) }
        if (points.size >= 2) {
            val coordinates = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
            features += """{"type":"Feature","properties":{},"geometry":""" +
                """{"type":"LineString","coordinates":[$coordinates]}}"""
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun pointFeature(p: LatLon) =
        """{"type":"Feature","properties":{},"geometry":""" +
            """{"type":"Point","coordinates":[${p.longitude},${p.latitude}]}}"""
}
