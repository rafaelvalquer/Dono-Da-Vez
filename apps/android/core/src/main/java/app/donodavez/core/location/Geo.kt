package app.donodavez.core.location

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Cálculos geodésicos de exibição (estimativas; a distância oficial é do servidor/PostGIS). */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Ponto a [distanceM] metros na direção [bearingDeg] (graus, 0 = norte). */
    fun destination(lat: Double, lon: Double, distanceM: Double, bearingDeg: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Anel fechado [lon, lat] aproximando um círculo de [radiusM] metros. */
    fun circleRing(lat: Double, lon: Double, radiusM: Double, steps: Int = 64): List<Pair<Double, Double>> {
        val ring = (0 until steps).map { i ->
            val (la, lo) = destination(lat, lon, radiusM, i * 360.0 / steps)
            lo to la
        }
        return ring + ring.first()
    }

    /** Caixa [minLon, minLat, maxLon, maxLat] com margem relativa. */
    fun expand(bbox: DoubleArray, ratio: Double): DoubleArray {
        val dx = (bbox[2] - bbox[0]) * ratio
        val dy = (bbox[3] - bbox[1]) * ratio
        return doubleArrayOf(
            (bbox[0] - dx).coerceAtLeast(-180.0), (bbox[1] - dy).coerceAtLeast(-90.0),
            (bbox[2] + dx).coerceAtMost(180.0), (bbox[3] + dy).coerceAtMost(90.0),
        )
    }

    fun contains(outer: DoubleArray, inner: DoubleArray): Boolean =
        inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3]
}
