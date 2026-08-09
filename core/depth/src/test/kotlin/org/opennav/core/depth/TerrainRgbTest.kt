package org.opennav.core.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainRgbTest {

    @Test
    fun `decodes the reference values of the Mapbox encoding`() {
        assertEquals(-10000.0, TerrainRgb.decode(0, 0, 0), 1e-9)
        assertEquals(-10000.0 + 0.1, TerrainRgb.decode(0, 0, 1), 1e-9)
        assertEquals(-10000.0 + 25.6, TerrainRgb.decode(0, 1, 0), 1e-9)
        assertEquals(-10000.0 + 6553.6, TerrainRgb.decode(1, 0, 0), 1e-9)
        assertEquals(TerrainRgb.MAX_ENCODABLE_METERS, TerrainRgb.decode(255, 255, 255), 1e-6)
    }

    @Test
    fun `round trips values that sit exactly on the quantisation grid`() {
        for (tenths in -1200..2000) {
            val metres = tenths / 10.0
            val packed = TerrainRgb.encode(metres)
            val decoded = TerrainRgb.decode(
                TerrainRgb.red(packed),
                TerrainRgb.green(packed),
                TerrainRgb.blue(packed),
            )
            assertEquals("round trip at $metres m", metres, decoded, 1e-6)
        }
    }

    @Test
    fun `quantisation always raises the seabed, never lowers it`() {
        // A seabed at -3.04 m must not be encoded as -3.1 m: that would hand the mariner
        // 6 cm of water that is not there. -3.0 m is the honest, pessimistic choice.
        assertEquals(-3.0, decodeMetres(TerrainRgb.encode(-3.04)), 1e-6)
        assertEquals(-3.0, decodeMetres(TerrainRgb.encode(-3.09999)), 1e-6)
        assertEquals(-3.1, decodeMetres(TerrainRgb.encode(-3.1)), 1e-6)

        var samples = 0
        var raised = 0
        var x = -60.0
        while (x < 20.0) {
            val decoded = decodeMetres(TerrainRgb.encode(x))
            assertTrue(
                "encoding $x m produced a deeper seabed ($decoded m)",
                decoded >= x - 1e-9,
            )
            assertTrue(
                "encoding $x m lost more than one quantisation step",
                decoded - x <= TerrainRgb.INTERVAL_METERS + 1e-9,
            )
            if (decoded > x + 1e-9) raised++
            samples++
            x += 0.0137 // deliberately not a multiple of the 0.1 m step
        }
        assertTrue("expected most off-grid samples to be raised", raised > samples / 2)
    }

    @Test
    fun `the no-data sentinel round trips exactly and reads as no data`() {
        val packed = TerrainRgb.encode(TerrainRgb.NO_DATA_ELEVATION_METERS)
        assertEquals(
            TerrainRgb.NO_DATA_ELEVATION_METERS,
            decodeMetres(packed),
            1e-6,
        )
        assertEquals(2, TerrainRgb.red(packed))
        assertEquals(230, TerrainRgb.green(packed))
        assertEquals(48, TerrainRgb.blue(packed))
        assertTrue(TerrainRgb.isNoData(decodeMetres(packed)))
    }

    @Test
    fun `the sentinel sits above every elevation the survey can produce`() {
        assertTrue(TerrainRgb.NO_DATA_ELEVATION_METERS > TerrainRgb.NO_DATA_THRESHOLD_METERS)
        assertTrue(TerrainRgb.highestRealElevation() < TerrainRgb.NO_DATA_THRESHOLD_METERS)
        // Litto3D covers the coastal strip, so a few hundred metres of relief is the
        // most that can legitimately appear. Nothing near the sentinel.
        assertTrue(TerrainRgb.isNoData(9000.0))
        assertTrue(TerrainRgb.isNoData(Double.NaN))
        assertTrue(!TerrainRgb.isNoData(300.0))
        assertTrue(!TerrainRgb.isNoData(-120.0))
    }

    @Test
    fun `a blend between a real sample and the sentinel is still flagged unusable`() {
        // The GPU filters the DEM texture, so pixels on the rim of a data hole decode to
        // a weighted mix. Even a 90 % real / 10 % sentinel mix must stay well above any
        // navigable elevation.
        val real = -12.0
        val blended = 0.9 * real + 0.1 * TerrainRgb.NO_DATA_ELEVATION_METERS
        assertTrue("blend decoded to $blended m", blended > 300.0)
    }

    @Test
    fun `clamps rather than wrapping outside the encodable range`() {
        assertEquals(0, TerrainRgb.encode(-20000.0))
        assertEquals(0xFFFFFF, TerrainRgb.encode(1e9))
        assertNotEquals(0, TerrainRgb.encode(-9999.0))
    }

    private fun decodeMetres(packed: Int) = TerrainRgb.decode(
        TerrainRgb.red(packed),
        TerrainRgb.green(packed),
        TerrainRgb.blue(packed),
    )
}
