package app.donodavez.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapBaseStyleCleanerTest {
    @Test fun hidesThirdPartyPoiLabelsAndIcons() {
        assertTrue(MapBaseStyleCleaner.isUnrelatedPoiLayer("poi-level-1", "poi"))
        assertTrue(MapBaseStyleCleaner.isUnrelatedPoiLayer("poi_transit", "poi"))
        assertTrue(MapBaseStyleCleaner.isUnrelatedPoiLayer("shop-label", "poi"))
        assertTrue(MapBaseStyleCleaner.isUnrelatedPoiLayer("poi-label", ""))
        assertTrue(MapBaseStyleCleaner.isUnrelatedPoiLayer("POI-COMMERCIAL", ""))
    }

    @Test fun preservesRoadsCityLabelsBuildingsAndGameMarkers() {
        assertFalse(MapBaseStyleCleaner.isUnrelatedPoiLayer("highway-name", "transportation_name"))
        assertFalse(MapBaseStyleCleaner.isUnrelatedPoiLayer("place-label", "place"))
        assertFalse(MapBaseStyleCleaner.isUnrelatedPoiLayer("building-3d", "building"))
        assertFalse(MapBaseStyleCleaner.isUnrelatedPoiLayer("dv-icons", "poi"))
        assertFalse(MapBaseStyleCleaner.isUnrelatedPoiLayer("dv-me", ""))
        assertFalse(MapBaseStyleCleaner.isUnrelatedPoiLayer("dv-points", ""))
    }
}
