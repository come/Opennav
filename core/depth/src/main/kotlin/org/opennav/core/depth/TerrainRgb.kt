/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.core.depth

import kotlin.math.ceil

/**
 * Mapbox Terrain-RGB codec.
 *
 * `elevation = -10000 + ((R * 256 * 256 + G * 256 + B) * 0.1)`
 *
 * Elevations are metres, **positive up**, referenced to the chart datum
 * (*zéro hydrographique*). A seabed 3 m below chart datum is `-3.0`; a rock drying 1 m
 * above chart datum is `+1.0`. See [docs/DATA.md] for the vertical-datum conversion the
 * pipeline must apply to the raw Litto3D tiles.
 *
 * Two conservatism rules live here, and both are load-bearing:
 *
 *  1. [encode] rounds the seabed **up** (shallower), never down. Quantisation to the
 *     0.1 m Terrain-RGB step can therefore cost the mariner up to 10 cm of displayed
 *     water, but it can never invent water that is not there.
 *  2. Absence of survey data is encoded as [NO_DATA_ELEVATION_METERS], a value far
 *     *above* any real elevation rather than far below it. Every consumer that does not
 *     know about the sentinel -- including the GPU, which bilinearly filters the DEM
 *     texture at the edge of a data hole -- therefore degrades towards "this is dry
 *     land, stay out", not towards "this is deep water, come on in".
 */
public object TerrainRgb {

    /** Elevation encoded by RGB(0, 0, 0). */
    public const val BASE_METERS: Double = -10000.0

    /** Quantisation step of the encoding, in metres. */
    public const val INTERVAL_METERS: Double = 0.1

    /** Largest value representable by the encoding, RGB(255, 255, 255). */
    public const val MAX_ENCODABLE_METERS: Double = BASE_METERS + 0xFFFFFF * INTERVAL_METERS

    /**
     * Elevation written by the pipeline where the survey has no data.
     *
     * Deliberately *high*: bathymetric lidar only penetrates 10-20 m depending on
     * turbidity, so data holes are common and often sit right next to navigable water.
     * A sentinel above every real elevation makes any accidental interpolation, texture
     * filtering or overview averaging pull the result towards "aground", which is the
     * safe direction to be wrong in.
     *
     * 9000 m is above the highest terrestrial elevation and far below
     * [MAX_ENCODABLE_METERS], so it round-trips exactly. Encoded as RGB(2, 230, 48).
     */
    public const val NO_DATA_ELEVATION_METERS: Double = 9000.0

    /**
     * Anything at or above this elevation is treated as "no survey data", not as
     * terrain. The gap between this threshold and [NO_DATA_ELEVATION_METERS] absorbs
     * GPU filtering that blends a real sample with a sentinel sample.
     */
    public const val NO_DATA_THRESHOLD_METERS: Double = 8000.0

    /** Decodes one Terrain-RGB pixel to metres. Channel values must be 0..255. */
    public fun decode(r: Int, g: Int, b: Int): Double {
        require(r in 0..255 && g in 0..255 && b in 0..255) {
            "Terrain-RGB channels must be 0..255, got ($r, $g, $b)"
        }
        return BASE_METERS + ((r shl 16) or (g shl 8) or b) * INTERVAL_METERS
    }

    /**
     * Encodes an elevation in metres, rounding **up** to the next representable value so
     * that the decoded seabed is never deeper than the true one.
     *
     * Values outside the encodable range are clamped. Clamping at the low end
     * ([BASE_METERS]) is the only place where this function is forced to be optimistic;
     * it cannot occur for any real bathymetry, and the pipeline rejects such tiles.
     *
     * @return the packed 0xRRGGBB value.
     */
    public fun encode(elevationMetres: Double): Int {
        require(!elevationMetres.isNaN()) { "cannot encode NaN elevation" }
        val clamped = elevationMetres.coerceIn(BASE_METERS, MAX_ENCODABLE_METERS)
        // ceil, not round: quantisation must raise the seabed, never lower it.
        val steps = ceil((clamped - BASE_METERS) / INTERVAL_METERS - QUANTISATION_EPSILON)
        return steps.toInt().coerceIn(0, 0xFFFFFF)
    }

    /** Red channel of [encode]. */
    public fun red(packed: Int): Int = (packed shr 16) and 0xFF

    /** Green channel of [encode]. */
    public fun green(packed: Int): Int = (packed shr 8) and 0xFF

    /** Blue channel of [encode]. */
    public fun blue(packed: Int): Int = packed and 0xFF

    /** True when [elevationMetres] carries no survey information. */
    public fun isNoData(elevationMetres: Double): Boolean =
        elevationMetres.isNaN() || elevationMetres >= NO_DATA_THRESHOLD_METERS

    /**
     * Largest elevation strictly below [NO_DATA_THRESHOLD_METERS] that the encoding can
     * represent. Used by the pipeline to assert that no real sample can be mistaken for
     * the sentinel.
     */
    public fun highestRealElevation(): Double {
        val stepsBelowThreshold =
            ceil((NO_DATA_THRESHOLD_METERS - BASE_METERS) / INTERVAL_METERS).toInt() - 1
        return BASE_METERS + stepsBelowThreshold * INTERVAL_METERS
    }

    // Guards against ceil() jumping a whole step when the input is already exactly on a
    // step but arrives with float noise (e.g. 3.0000000000000004 / 0.1 == 30.000000000000004).
    private const val QUANTISATION_EPSILON = 1e-9
}
