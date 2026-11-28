package io.github.sentinel6systems.fieldmap.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The print tool's arrows (fieldmap-server print/src/shared.js, arrowHead): a line from the start
 * to the head, with two short strokes back from the head, 28° either side, as long as the map
 * says (HEAD_PX on the screen at its zoom), at most 40 % of the arrow. On a sphere: good to a few
 * parts in a thousand, as there.
 */
object Arrows {
    private const val R = 6_371_008.8

    /** Meters and initial true bearing (0-360) from a to b. */
    fun inverse(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val distance = 2 * R * asin(sqrt(h).coerceAtMost(1.0))
        val bearing = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
        return distance to (bearing + 360) % 360
    }

    /** The point `meters` from (lat, lon) at `bearing` (degrees true). */
    fun destination(lat: Double, lon: Double, bearing: Double, meters: Double): Pair<Double, Double> {
        val d = meters / R
        val t = Math.toRadians(bearing)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(t))
        val l2 = l1 + atan2(sin(t) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Pixels on the screen a head's strokes are long, at any zoom. */
    const val HEAD_PX = 18.0

    /** Meters a head's strokes are long at the head's latitude and the map's zoom: HEAD_PX on the screen. */
    fun headLength(lat: Double, zoom: Double) = HEAD_PX * Grid.metersPerPixel(lat, zoom)

    /** The two ends of the head's strokes (lat to lon), `length` meters (at most 40 % of the arrow); each goes to the arrow's head. */
    fun head(lat1: Double, lon1: Double, lat2: Double, lon2: Double, length: Double): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val (distance, _) = inverse(lat1, lon1, lat2, lon2)
        val size = minOf(length, distance * 0.4)
        // The bearing from the head back to the start.
        val back = inverse(lat2, lon2, lat1, lon1).second
        return destination(lat2, lon2, back - 28, size) to destination(lat2, lon2, back + 28, size)
    }

    /** How far a label along the shaft is turned (degrees clockwise from level), kept upright. */
    fun labelAngle(bearing: Double): Float {
        var a = bearing - 90
        while (a > 180) a -= 360
        while (a < -180) a += 360
        if (a > 90) a -= 180
        if (a < -90) a += 180
        return a.toFloat()
    }
}
