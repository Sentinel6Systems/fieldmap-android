package io.github.sentinel6systems.fieldmap.geo

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The MGRS grid on the map (Menu > Settings > MGRS Grid Lines), as on the map in the browser
 * (print/src/grid.js): the lines for what is in view. The spacing follows the zoom: 100 m, 1 km,
 * 10 km or 100 km lines, and the grid zone lines (6 degrees by 8) when zoomed out further. Each
 * line's digits sit where it meets the top or left edge of the view (kind "edge", upright), and
 * each 100 km square in view has its full name ("13S DC") in the middle of the part in view.
 * Points are lat to lon.
 */
object Grid {
    /** A line ("major" on a 100 km line, "minor", "zone") or a label ("edge" with its `anchor`, top or left; "name"). */
    class Feature(val kind: String, val points: List<Pair<Double, Double>>, val label: String = "", val anchor: String = "") {
        val isLine get() = kind == "major" || kind == "minor" || kind == "zone"
    }

    private val STEPS = listOf(100, 1000, 10000, 100000)
    private const val MIN_PX = 70           // closest two lines may be on screen
    private const val SEGS = 24             // points along each line, so that it bends with the projection

    /** Meters per screen pixel at a latitude and MapLibre zoom (512 px tiles). */
    fun metersPerPixel(lat: Double, zoom: Double): Double = 40075016.686 * cos(Math.toRadians(lat)) / (512 * 2.0.pow(zoom))

    /** The line spacing (meters) for a zoom, or 0 for the grid zone lines only. */
    fun step(lat: Double, zoom: Double): Int {
        val mpp = metersPerPixel(lat, zoom)
        return STEPS.firstOrNull { it / mpp >= MIN_PX } ?: 0
    }

    /** The digits a line is labeled with: its place within the 100 km square, one digit for 10 km lines (4), two for 1 km (43), three for 100 m (432). */
    fun lineLabel(v: Double, step: Int): String {
        if (step >= 100000) return ""          // the square letters say it
        val inSquare = ((v % 100000) + 100000) % 100000
        val digits = log10(100000.0 / step).roundToInt()
        return floor(inSquare / step).toLong().toString().padStart(digits, '0')
    }

    /** Where a run of points crosses lat (i = 0) or lon (i = 1) = v, or null. */
    private fun crossing(pts: List<Pair<Double, Double>>, i: Int, v: Double): Pair<Double, Double>? {
        fun c(p: Pair<Double, Double>) = if (i == 0) p.first else p.second
        for (k in 1 until pts.size) {
            val p = pts[k - 1]
            val q = pts[k]
            if ((c(p) - v) * (c(q) - v) <= 0 && c(p) != c(q)) {
                val t = (v - c(p)) / (c(q) - c(p))
                return (p.first + t * (q.first - p.first)) to (p.second + t * (q.second - p.second))
            }
        }
        return null
    }

    /** Splits a run of points into the parts inside west..east, cut at the edges. */
    private fun clipLon(pts: List<Pair<Double, Double>>, west: Double, east: Double): List<List<Pair<Double, Double>>> {
        val out = ArrayList<List<Pair<Double, Double>>>()
        var run = ArrayList<Pair<Double, Double>>()
        fun inside(p: Pair<Double, Double>) = p.second in west..east
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts.getOrNull(i - 1)
            if (q != null && inside(p) != inside(q)) {
                val edge = if (inside(p)) (if (q.second < west) west else east) else (if (p.second < west) west else east)
                val t = (edge - q.second) / (p.second - q.second)
                run += (q.first + t * (p.first - q.first)) to edge
                if (!inside(p)) { if (run.size > 1) out += run; run = ArrayList(); continue }
            }
            if (inside(p)) run += p
        }
        if (run.size > 1) out += run
        return out
    }

    /** The grid zone lines (and their names) for a view; when the 100 km lines would crowd. */
    private fun zoneLines(west: Double, south: Double, east: Double, north: Double): List<Feature> {
        val f = ArrayList<Feature>()
        val s = max(-80.0, south)
        val n = min(84.0, north)
        if (s >= n) return f
        var lon = ceil((west + 180) / 6) * 6 - 180
        while (lon <= east) { f += Feature("zone", listOf(s to lon, n to lon)); lon += 6 }
        var lat = ceil((s + 80) / 8) * 8 - 80
        while (lat <= n) { f += Feature("zone", listOf(lat to max(west, -180.0), lat to min(east, 180.0))); lat += 8 }
        // The zone names, in the middle of each cell that is in view.
        lon = floor((west + 180) / 6) * 6 - 180
        while (lon < east) {
            lat = floor((s + 80) / 8) * 8 - 80
            while (lat < n) {
                val cx = lon + 3
                val cy = min(lat + 4, 82.0)
                if (cx >= -180 && cx <= 180) {
                    val zone = (floor((cx + 180) / 6).toInt() % 60 + 60) % 60 + 1
                    f += Feature("name", listOf(cy to cx), "$zone${Utm.bandFor(cy)}")
                }
                lat += 8
            }
            lon += 6
        }
        return f
    }

    /**
     * The grid for the map view (degrees) at a zoom. Each UTM zone in view gets its own lines,
     * cut at the zone's edges; the spacing is worked out at the middle of the view.
     */
    fun features(west: Double, south: Double, east: Double, north: Double, zoom: Double): List<Feature> {
        val s = max(-80.0, south)
        val n = min(84.0, north)
        val step = step((s + n) / 2, zoom)
        if (step == 0 || s >= n) return zoneLines(west, south, east, north)
        val out = ArrayList<Feature>()
        val firstZone = floor((max(west, -180.0) + 180) / 6).toInt() + 1
        val lastZone = min(60, floor((min(east, 179.999) + 180) / 6).toInt() + 1)
        for (zone in firstZone..lastZone) {
            val zw = max(west, (zone - 1) * 6.0 - 180)
            val ze = min(east, zone * 6.0 - 180)
            if (zw >= ze) continue
            // The UTM extent of this zone's part of the view (northings below the equator negative,
            // so that one zone runs on across it).
            val edge = ArrayList<Pair<Double, Double>>()
            for (i in 0..8) {
                val lon = zw + (ze - zw) * i / 8
                val lat = s + (n - s) * i / 8
                edge += listOf(s to lon, n to lon, lat to zw, lat to ze)
            }
            val us = edge.map { (lat, lon) -> Utm.toUtm(lat, lon, zone).let { it.easting to if (lat < 0) it.northing - 10000000 else it.northing } }
            val minE = us.minOf { it.first }
            val maxE = us.maxOf { it.first }
            val minN = us.minOf { it.second }
            val maxN = us.maxOf { it.second }
            fun at(e: Double, nn: Double) = Utm.fromUtm(zone, e, nn)
            fun kind(v: Double) = if (v % 100000 == 0.0) "major" else "minor"
            var k = ceil(minE / step).toLong()
            while (k * step <= maxE) {
                val e = (k * step).toDouble()
                val pts = (0..SEGS).map { at(e, minN + (maxN - minN) * it / SEGS) }
                for (part in clipLon(pts, zw, ze)) out += Feature(kind(e), part)
                val top = crossing(pts, 0, n)
                val label = lineLabel(e, step)
                if (label.isNotEmpty() && top != null && top.second >= zw && top.second <= ze) out += Feature("edge", listOf(top), label, "top")
                k++
            }
            k = ceil(minN / step).toLong()
            while (k * step <= maxN) {
                val nn = (k * step).toDouble()
                val pts = (0..SEGS).map { at(minE + (maxE - minE) * it / SEGS, nn) }
                for (part in clipLon(pts, zw, ze)) out += Feature(kind(nn), part)
                val left = crossing(pts, 1, zw)
                val label = lineLabel(nn, step)
                if (label.isNotEmpty() && left != null && left.first >= s && left.first <= n) out += Feature("edge", listOf(left), label, "left")
                k++
            }
            // The 100 km squares' names, each in the middle of the part of the square that is in view.
            var e = floor(minE / 100000) * 100000
            while (e < maxE) {
                var nn = floor(minN / 100000) * 100000
                while (nn < maxN) {
                    if (e >= 100000 && e < 900000) {
                        val c = at((max(e, minE) + min(e + 100000, maxE)) / 2, (max(nn, minN) + min(nn + 100000, maxN)) / 2)
                        if (c.second >= zw && c.second <= ze && c.first >= s && c.first <= n) {
                            out += Feature("name", listOf(c), "$zone${Utm.bandFor(c.first)} ${Utm.squareId(zone, e, if (nn < 0) nn + 10000000 else nn)}")
                        }
                    }
                    nn += 100000
                }
                e += 100000
            }
        }
        return out
    }
}
