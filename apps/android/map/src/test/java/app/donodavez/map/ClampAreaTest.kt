package app.donodavez.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class ClampAreaTest {
    private fun areaKm2(b: DoubleArray, lat: Double) =
        (b[2] - b[0]) * 111.32 * cos(Math.toRadians(lat)) * (b[3] - b[1]) * 110.57

    @Test fun smallBoxIsUntouched() {
        val box = doubleArrayOf(-46.70, -23.57, -46.69, -23.56)
        assertArrayEquals(box, MapViewModel.clampArea(box, -23.565, -46.695, 5.0), 0.0)
    }

    @Test fun largeBoxShrinksAroundCenterBelowServerLimit() {
        val box = doubleArrayOf(-46.80, -23.65, -46.60, -23.45)
        val out = MapViewModel.clampArea(box, -23.55, -46.70, 5.0)
        assertTrue(areaKm2(out, -23.55) <= 5.0001)
        assertEquals(-46.70, (out[0] + out[2]) / 2, 1e-9)
        assertEquals(-23.55, (out[1] + out[3]) / 2, 1e-9)
    }
}
