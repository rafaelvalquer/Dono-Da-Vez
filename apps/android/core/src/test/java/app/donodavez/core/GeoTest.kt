package app.donodavez.core

import app.donodavez.core.location.Geo
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test fun distanceOfOneThousandthDegreeLatitude() =
        assertEquals(111.2, Geo.distanceM(-23.0, -46.0, -23.001, -46.0), 0.5)

    @Test fun destinationRoundTrip() {
        val (lat, lon) = Geo.destination(-23.56, -46.69, 80.0, 37.0)
        assertEquals(80.0, Geo.distanceM(-23.56, -46.69, lat, lon), 0.01)
    }

    @Test fun circleRingIsClosedAndOnRadius() {
        val ring = Geo.circleRing(-23.56, -46.69, 80.0, steps = 32)
        assertEquals(33, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.forEach { (lon, lat) -> assertEquals(80.0, Geo.distanceM(-23.56, -46.69, lat, lon), 0.01) }
    }
}
