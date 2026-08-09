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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.VectorSource
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
    const val SOURCE_BASEMAP = "basemap"
    const val SOURCE_SEAMARKS = "seamarks"
    const val SOURCE_ROUTE = "route"
    const val SOURCE_POSITION = "position"

    const val LAYER_BACKGROUND = "background"
    const val LAYER_DEPTH = "depth"
    const val LAYER_WATER = "base-water"
    const val LAYER_HARBOUR = "base-harbour"
    const val LAYER_LAND = "base-land"
    const val LAYER_COASTLINE = "base-coastline"
    const val LAYER_STRUCTURE = "base-structure"
    const val LAYER_PLACE = "base-place"
    const val LAYER_SEAMARKS = "seamark-dots"
    const val LAYER_ROUTE_LINE = "route-line"
    const val LAYER_ROUTE_POINTS = "route-points"
    const val LAYER_POSITION = "position-dot"

    /** Source layers inside the base map tiles, set by tools/build_basemap.py. */
    private const val SOURCE_LAYER_LAND = "land"
    private const val SOURCE_LAYER_WATER = "water"
    private const val SOURCE_LAYER_HARBOUR = "harbour"
    private const val SOURCE_LAYER_COASTLINE = "coastline"
    private const val SOURCE_LAYER_STRUCTURE = "structure"
    private const val SOURCE_LAYER_PLACE = "place"

    /** Tile size of the archives the pipeline produces. */
    const val TILE_SIZE = 256

    /** Layer name inside the seamark vector tiles, set by tools/build_seamarks.py. */
    const val SEAMARK_SOURCE_LAYER = "seamarks"

    private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[]}"""

    /** Colour behind everything: not blue, so "no tile here" cannot read as water. */
    private const val BACKGROUND_ARGB = 0xFF1A1A1E.toInt()

    /**
     * @param archive Terrain-RGB bathymetry, or null to draw everything else without it.
     *
     * Null is a real case, not a degraded one. Buoyage plus a GPS position is already a
     * usable thing to look at, and it is the only thing available before a Litto3D zone
     * has been built -- which takes an evening. Refusing to draw a map until the depths
     * exist meant the buoyage a user had just spent time building could not be seen at
     * all.
     */
    fun styleBuilder(
        archive: File?,
        basemap: File?,
        seamarks: File?,
        boat: BoatProfile,
        tideHeightMeters: Double,
        deepRangeMeters: Double,
    ): Style.Builder {
        val background = org.maplibre.android.style.layers.BackgroundLayer(LAYER_BACKGROUND)
            .withProperties(PropertyFactory.backgroundColor(BACKGROUND_ARGB))

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

        val builder = Style.Builder()
            .fromJson(EMPTY_STYLE)
            .withSource(GeoJsonSource(SOURCE_ROUTE, emptyFeatureCollection()))
            .withSource(GeoJsonSource(SOURCE_POSITION, emptyFeatureCollection()))
            .withLayer(background)

        if (archive != null) {
            val depth = ColorReliefLayer(LAYER_DEPTH, SOURCE_BATHY).apply {
                setProperties(
                    PropertyFactory.colorReliefColor(
                        colorRamp(boat, tideHeightMeters, deepRangeMeters),
                    ),
                    PropertyFactory.colorReliefOpacity(1.0f),
                )
            }
            builder
                .withSource(
                    RasterDemSource(SOURCE_BATHY, ChartArchive.sourceUri(archive), TILE_SIZE),
                )
                .withLayer(depth)
        }

        // The base map goes over the depths. That ordering is what makes an island read
        // as an island: the depth ramp paints land above chart datum in the aground red,
        // which is correct as an answer to "can I go there" and useless as a coastline.
        if (basemap != null) {
            builder.withSource(
                VectorSource(SOURCE_BASEMAP, ChartArchive.sourceUri(basemap)),
            )
            basemapLayers().forEach { builder.withLayer(it) }
        }

        // Buoyage sits above the depths and below the mariner's own marks: it is
        // reference, not something they placed.
        if (seamarks != null) {
            builder
                .withSource(VectorSource(SOURCE_SEAMARKS, ChartArchive.sourceUri(seamarks)))
                .withLayer(seamarkLayer())
        }

        return builder
            .withLayer(routeLine)
            .withLayer(routePoints)
            .withLayer(position)
    }

    /**
     * The OSM base map, in draw order.
     *
     * Deliberately quiet. This is the paper under the chart, not the chart: it has to
     * make an island read as an island and a breakwater as something solid, without
     * competing with the depth bands or the buoyage for attention. Hence unsaturated
     * greys and greens against the blues, and no fill anywhere near the blue of safe
     * water.
     *
     * **The mainland is not filled**, because `build_basemap.py` cannot fill it without
     * reassembling coastline rings against the clip box, and a mistake there draws land
     * over navigable water. The coastline stroke carries the shore instead. Islands are
     * filled because their rings are closed in OSM already.
     */
    private fun basemapLayers(): List<org.maplibre.android.style.layers.Layer> = listOf(
        FillLayer(LAYER_WATER, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_WATER)
            .withProperties(
                PropertyFactory.fillColor(WATER_FILL),
                PropertyFactory.fillOpacity(1.0f),
            ),
        FillLayer(LAYER_HARBOUR, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_HARBOUR)
            .withProperties(
                PropertyFactory.fillColor(HARBOUR_FILL),
                PropertyFactory.fillOpacity(0.55f),
            ),
        FillLayer(LAYER_LAND, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_LAND)
            .withProperties(
                PropertyFactory.fillColor(LAND_FILL),
                PropertyFactory.fillOutlineColor(SHORE_LINE),
                PropertyFactory.fillOpacity(1.0f),
            ),
        LineLayer(LAYER_COASTLINE, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_COASTLINE)
            .withProperties(
                PropertyFactory.lineColor(SHORE_LINE),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 0.6f),
                        Expression.stop(11, 1.4f),
                        Expression.stop(16, 2.6f),
                    ),
                ),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        LineLayer(LAYER_STRUCTURE, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_STRUCTURE)
            .withProperties(
                PropertyFactory.lineColor(STRUCTURE_LINE),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(11, 1.0f),
                        Expression.stop(16, 4.0f),
                    ),
                ),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            ),
        // Settlements as plain dots. Drawing their names needs a glyph source, and an
        // app with no network has to carry the font ranges in the APK -- a separate
        // job. The names are already in the tiles, so that day is a style change.
        CircleLayer(LAYER_PLACE, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_PLACE)
            .withProperties(
                PropertyFactory.circleRadius(2.5f),
                PropertyFactory.circleColor(PLACE_DOT),
                PropertyFactory.circleOpacity(0.8f),
            ),
    )

    private const val LAND_FILL = 0xFF2E3A2C.toInt()
    private const val SHORE_LINE = 0xFFBFC9B4.toInt()
    private const val WATER_FILL = 0xFF16324A.toInt()
    private const val HARBOUR_FILL = 0xFF3A4652.toInt()
    private const val STRUCTURE_LINE = 0xFF8A8F96.toInt()
    private const val PLACE_DOT = 0xFFD8D8D0.toInt()

    /**
     * Buoyage, drawn as coloured dots.
     *
     * **These are not IALA symbols.** A proper chart draws a south cardinal as a specific
     * shape with a specific topmark; this draws a yellow-black dot. Rendering real
     * symbology needs a sprite sheet, and labels need a glyph source, neither of which an
     * app with no network can fetch -- both have to be bundled, which is a Phase 2 job.
     *
     * A dot in roughly the right colour in roughly the right place is still worth having:
     * it tells you a mark exists there, which the depth layer alone never will. It is not
     * enough to identify one, and the Sources screen says so.
     */
    private fun seamarkLayer(): CircleLayer = CircleLayer(LAYER_SEAMARKS, SOURCE_SEAMARKS)
        .withSourceLayer(SEAMARK_SOURCE_LAYER)
        .withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(10, 2.0f),
                    Expression.stop(13, 4.5f),
                    Expression.stop(16, 8.0f),
                ),
            ),
            PropertyFactory.circleColor(seamarkColour()),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeColor(0xFF101014.toInt()),
            PropertyFactory.circleOpacity(0.95f),
        )

    /**
     * Colour from the OSM tags, closest to the real mark rather than to a legend.
     *
     * `colour` is what OpenSeaMap actually carries for most marks; the `match` on
     * `type` only catches what has no colour of its own.
     */
    private fun seamarkColour(): Expression = Expression.raw(
        // One line on purpose: MapLibre parses this string, and reflowing it for
        // readability has a habit of introducing a stray character that turns the whole
        // style into a silent no-op.
        "[\"case\"," +
            "[\"==\",[\"get\",\"type\"],\"wreck\"],\"$DANGER\"," +
            "[\"==\",[\"get\",\"type\"],\"rock\"],\"$DANGER\"," +
            "[\"==\",[\"get\",\"type\"],\"obstruction\"],\"$DANGER\"," +
            "[\"==\",[\"get\",\"type\"],\"buoy_isolated_danger\"],\"$BLACK\"," +
            "[\"==\",[\"get\",\"type\"],\"beacon_isolated_danger\"],\"$BLACK\"," +
            "[\"==\",[\"get\",\"colour\"],\"red\"],\"$PORT_RED\"," +
            "[\"==\",[\"get\",\"colour\"],\"green\"],\"$STARBOARD_GREEN\"," +
            "[\"==\",[\"get\",\"colour\"],\"yellow\"],\"$CARDINAL_YELLOW\"," +
            "[\"==\",[\"get\",\"colour\"],\"white\"],\"$WHITE\"," +
            "[\"==\",[\"get\",\"colour\"],\"black\"],\"$BLACK\"," +
            "[\"==\",[\"get\",\"lateral\"],\"port\"],\"$PORT_RED\"," +
            "[\"==\",[\"get\",\"lateral\"],\"starboard\"],\"$STARBOARD_GREEN\"," +
            "[\"has\",\"cardinal\"],\"$CARDINAL_YELLOW\"," +
            "[\"has\",\"light_colour\"],\"$LIGHT\"," +
            "\"$UNKNOWN_MARK\"]",
    )

    private const val DANGER = "rgba(255,64,160,1)"
    private const val PORT_RED = "rgba(214,40,40,1)"
    private const val STARBOARD_GREEN = "rgba(46,170,80,1)"
    private const val CARDINAL_YELLOW = "rgba(240,200,40,1)"
    private const val WHITE = "rgba(245,245,245,1)"
    private const val BLACK = "rgba(30,30,30,1)"
    private const val LIGHT = "rgba(255,245,200,1)"
    private const val UNKNOWN_MARK = "rgba(200,200,210,1)"

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
