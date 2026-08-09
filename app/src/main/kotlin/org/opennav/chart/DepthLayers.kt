/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.chart

import android.util.Log
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
    const val LAYER_SHORE_BAND = "base-shore-band"
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

    /**
     * Colour behind everything, and deliberately **not** blue.
     *
     * This shows wherever no tile exists at all, which includes the sea when no
     * bathymetry is installed. Colouring it as water is the obvious way to stop the map
     * looking like a void and it is the wrong trade: a dark navy sits a shade away from
     * the deep end of the safe-water ramp, so the edge of a survey would read as more
     * survey. "Nothing known here" has to look like nothing, not like deep water.
     *
     * Lifted off black, though. A void is not more honest for being unreadable.
     */
    private const val BACKGROUND_ARGB = 0xFF1E2126.toInt()

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
        // A tint along the landward side of the shore.
        //
        // The mainland has no polygon to fill, so without this the only thing separating
        // a headland from the channel beside it is a one-pixel line, and on a dark screen
        // that is not a distinction anyone makes at a glance. OSM draws coastline with
        // the land on the left of the way, and MapLibre offsets a line to the right of
        // its direction, so a negative offset lands the band on the ground every time --
        // no polygon assembly, and no way for it to invert.
        //
        // Its width is in pixels, which is what keeps it safe. A band that scaled with
        // the ground would close over a narrow pass as you zoomed in, painting navigable
        // water as shore; a constant handful of pixels covers less and less ground the
        // closer you look, so the one place it could mislead is the one place it retreats
        // from. It stops entirely below z9, where the Brittany coastline is too intricate
        // for a band to be anything but a smear.
        LineLayer(LAYER_SHORE_BAND, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_COASTLINE)
            .withProperties(
                PropertyFactory.lineColor(LAND_FILL),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(9, 0.0f),
                        Expression.stop(11, 6.0f),
                        Expression.stop(14, 10.0f),
                        Expression.stop(18, 10.0f),
                    ),
                ),
                PropertyFactory.lineOffset(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(9, 0.0f),
                        Expression.stop(11, -3.0f),
                        Expression.stop(14, -5.0f),
                        Expression.stop(18, -5.0f),
                    ),
                ),
            ),
        // Only water areas that say what they are.
        //
        // A base map built before bays were excluded cannot tell a lake from a piece of
        // open sea named "Golfe du Morbihan", and drew the second as a fill ending in a
        // ruled line across navigable water. Those builds emit no `kind` on any water
        // area; every build since emits one on all of them, so the property doubles as a
        // version marker and the fix reaches an archive already on a phone without
        // anyone rebuilding it.
        //
        // The cost is that an older archive loses its inland lakes too. That is the
        // right way round: a missing pond is a missing decoration, an invented shoreline
        // is a chart saying something untrue about where a boat can go.
        FillLayer(LAYER_WATER, SOURCE_BASEMAP)
            .withSourceLayer(SOURCE_LAYER_WATER)
            .withFilter(Expression.has("kind"))
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
                // Wider than a hairline on purpose. With the mainland unfilled this
                // stroke is the entire answer to "where does the water stop", so it has
                // to survive a glance at arm's length on a moving boat.
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 0.8f),
                        Expression.stop(11, 2.0f),
                        Expression.stop(16, 3.6f),
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

    // Warm for what is dry, cool for what is wet, and nothing in between. That is the
    // one distinction the eye should make without reading anything, so it is carried by
    // hue rather than by lightness: a paper chart is legible photocopied, and a screen
    // at night is dimmed until lightness differences stop existing.
    //
    // Everything here is dark. This is looked at from a cockpit after sunset, where a
    // pale fill is a flash-blind waiting to happen and the pupil never recovers before
    // the next glance at the water.
    private const val LAND_FILL = 0xFF554A32.toInt()
    private const val SHORE_LINE = 0xFFE2D8BA.toInt()
    private const val WATER_FILL = 0xFF10334F.toInt()
    private const val HARBOUR_FILL = 0xFF2A3F52.toInt()
    private const val STRUCTURE_LINE = 0xFFBDB4A0.toInt()
    private const val PLACE_DOT = 0xFFEFE8D6.toInt()

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

    /**
     * Runs [block] only if [style] is still the one on screen.
     *
     * MapLibre throws `IllegalStateException: Calling getSourceAs when a newer style is
     * loading/has loaded` the moment a `Style` stops being current, and a `Style` stops
     * being current as soon as `setStyle` is called again -- which is what importing a
     * chart does. A GPS fix arriving inside that window took the app down on a Pixel 5.
     *
     * Two guards, because they cover different things. `isFullyLoaded` is the documented
     * state the exception is thrown on, and catching is what remains for the race between
     * reading that flag and using the object: the style can be replaced from the map
     * thread between the two. Losing one frame of the position dot is not worth a crash,
     * and every caller re-applies its state when the new style arrives.
     */
    private inline fun onCurrentStyle(style: Style, block: (Style) -> Unit) {
        if (!style.isFullyLoaded) return
        try {
            block(style)
        } catch (error: IllegalStateException) {
            // The exact and only thing MapLibre raises for a superseded style. Anything
            // else is a bug in this file and has no business being swallowed here.
            Log.w(TAG, "style update skipped: ${error.message}")
        }
    }

    /** Re-colours the depth layer. Cheap enough to call on every slider frame. */
    fun updateDepthRamp(
        style: Style,
        boat: BoatProfile,
        tideHeightMeters: Double,
        deepRangeMeters: Double,
    ) = onCurrentStyle(style) {
        val layer = it.getLayerAs<ColorReliefLayer>(LAYER_DEPTH) ?: return@onCurrentStyle
        layer.setProperties(
            PropertyFactory.colorReliefColor(colorRamp(boat, tideHeightMeters, deepRangeMeters)),
        )
    }

    fun updateRoute(style: Style, points: List<LatLon>) = onCurrentStyle(style) {
        it.getSourceAs<GeoJsonSource>(SOURCE_ROUTE)?.setGeoJson(routeGeoJson(points))
    }

    fun updatePosition(style: Style, position: LatLon?) = onCurrentStyle(style) {
        val json = if (position == null) {
            emptyFeatureCollection()
        } else {
            """{"type":"FeatureCollection","features":[${pointFeature(position)}]}"""
        }
        it.getSourceAs<GeoJsonSource>(SOURCE_POSITION)?.setGeoJson(json)
    }

    private const val TAG = "DepthLayers"

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
