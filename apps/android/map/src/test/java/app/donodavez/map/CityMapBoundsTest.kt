package app.donodavez.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CityMapBoundsTest {
    @Test fun cityCenterAndLocalDemoAreWithinLimits() {
        assertTrue(CityMapBounds.contains(-23.5505, -46.6333))
        assertTrue(CityMapBounds.contains(-23.5560, -46.6900))
    }

    @Test fun otherCitiesAndInvalidCoordinatesAreOutside() {
        assertFalse(CityMapBounds.contains(-22.9068, -43.1729))
        assertFalse(CityMapBounds.contains(-23.0, -46.6333))
        assertFalse(CityMapBounds.contains(Double.NaN, -46.6333))
    }

    @Test fun minimumZoomPreventsStateWideMapRequests() {
        assertTrue(CityMapBounds.MIN_ZOOM > MapRepository.CLUSTER_ZOOM)
        assertTrue(MapRepository.PAGE_SIZE * MapRepository.MAX_PAGES <= 500)
    }
}
