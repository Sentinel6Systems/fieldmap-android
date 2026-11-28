package io.github.sentinel6systems.fieldmap.geo

import io.github.sentinel6systems.fieldmap.data.Shape
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The shared shapes of the map in the browser, as it measures them (print/src/shapes.js): their
 * outline, area and one line of what they measure. Points are lat to lon.
 */
object Shapes {
    private const val R = 6_371_008.8

    val NAMES = mapOf("circle" to "Circle", "rect" to "Rectangle", "polygon" to "Polygon")

    fun name(s: Shape) = NAMES[s.kind] ?: "Shape"

    /** The outline, not closed: a circle traced as 96 points on the sphere, else its own points. */
    fun ring(s: Shape): List<Pair<Double, Double>> {
        val c = s.center ?: return s.ring
        return (0 until 96).map { Arrows.destination(c.first, c.second, it * 360.0 / 96, s.radius) }
    }

    /** Square meters inside an outline (not closed), on the sphere. */
    fun area(ring: List<Pair<Double, Double>>): Double {
        if (ring.size < 3) return 0.0
        val d2r = PI / 180
        var sum = 0.0
        for (i in ring.indices) {
            val (p1, l1) = ring[i]
            val (p2, l2) = ring[(i + 1) % ring.size]
            sum += (l2 - l1) * d2r * (2 + sin(p1 * d2r) + sin(p2 * d2r))
        }
        return abs(sum * R * R / 2)
    }

    /** Where its name goes: a circle's center, else the mean of its points. */
    fun center(s: Shape): Pair<Double, Double> = s.center ?: (s.ring.sumOf { it.first } / s.ring.size to s.ring.sumOf { it.second } / s.ring.size)

    /** "1,234 ft", "0.45 mi"; "820 m", "1.25 km". */
    fun lengthText(m: Double, imperial: Boolean): String = if (!imperial) {
        if (m < 1000) "${m.roundToLong()} m" else String.format(Locale.US, "%.2f km", m / 1000)
    } else {
        val mi = m / 1609.344
        if (mi < 0.2) String.format(Locale.US, "%,d ft", (m / 0.3048).roundToLong()) else String.format(Locale.US, "%.2f mi", mi)
    }

    /** "12.5 acres", "1.20 sq mi"; "4.3 ha", "2.10 km²". */
    fun areaText(m2: Double, imperial: Boolean): String = if (!imperial) {
        if (m2 < 1e6) String.format(Locale.US, "%.1f ha", m2 / 1e4) else String.format(Locale.US, "%.2f km²", m2 / 1e6)
    } else {
        val acres = m2 / 4046.8564224
        if (acres < 640) String.format(Locale.US, "%.1f acres", acres) else String.format(Locale.US, "%.2f sq mi", m2 / 2589988.110336)
    }

    /** One line: a circle's radius and area, a rectangle's width, height and area, a polygon's area. */
    fun summary(s: Shape, imperial: Boolean): String {
        if (s.center != null) return "radius ${lengthText(s.radius, imperial)} · ${areaText(PI * s.radius * s.radius, imperial)}"
        val area = areaText(area(s.ring), imperial)
        if (s.kind == "rect" && s.ring.size == 4) {
            val (sw, se) = s.ring[0] to s.ring[1]
            val w = Arrows.inverse(sw.first, sw.second, se.first, se.second).first
            val h = Arrows.inverse(sw.first, sw.second, s.ring[3].first, s.ring[3].second).first
            return "${lengthText(w, imperial)} × ${lengthText(h, imperial)} · $area"
        }
        return area
    }
}
