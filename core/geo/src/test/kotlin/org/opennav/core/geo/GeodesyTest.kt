package org.opennav.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesyTest {

    // Two buoys either side of the Goulet de Brest, near enough for a sanity check
    // against a chart plotter.
    private val brest = LatLon(48.3833, -4.4950)
    private val camaret = LatLon(48.2775, -4.5936)

    @Test
    fun `distance matches a known leg`() {
        val nm = Geodesy.distanceNauticalMiles(brest, camaret)
        assertEquals(7.47, nm, 0.02)
        assertEquals(
            Geodesy.distanceMeters(brest, camaret),
            nm * Geodesy.METERS_PER_NAUTICAL_MILE,
            1e-6,
        )
    }

    @Test
    fun `one minute of latitude is one nautical mile`() {
        // The definition the whole unit rests on, good to about 0.3 % on a sphere.
        val a = LatLon(48.0, -4.5)
        val b = LatLon(48.0 + 1.0 / 60.0, -4.5)
        assertEquals(1.0, Geodesy.distanceNauticalMiles(a, b), 0.005)
    }

    @Test
    fun `bearings point the way a compass would`() {
        val origin = LatLon(48.0, -4.5)
        assertEquals(0.0, Geodesy.initialBearingDegrees(origin, LatLon(48.1, -4.5)), 0.001)
        assertEquals(180.0, Geodesy.initialBearingDegrees(origin, LatLon(47.9, -4.5)), 0.001)
        // Due east and due west are *not* exactly 090 and 270: a great circle leaving
        // 48 N heading east immediately starts curving polewards, so the initial bearing
        // is a few hundredths of a degree shy of 090. That is the correct answer for the
        // course to steer at the start of the leg, and the gap is what distinguishes it
        // from a rhumb line.
        assertEquals(90.0, Geodesy.initialBearingDegrees(origin, LatLon(48.0, -4.4)), 0.05)
        assertEquals(270.0, Geodesy.initialBearingDegrees(origin, LatLon(48.0, -4.6)), 0.05)
        assertTrue(Geodesy.initialBearingDegrees(origin, LatLon(48.0, -4.4)) < 90.0)
        assertTrue(Geodesy.initialBearingDegrees(origin, LatLon(48.0, -4.6)) > 270.0)
    }

    @Test
    fun `bearings stay inside zero to 360`() {
        val origin = LatLon(48.0, -4.5)
        for (lat in -80..80 step 7) {
            for (lon in -180..179 step 11) {
                val b = Geodesy.initialBearingDegrees(origin, LatLon(lat.toDouble(), lon.toDouble()))
                assertTrue("bearing $b out of range", b >= 0.0 && b < 360.0)
            }
        }
    }

    @Test
    fun `the reciprocal bearing comes back within a degree over a short leg`() {
        val out = Geodesy.initialBearingDegrees(brest, camaret)
        val back = Geodesy.initialBearingDegrees(camaret, brest)
        val reciprocal = (out + 180.0) % 360.0
        assertEquals(reciprocal, back, 0.2)
    }

    @Test
    fun `distance is symmetric and zero for a point on itself`() {
        assertEquals(
            Geodesy.distanceMeters(brest, camaret),
            Geodesy.distanceMeters(camaret, brest),
            1e-9,
        )
        assertEquals(0.0, Geodesy.distanceMeters(brest, brest), 1e-9)
        assertEquals(0.0, Geodesy.initialBearingDegrees(brest, brest), 1e-9)
    }

    @Test
    fun `short legs keep their precision`() {
        // A metre apart. The law of cosines would lose most of this to rounding.
        val a = LatLon(48.35, -4.50)
        val b = LatLon(48.35 + 1.0 / Geodesy.EARTH_RADIUS_METERS * 180.0 / Math.PI, -4.50)
        assertEquals(1.0, Geodesy.distanceMeters(a, b), 0.001)
    }

    @Test
    fun `legs take the short way across the antimeridian`() {
        val a = LatLon(0.0, 179.9)
        val b = LatLon(0.0, -179.9)
        assertEquals(0.2 * 60, Geodesy.distanceNauticalMiles(a, b), 0.02)
        assertEquals(90.0, Geodesy.initialBearingDegrees(a, b), 0.01)
    }

    @Test
    fun `path length adds its legs up`() {
        val path = listOf(brest, camaret, LatLon(48.30, -4.70))
        assertEquals(
            Geodesy.distanceMeters(brest, camaret) + Geodesy.distanceMeters(camaret, path[2]),
            Geodesy.pathLengthMeters(path),
            1e-9,
        )
        assertEquals(0.0, Geodesy.pathLengthMeters(listOf(brest)), 0.0)
        assertEquals(0.0, Geodesy.pathLengthMeters(emptyList()), 0.0)
    }

    @Test
    fun `rejects impossible coordinates`() {
        assertThrows(IllegalArgumentException::class.java) { LatLon(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LatLon(0.0, 181.0) }
    }
}
