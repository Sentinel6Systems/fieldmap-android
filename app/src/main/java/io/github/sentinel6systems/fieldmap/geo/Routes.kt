package io.github.sentinel6systems.fieldmap.geo

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A route's legs, length and time, and making a long line fit a route's points. Points are lat to lon. */
object Routes {
    /** One leg: from point `from` (0-based) to the next one, its meters and initial true bearing. */
    class Leg(val from: Int, val meters: Double, val bearing: Double)

    /** The speeds a route's time can be worked out at, in mph: hiking (the default), running, cycling, driving. */
    val SPEEDS = listOf(2, 4, 10, 15, 25, 45, 60)
    const val DEFAULT_MPH = 2

    fun legs(points: List<Pair<Double, Double>>): List<Leg> = points.zipWithNext().mapIndexed { i, (a, b) ->
        val (meters, bearing) = Arrows.inverse(a.first, a.second, b.first, b.second)
        Leg(i, meters, bearing)
    }

    fun length(points: List<Pair<Double, Double>>): Double = legs(points).sumOf { it.meters }

    /** Minutes for `meters` at `mph`, rounded up to the next minute. */
    fun minutes(meters: Double, mph: Int): Int = kotlin.math.ceil(meters / (mph * 1609.344 / 60) - 1e-9).toInt()

    /** "45 min", "2 h 05 min". */
    fun timeText(minutes: Int): String = if (minutes < 60) "$minutes min" else String.format(java.util.Locale.US, "%d h %02d min", minutes / 60, minutes % 60)

    /** The middle of a leg, near enough for a label or a handle (lat to lon). */
    fun middle(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
        val (meters, bearing) = Arrows.inverse(a.first, a.second, b.first, b.second)
        return Arrows.destination(a.first, a.second, bearing, meters / 2)
    }

    /**
     * At most `max` (at least 2) of the points: the first, the last, and in between those that
     * shape the line most (Douglas-Peucker, one point at a time, the farthest off first).
     */
    fun simplify(points: List<Pair<Double, Double>>, max: Int): List<Pair<Double, Double>> {
        if (points.size <= max) return points
        val keep = sortedSetOf(0, points.lastIndex)
        while (keep.size < max) {
            var best = -1
            var far = -1.0
            for ((a, b) in keep.zipWithNext()) for (i in a + 1 until b) {
                val d = offLine(points[i], points[a], points[b])
                if (d > far) { far = d; best = i }
            }
            if (best < 0) break
            keep += best
        }
        return keep.map { points[it] }
    }

    /** Meters from p to the segment a-b, on a flat map around a (good enough to rank points). */
    private fun offLine(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = cos(Math.toRadians(a.first)) * 111_195.0
        val (px, py) = (p.second - a.second) * k to (p.first - a.first) * 111_195.0
        val (bx, by) = (b.second - a.second) * k to (b.first - a.first) * 111_195.0
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    /** A bearing as the app shows it: "042°T". */
    fun bearingText(bearing: Double): String = String.format(java.util.Locale.US, "%03d°T", bearing.roundToInt() % 360)
}
