/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.core.depth

/**
 * The one calculation the whole app exists to perform.
 *
 * The plan states it in charted-depth terms:
 *
 * ```
 * eauDisponible = profondeurCarte + hauteurMaree - tirantEau - margeSecurite
 * ```
 *
 * The raster stores *elevation* (positive up, chart-datum referenced) rather than
 * charted depth (positive down), because that is what Terrain-RGB and MapLibre's
 * `raster-dem` sources carry. With `profondeurCarte == -elevation` the same identity is:
 *
 * ```
 * clearance = tide - elevation - draft - margin
 *           = offset - elevation           where offset = tide - draft - margin
 * ```
 *
 * Factoring out [offsetMeters] is what makes the time cursor cheap: the tide, the draft
 * and the margin only ever move the ramp along the elevation axis. Nothing has to be
 * re-tiled or re-decoded when the user drags the slider, which is the whole reason the
 * Phase 0 exit criterion (60 fps while scrubbing) is reachable at all.
 */
public object UnderKeelClearance {

    /**
     * The single scalar that folds tide, draft and margin together.
     *
     * @param tideHeightMeters height of tide above chart datum at the instant of
     *   interest, from the harmonic engine (`:core:tide`, Phase 1).
     */
    public fun offsetMeters(tideHeightMeters: Double, boat: BoatProfile): Double {
        require(tideHeightMeters.isFinite()) { "tide height must be finite" }
        return tideHeightMeters - boat.requiredWaterMeters
    }

    /**
     * Water under the keel at a cell, in metres. Negative means the boat touches.
     *
     * Returns [Double.NaN] where the survey has no data: there is no honest number to
     * give, and callers must render that case distinctly instead of guessing.
     */
    public fun clearanceMeters(
        seabedElevationMeters: Double,
        tideHeightMeters: Double,
        boat: BoatProfile,
    ): Double {
        if (TerrainRgb.isNoData(seabedElevationMeters)) return Double.NaN
        return offsetMeters(tideHeightMeters, boat) - seabedElevationMeters
    }

    /** Classifies a cell for display. See [DepthStatus]. */
    public fun classify(
        seabedElevationMeters: Double,
        tideHeightMeters: Double,
        boat: BoatProfile,
    ): DepthStatus = DepthStatus.of(clearanceMeters(seabedElevationMeters, tideHeightMeters, boat))
}

/**
 * Display bands, in the order the plan specifies them.
 *
 * The band edges are expressed in *clearance* (metres of water under the keel once the
 * draft and the mariner's own margin are already gone), so [MARGIN_CONSUMED] does not
 * mean "you are aground", it means "you are eating into the margin you asked for".
 */
public enum class DepthStatus {
    /** No survey data. Never conflate with deep water. */
    NO_DATA,

    /** clearance &lt; 0: the keel is in the mud. */
    AGROUND,

    /** 0 &le; clearance &lt; 0.5 m. */
    MARGIN_CONSUMED,

    /** 0.5 m &le; clearance &lt; 2 m. */
    CAUTION,

    /** clearance &ge; 2 m. */
    SAFE,

    ;

    public companion object {
        /** Upper edge of [MARGIN_CONSUMED], in metres of clearance. */
        public const val MARGIN_CONSUMED_CEILING_METERS: Double = 0.5

        /** Upper edge of [CAUTION], in metres of clearance. */
        public const val CAUTION_CEILING_METERS: Double = 2.0

        public fun of(clearanceMeters: Double): DepthStatus = when {
            clearanceMeters.isNaN() -> NO_DATA
            clearanceMeters < 0.0 -> AGROUND
            clearanceMeters < MARGIN_CONSUMED_CEILING_METERS -> MARGIN_CONSUMED
            clearanceMeters < CAUTION_CEILING_METERS -> CAUTION
            else -> SAFE
        }
    }
}
