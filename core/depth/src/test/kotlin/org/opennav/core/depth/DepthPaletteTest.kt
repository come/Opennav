package org.opennav.core.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthPaletteTest {

    private val boat = BoatProfile(name = "test", draftMeters = 1.6, safetyMarginMeters = 1.0)

    /** Evaluates the ramp the way the shader does, but from a clearance figure. */
    private fun colorForClearance(clearance: Double, tide: Double = 3.0): Int {
        val stops = DepthPalette.stops(tide, boat)
        val offset = UnderKeelClearance.offsetMeters(tide, boat)
        return DepthPalette.colorAt(offset - clearance, stops)
    }

    @Test
    fun `stops stay strictly increasing across the whole plausible envelope`() {
        // Saint-Malo runs to roughly 13 m of range; deep-draft boats and paranoid
        // margins push the offset around further still.
        for (tideTenths in -20..140) {
            for (draft in listOf(0.3, 1.6, 3.0, 6.0)) {
                for (margin in listOf(0.0, 0.5, 1.0, 3.0)) {
                    val stops = DepthPalette.stops(
                        tideTenths / 10.0,
                        BoatProfile("b", draft, margin),
                    )
                    stops.zipWithNext().forEach { (a, b) ->
                        assertTrue(
                            "non-monotonic ramp at tide=${tideTenths / 10.0} " +
                                "draft=$draft margin=$margin: $a then $b",
                            b.elevationMeters > a.elevationMeters,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `each band renders in the colour the plan asks for`() {
        assertEquals(DepthPalette.AGROUND_ARGB, colorForClearance(-5.0))
        assertEquals(DepthPalette.AGROUND_ARGB, colorForClearance(-0.1))
        assertEquals(DepthPalette.MARGIN_CONSUMED_ARGB, colorForClearance(0.0))
        assertEquals(DepthPalette.MARGIN_CONSUMED_ARGB, colorForClearance(0.25))
        assertEquals(DepthPalette.CAUTION_ARGB, colorForClearance(0.5))
        assertEquals(DepthPalette.CAUTION_ARGB, colorForClearance(1.9))
        assertEquals(DepthPalette.SAFE_SHALLOW_ARGB, colorForClearance(2.0))
        assertEquals(DepthPalette.SAFE_DEEP_ARGB, colorForClearance(DepthPalette.DEFAULT_DEEP_RANGE_METERS))
        assertEquals(DepthPalette.SAFE_DEEP_ARGB, colorForClearance(500.0))
    }

    @Test
    fun `band edges are hard, not gradients`() {
        // A couple of millimetres either side of an edge must already be fully saturated
        // in the neighbouring band -- no visible ramp between red and orange.
        val past = 2 * DepthPalette.EDGE_EPSILON_METERS
        assertEquals(DepthPalette.MARGIN_CONSUMED_ARGB, colorForClearance(0.0))
        assertEquals(DepthPalette.AGROUND_ARGB, colorForClearance(-past))
        assertEquals(DepthPalette.CAUTION_ARGB, colorForClearance(0.5))
        assertEquals(DepthPalette.MARGIN_CONSUMED_ARGB, colorForClearance(0.5 - past))
        assertEquals(DepthPalette.SAFE_SHALLOW_ARGB, colorForClearance(2.0))
        assertEquals(DepthPalette.CAUTION_ARGB, colorForClearance(2.0 - past))
    }

    @Test
    fun `safe water is a gradient, not a flat fill`() {
        val mid = colorForClearance(10.0)
        assertNotEquals(DepthPalette.SAFE_SHALLOW_ARGB, mid)
        assertNotEquals(DepthPalette.SAFE_DEEP_ARGB, mid)
        // Monotonically darkening with clearance.
        var previousLuma = luma(colorForClearance(2.0))
        var clearance = 3.0
        while (clearance <= DepthPalette.DEFAULT_DEEP_RANGE_METERS) {
            val l = luma(colorForClearance(clearance))
            assertTrue("blue got lighter at $clearance m", l <= previousLuma + 1e-6)
            previousLuma = l
            clearance += 1.0
        }
    }

    @Test
    fun `no data is violet and is never mistaken for deep water`() {
        val stops = DepthPalette.stops(3.0, boat)
        assertEquals(
            DepthPalette.NO_DATA_ARGB,
            DepthPalette.colorAt(TerrainRgb.NO_DATA_ELEVATION_METERS, stops),
        )
        assertNotEquals(
            DepthPalette.SAFE_DEEP_ARGB,
            DepthPalette.colorAt(TerrainRgb.NO_DATA_ELEVATION_METERS, stops),
        )
    }

    @Test
    fun `every blend between real ground and the sentinel renders as danger`() {
        // This is the property that lets the pipeline leave data holes unfilled: no
        // matter how the GPU mixes a surveyed sample with the sentinel, the result is
        // red or violet, never a shade of navigable blue.
        val stops = DepthPalette.stops(3.0, boat)
        for (realElevation in listOf(-45.0, -12.0, -3.0, 0.0, 2.0)) {
            for (weightPercent in 1..99) {
                val w = weightPercent / 100.0
                val blended = (1 - w) * realElevation + w * TerrainRgb.NO_DATA_ELEVATION_METERS
                val color = DepthPalette.colorAt(blended, stops)
                assertTrue(
                    "blend of $realElevation m with the sentinel at $weightPercent % " +
                        "rendered as ${color.toUInt().toString(16)}",
                    color == DepthPalette.AGROUND_ARGB || color == DepthPalette.NO_DATA_ARGB,
                )
                assertTrue(
                    "blend at $weightPercent % was classified navigable",
                    UnderKeelClearance.classify(blended, 3.0, boat) != DepthStatus.SAFE,
                )
            }
        }
    }

    @Test
    fun `sliding the time cursor only translates the ramp`() {
        // The whole 60 fps story rests on this: changing t moves the stops, it does not
        // change their shape, so nothing has to be re-decoded.
        val a = DepthPalette.stops(1.0, boat)
        val b = DepthPalette.stops(4.5, boat)
        assertEquals(a.size, b.size)
        a.zip(b).forEachIndexed { i, (sa, sb) ->
            assertEquals("colour changed at stop $i", sa.argb, sb.argb)
        }
        // The band stops shift by exactly the tide delta; the sentinel stops are pinned.
        val shifted = a.zip(b).count { (sa, sb) ->
            kotlin.math.abs((sb.elevationMeters - sa.elevationMeters) - 3.5) < 1e-9
        }
        assertEquals(7, shifted)
    }

    @Test
    fun `the ramp is classified consistently with the clearance calculation`() {
        val tide = 2.4
        val stops = DepthPalette.stops(tide, boat)
        val offset = UnderKeelClearance.offsetMeters(tide, boat)
        var elevation = -40.0
        while (elevation < 12.0) {
            val clearance = offset - elevation
            // Skip the millimetre-wide transitions that make the edges hard; they are
            // covered by `band edges are hard, not gradients`.
            val onAnEdge = listOf(0.0, 0.5, DepthStatus.CAUTION_CEILING_METERS).any {
                kotlin.math.abs(clearance - it) < 2 * DepthPalette.EDGE_EPSILON_METERS
            }
            if (onAnEdge) {
                elevation += 0.017
                continue
            }
            val expected = when (UnderKeelClearance.classify(elevation, tide, boat)) {
                DepthStatus.AGROUND -> DepthPalette.AGROUND_ARGB
                DepthStatus.MARGIN_CONSUMED -> DepthPalette.MARGIN_CONSUMED_ARGB
                DepthStatus.CAUTION -> DepthPalette.CAUTION_ARGB
                DepthStatus.NO_DATA -> DepthPalette.NO_DATA_ARGB
                DepthStatus.SAFE -> null // gradient, checked separately
            }
            if (expected != null) {
                assertEquals(
                    "elevation $elevation m",
                    expected,
                    DepthPalette.colorAt(elevation, stops),
                )
            }
            elevation += 0.017
        }
    }

    @Test
    fun `serialises to a color-relief expression the renderer can parse`() {
        val json = DepthPalette.toColorReliefColorJson(DepthPalette.stops(3.2, boat))
        assertTrue(json.startsWith("[\"interpolate\",[\"linear\"],[\"elevation\"],"))
        assertTrue(json.endsWith("]"))
        // One colour literal per stop.
        val stops = DepthPalette.stops(3.2, boat)
        assertEquals(stops.size, Regex("rgba\\(").findAll(json).count())
        assertTrue("expected the aground colour", json.contains("rgba(215,25,28,1.000)"))
        assertTrue("expected the no-data colour", json.contains("rgba(142,95,168,1.000)"))
        assertTrue("expected the sentinel stop", json.contains("9000.0000"))
    }

    @Test
    fun `serialisation keeps the stops ordered at every tide`() {
        // Rounding for the wire format must not merge the millimetre-wide band edges.
        for (tideTenths in -20..140) {
            val json = DepthPalette.toColorReliefColorJson(
                DepthPalette.stops(tideTenths / 10.0, boat),
            )
            val numbers = Regex("(?<![.\\d])(-?\\d+\\.\\d{4})(?=,\")")
                .findAll(json)
                .map { it.groupValues[1].toDouble() }
                .toList()
            assertEquals(10, numbers.size)
            numbers.zipWithNext().forEach { (a, b) ->
                assertTrue("ramp collapsed at tide ${tideTenths / 10.0}: $a then $b", b > a)
            }
        }
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
