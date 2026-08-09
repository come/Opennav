package org.opennav.core.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderKeelClearanceTest {

    private val boat = BoatProfile(name = "test", draftMeters = 1.6, safetyMarginMeters = 1.0)

    @Test
    fun `matches the charted-depth form of the formula`() {
        // The plan writes it as: chartedDepth + tide - draft - margin.
        // The raster stores elevation = -chartedDepth.
        for (chartedDepth in listOf(0.0, 0.7, 2.4, 5.0, 18.3)) {
            for (tide in listOf(0.0, 1.2, 3.5, 7.8)) {
                val expected = chartedDepth + tide - boat.draftMeters - boat.safetyMarginMeters
                val actual = UnderKeelClearance.clearanceMeters(-chartedDepth, tide, boat)
                assertEquals("depth=$chartedDepth tide=$tide", expected, actual, 1e-9)
            }
        }
    }

    @Test
    fun `a drying rock has negative clearance at low water and floats at high water`() {
        // A rock drying 1.2 m above chart datum: elevation = +1.2.
        val rock = 1.2
        assertTrue(UnderKeelClearance.clearanceMeters(rock, 0.0, boat) < 0.0)
        assertTrue(UnderKeelClearance.clearanceMeters(rock, 3.0, boat) < 0.0)
        // Needs the rock covered by draft + margin + its own height before it is safe.
        assertEquals(
            DepthStatus.AGROUND,
            UnderKeelClearance.classify(rock, 3.7, boat),
        )
        assertEquals(
            DepthStatus.SAFE,
            UnderKeelClearance.classify(rock, 6.0, boat),
        )
    }

    @Test
    fun `offset folds tide draft and margin into one number`() {
        val tide = 4.3
        val offset = UnderKeelClearance.offsetMeters(tide, boat)
        assertEquals(tide - 2.6, offset, 1e-9)
        for (elevation in listOf(-30.0, -4.0, 0.0, 2.0)) {
            assertEquals(
                offset - elevation,
                UnderKeelClearance.clearanceMeters(elevation, tide, boat),
                1e-9,
            )
        }
    }

    @Test
    fun `no data yields NaN rather than a guess`() {
        val c = UnderKeelClearance.clearanceMeters(TerrainRgb.NO_DATA_ELEVATION_METERS, 3.0, boat)
        assertTrue("expected NaN, got $c", c.isNaN())
        assertEquals(
            DepthStatus.NO_DATA,
            UnderKeelClearance.classify(TerrainRgb.NO_DATA_ELEVATION_METERS, 3.0, boat),
        )
        // Including the filtered rim of a data hole.
        assertEquals(
            DepthStatus.NO_DATA,
            UnderKeelClearance.classify(8500.0, 3.0, boat),
        )
    }

    @Test
    fun `band edges land on the values the plan specifies`() {
        assertEquals(DepthStatus.AGROUND, DepthStatus.of(-0.001))
        assertEquals(DepthStatus.MARGIN_CONSUMED, DepthStatus.of(0.0))
        assertEquals(DepthStatus.MARGIN_CONSUMED, DepthStatus.of(0.499))
        assertEquals(DepthStatus.CAUTION, DepthStatus.of(0.5))
        assertEquals(DepthStatus.CAUTION, DepthStatus.of(1.999))
        assertEquals(DepthStatus.SAFE, DepthStatus.of(2.0))
        assertEquals(DepthStatus.NO_DATA, DepthStatus.of(Double.NaN))
    }

    @Test
    fun `a bigger margin can only ever shrink the safe area`() {
        val cautious = BoatProfile("cautious", draftMeters = 1.6, safetyMarginMeters = 2.0)
        val elevation = -3.5
        val tide = 1.0
        assertTrue(
            UnderKeelClearance.clearanceMeters(elevation, tide, cautious) <
                UnderKeelClearance.clearanceMeters(elevation, tide, boat),
        )
    }

    @Test
    fun `rejects nonsense boats and nonsense tides`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoatProfile("no draft", draftMeters = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoatProfile("negative margin", draftMeters = 1.0, safetyMarginMeters = -0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnderKeelClearance.offsetMeters(Double.NaN, boat)
        }
    }
}
