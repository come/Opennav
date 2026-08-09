/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.core.depth

/** A stop of the elevation-to-colour ramp. [argb] is 0xAARRGGBB. */
public data class ColorStop(val elevationMeters: Double, val argb: Int)

/**
 * Turns a [BoatProfile] and a tide height into the colour ramp that MapLibre's
 * `color-relief` layer consumes.
 *
 * The layer's fragment shader binary-searches the stop array and interpolates linearly
 * between the two surrounding stops, using the stop elevations verbatim -- it does not
 * resample the ramp into a fixed-resolution lookup table. Two consequences shape this
 * file:
 *
 *  * Band edges are *exact*. A pair of stops [EDGE_EPSILON_METERS] apart reads as a hard
 *    boundary on screen, so the plan's discrete red / orange / yellow bands survive.
 *  * A stop far from the others (the no-data sentinel at 9000 m) costs nothing in
 *    precision, so it can live in the same ramp as the 0.5 m-wide bands.
 *
 * Everything below is stated in **elevation**, which runs the opposite way to clearance:
 * a *higher* seabed is *less* water. The ramp is therefore the clearance bands mirrored
 * and slid along the axis by [UnderKeelClearance.offsetMeters].
 */
public object DepthPalette {

    // --- Day palette -------------------------------------------------------------
    // Opaque, because a translucent "you are aground" is not a warning.

    /** No survey data. Deliberately not on the blue-to-red axis: it is not a depth. */
    public const val NO_DATA_ARGB: Int = 0xFF8E5FA8.toInt()

    /** clearance < 0. */
    public const val AGROUND_ARGB: Int = 0xFFD7191C.toInt()

    /** 0 <= clearance < 0.5 m. */
    public const val MARGIN_CONSUMED_ARGB: Int = 0xFFF07C1F.toInt()

    /** 0.5 m <= clearance < 2 m. */
    public const val CAUTION_ARGB: Int = 0xFFF4E04D.toInt()

    /** Shallow end of the safe-water gradient, at exactly 2 m of clearance. */
    public const val SAFE_SHALLOW_ARGB: Int = 0xFF9ECAE1.toInt()

    /** Deep end of the safe-water gradient, at [DEFAULT_DEEP_RANGE_METERS] of clearance. */
    public const val SAFE_DEEP_ARGB: Int = 0xFF08306B.toInt()

    /**
     * Clearance beyond which the blue stops getting darker. Purely cosmetic: past this
     * point the answer is "plenty" and the exact figure stops being interesting.
     */
    public const val DEFAULT_DEEP_RANGE_METERS: Double = 30.0

    /**
     * Separation between the two stops that form a hard band edge. Small enough to read
     * as a step at any zoom, large enough to stay well clear of float32 spacing at the
     * magnitudes involved (~1e-3 m against a ~1e-6 m float32 step near 10 m).
     */
    public const val EDGE_EPSILON_METERS: Double = 0.001

    /**
     * Builds the ramp for a given instant.
     *
     * @param tideHeightMeters height of tide above chart datum. During Phase 0 this is
     *   driven straight from the on-screen slider; from Phase 1 it comes from the
     *   harmonic engine.
     * @param deepRangeMeters clearance at which the blue gradient bottoms out.
     * @return stops in strictly increasing elevation order, ready to be handed to
     *   `color-relief-color`.
     */
    public fun stops(
        tideHeightMeters: Double,
        boat: BoatProfile,
        deepRangeMeters: Double = DEFAULT_DEEP_RANGE_METERS,
    ): List<ColorStop> {
        require(deepRangeMeters > DepthStatus.CAUTION_CEILING_METERS) {
            "deep range must be deeper than the caution band, got $deepRangeMeters"
        }
        val offset = UnderKeelClearance.offsetMeters(tideHeightMeters, boat)

        // elevation(clearance) = offset - clearance, so the list below is written
        // deepest-clearance first in order to come out ascending in elevation.
        val stops = listOf(
            // Deep water, and everything deeper: MapLibre clamps below the first stop.
            ColorStop(offset - deepRangeMeters, SAFE_DEEP_ARGB),
            ColorStop(offset - DepthStatus.CAUTION_CEILING_METERS, SAFE_SHALLOW_ARGB),
            // 2 m of clearance: hard edge into the caution band.
            ColorStop(offset - DepthStatus.CAUTION_CEILING_METERS + EDGE_EPSILON_METERS, CAUTION_ARGB),
            ColorStop(offset - DepthStatus.MARGIN_CONSUMED_CEILING_METERS, CAUTION_ARGB),
            // 0.5 m of clearance.
            ColorStop(
                offset - DepthStatus.MARGIN_CONSUMED_CEILING_METERS + EDGE_EPSILON_METERS,
                MARGIN_CONSUMED_ARGB,
            ),
            ColorStop(offset, MARGIN_CONSUMED_ARGB),
            // 0 m of clearance: you are touching.
            ColorStop(offset + EDGE_EPSILON_METERS, AGROUND_ARGB),
            // Aground stays aground all the way up to the no-data band. This is what
            // makes the sentinel fail safe: a cell the pipeline could not survey clamps
            // into red long before it reaches the violet, and so does any GPU-filtered
            // blend between a real sample and the sentinel.
            ColorStop(TerrainRgb.NO_DATA_THRESHOLD_METERS, AGROUND_ARGB),
            ColorStop(TerrainRgb.NO_DATA_THRESHOLD_METERS + EDGE_EPSILON_METERS, NO_DATA_ARGB),
            ColorStop(TerrainRgb.NO_DATA_ELEVATION_METERS, NO_DATA_ARGB),
        )
        check(isStrictlyIncreasing(stops)) {
            // Would mean the offset drifted far enough to collide the bands with the
            // sentinel, i.e. a tide of several kilometres. Fail loudly rather than hand
            // the GPU a ramp it will binary-search incorrectly.
            "colour ramp stops are not strictly increasing: $stops"
        }
        return stops
    }

    /**
     * Colour a single elevation would get, evaluated on the CPU exactly the way the
     * shader does it. Used by the tests, and by anything that needs a legend swatch or a
     * readout under the cursor rather than a rendered tile.
     */
    public fun colorAt(elevationMeters: Double, stops: List<ColorStop>): Int {
        require(stops.isNotEmpty()) { "empty ramp" }
        if (elevationMeters <= stops.first().elevationMeters) return stops.first().argb
        if (elevationMeters >= stops.last().elevationMeters) return stops.last().argb
        val upper = stops.indexOfFirst { it.elevationMeters >= elevationMeters }
        val lower = upper - 1
        val a = stops[lower]
        val b = stops[upper]
        val span = b.elevationMeters - a.elevationMeters
        val t = if (span <= 0.0) 1.0 else (elevationMeters - a.elevationMeters) / span
        return lerpArgb(a.argb, b.argb, t)
    }

    /**
     * Serialises a ramp as the MapLibre expression that `color-relief-color` expects.
     *
     * Lives here rather than in the Android module for two reasons: it is the point where
     * the safety-critical numbers cross into the renderer, and building it as a string
     * means it can be asserted on by a JVM test instead of only by looking at a phone.
     *
     * The result is fed to `Expression.raw`, which parses it back into a real expression
     * tree, so a malformed string fails at style-load time rather than silently painting
     * the wrong colours.
     */
    public fun toColorReliefColorJson(stops: List<ColorStop>): String {
        require(stops.isNotEmpty()) { "empty ramp" }
        require(isStrictlyIncreasing(stops)) { "ramp stops must be strictly increasing" }
        val body = stops.joinToString(",") { stop ->
            "${formatMeters(stop.elevationMeters)},\"${formatColor(stop.argb)}\""
        }
        val json = "[\"interpolate\",[\"linear\"],[\"elevation\"],$body]"
        // Rounding the elevations for the wire format must not collapse two stops that
        // are only EDGE_EPSILON_METERS apart, or a hard band edge would turn into a
        // gradient -- or worse, into a non-monotonic ramp the shader binary-searches
        // incorrectly.
        check(elevationsInJsonAreStrictlyIncreasing(stops)) {
            "serialised ramp lost its ordering at $ELEVATION_DECIMALS decimals: $json"
        }
        return json
    }

    private const val ELEVATION_DECIMALS = 4

    private fun formatMeters(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.${ELEVATION_DECIMALS}f", value)

    private fun formatColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "rgba($r,$g,$b,${String.format(java.util.Locale.ROOT, "%.3f", a / 255.0)})"
    }

    private fun elevationsInJsonAreStrictlyIncreasing(stops: List<ColorStop>): Boolean =
        stops.map { formatMeters(it.elevationMeters).toDouble() }
            .zipWithNext()
            .all { (a, b) -> b > a }

    private fun isStrictlyIncreasing(stops: List<ColorStop>): Boolean =
        stops.zipWithNext().all { (a, b) -> b.elevationMeters > a.elevationMeters }

    private fun lerpArgb(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
