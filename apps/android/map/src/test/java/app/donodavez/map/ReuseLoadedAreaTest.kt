package app.donodavez.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReuseLoadedAreaTest {
    private val loaded = doubleArrayOf(-46.70, -23.60, -46.60, -23.50)
    private val closeVisible = doubleArrayOf(-46.66, -23.56, -46.64, -23.54)
    private val closeRequest = doubleArrayOf(-46.665, -23.565, -46.635, -23.535)

    @Test fun completeLoadIsReusedWhenZoomingIn() {
        assertTrue(MapViewModel.canReuse(loaded, closeVisible, closeRequest, truncated = false))
    }

    @Test fun truncatedLoadIsReplacedWhenZoomingIn() {
        assertFalse(MapViewModel.canReuse(loaded, closeVisible, closeRequest, truncated = true))
    }

    @Test fun truncatedLoadIsKeptWhilePanningAtTheSameZoom() {
        val visible = doubleArrayOf(-46.68, -23.58, -46.62, -23.52)
        assertTrue(MapViewModel.canReuse(loaded, visible, loaded, truncated = true))
    }

    @Test fun leavingTheLoadedAreaAlwaysReloads() {
        val outside = doubleArrayOf(-46.72, -23.56, -46.68, -23.54)
        assertFalse(MapViewModel.canReuse(loaded, outside, outside, truncated = false))
    }

    @Test fun toleranceAbsorbsGpsJitterWithoutMargin() {
        val jitter = doubleArrayOf(-46.697, -23.60, -46.597, -23.50)
        assertFalse(MapViewModel.canReuse(loaded, jitter, jitter, truncated = false))
        assertTrue(MapViewModel.canReuse(loaded, jitter, jitter, truncated = false, tolerance = 0.05))
    }

    @Test fun realMovementReloadsEvenWithTolerance() {
        val moved = doubleArrayOf(-46.692, -23.60, -46.592, -23.50)
        assertFalse(MapViewModel.canReuse(loaded, moved, moved, truncated = false, tolerance = 0.05))
    }

    @Test fun marginReturnsOnlyWhenTheLargerAreaFitsWithRoomToSpare() {
        assertTrue(MapViewModel.fitsWithMargin(78))
        assertFalse(MapViewModel.fitsWithMargin(222))
        assertFalse(MapViewModel.fitsWithMargin(349))
    }
}
