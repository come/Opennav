/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.core.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A position in WGS 84 degrees. */
public data class LatLon(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}

/**
 * Great-circle distances and bearings.
 *
 * A sphere, not an ellipsoid. Over the legs this app deals with -- a few miles between
 * two taps in a Breton estuary -- the spherical model differs from Vincenty by well under
 * a metre, which is far below the precision of anything else on screen. It is also
 * branch-free and impossible to get stuck in a non-convergent iteration, which matters
 * more on a boat than the last centimetre.
 */
public object Geodesy {

    /** Mean Earth radius, metres (IUGG). */
    public const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    /** One international nautical mile, in metres. */
    public const val METERS_PER_NAUTICAL_MILE: Double = 1852.0

    /** Great-circle distance in metres. */
    public fun distanceMeters(from: LatLon, to: LatLon): Double {
        val phi1 = Math.toRadians(from.latitude)
        val phi2 = Math.toRadians(to.latitude)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(normalizeLongitudeDelta(to.longitude - from.longitude))
        // Haversine: stable for the short legs this app measures, unlike the spherical
        // law of cosines, which loses precision below a few hundred metres.
        val a = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a).coerceAtMost(1.0))
    }

    /** Great-circle distance in nautical miles. */
    public fun distanceNauticalMiles(from: LatLon, to: LatLon): Double =
        distanceMeters(from, to) / METERS_PER_NAUTICAL_MILE

    /**
     * Initial true bearing from [from] to [to], in degrees clockwise from true north,
     * in `[0, 360)`.
     *
     * This is the *initial* great-circle bearing. It is the heading to steer at the
     * start of the leg, not a constant rhumb-line course; over the distances involved
     * here the two agree to a fraction of a degree, but the distinction is real and the
     * name says which one this is.
     */
    public fun initialBearingDegrees(from: LatLon, to: LatLon): Double {
        val phi1 = Math.toRadians(from.latitude)
        val phi2 = Math.toRadians(to.latitude)
        val dLambda = Math.toRadians(normalizeLongitudeDelta(to.longitude - from.longitude))
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        if (abs(y) < 1e-15 && abs(x) < 1e-15) return 0.0 // coincident points
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Total length of a polyline, in metres. */
    public fun pathLengthMeters(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Wraps a longitude difference into `(-180, 180]` so legs take the short way round. */
    public fun normalizeLongitudeDelta(delta: Double): Double {
        var d = delta % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }
}
