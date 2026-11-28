package io.github.sentinel6systems.fieldmap.geo

import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.data.Shape
import java.security.MessageDigest
import kotlin.math.abs
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

    /** The point `fraction` (0-1) of the way along a line (lat to lon), by length. */
    fun along(line: List<Pair<Double, Double>>, fraction: Double): Pair<Double, Double> {
        if (line.size < 2) return line.first()
        val legs = legs(line)
        var left = legs.sumOf { it.meters } * fraction
        for (leg in legs) {
            if (left <= leg.meters) return Arrows.destination(line[leg.from].first, line[leg.from].second, leg.bearing, left)
            left -= leg.meters
        }
        return line.last()
    }

    /** The way a line (lat to lon) goes `fraction` (0-1) of the way along it: the bearing of its piece there. */
    fun bearingAt(line: List<Pair<Double, Double>>, fraction: Double): Double {
        val legs = legs(line).filter { it.meters > 0 }
        var left = legs.sumOf { it.meters } * fraction
        for (leg in legs) {
            if (left <= leg.meters) return leg.bearing
            left -= leg.meters
        }
        return legs.lastOrNull()?.bearing ?: 0.0
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

    /**
     * A leg found along roads or trails from p to q. BRouter's line goes from the road or trail
     * nearest p to the one nearest q; the points stay where they were put. Where the line starts or
     * ends more than GAP_METERS from its point, a straight dashed piece joins them (head / tail);
     * more than OFF_ROAD_METERS: the leg "ends off road". All as on the map in the browser.
     */
    class RoadLeg(val line: List<Pair<Double, Double>>, val head: Boolean, val tail: Boolean, val offRoad: Boolean)

    const val GAP_METERS = 3.0
    const val OFF_ROAD_METERS = 100.0
    /** An end farther than this from its point: no way (the leg is drawn straight). Also how far roads are looked for. */
    const val MAX_OFF_ROAD_METERS = 15_000.0

    /** The leg from p to q around `road` (the line along roads, at least 2 points); null when an end is too far from its point. */
    fun roadLeg(p: Pair<Double, Double>, q: Pair<Double, Double>, road: List<Pair<Double, Double>>): RoadLeg? {
        fun gap(a: Pair<Double, Double>, b: Pair<Double, Double>) = Arrows.inverse(a.first, a.second, b.first, b.second).first
        val start = gap(p, road.first())
        val end = gap(q, road.last())
        if (start > MAX_OFF_ROAD_METERS || end > MAX_OFF_ROAD_METERS) return null
        val head = start > GAP_METERS
        val tail = end > GAP_METERS
        return RoadLeg((if (head) listOf(p) else emptyList()) + road + (if (tail) listOf(q) else emptyList()), head, tail,
            start > OFF_ROAD_METERS || end > OFF_ROAD_METERS)
    }

    /**
     * The places on these lines nearest p, nearest first: at most `count`, each at least `spread`
     * meters from the others, none farther than `most` meters. For a leg with no way: the roads
     * near its end (then its start) to route to instead.
     */
    fun nearestRoadPoints(p: Pair<Double, Double>, lines: List<List<Pair<Double, Double>>>, count: Int = 3, spread: Double = 500.0,
                          most: Double = MAX_OFF_ROAD_METERS): List<Pair<Double, Double>> {
        val kx = cos(Math.toRadians(p.first)) * 111_320
        val ky = 110_540.0
        class Found(val x: Double, val y: Double) { val meters = hypot(x, y) }
        val found = ArrayList<Found>()
        for (line in lines) for (i in 1 until line.size) {
            val x1 = (line[i - 1].second - p.second) * kx
            val y1 = (line[i - 1].first - p.first) * ky
            val dx = (line[i].second - p.second) * kx - x1
            val dy = (line[i].first - p.first) * ky - y1
            val len = dx * dx + dy * dy
            val t = if (len > 0) (-(x1 * dx + y1 * dy) / len).coerceIn(0.0, 1.0) else 0.0
            val f = Found(x1 + t * dx, y1 + t * dy)
            if (f.meters <= most) found += f
        }
        val out = ArrayList<Found>()
        for (f in found.sortedBy { it.meters }) {
            if (out.size >= count) break
            if (out.all { hypot(it.x - f.x, it.y - f.y) >= spread }) out += f
        }
        return out.map { (p.first + it.y / ky) to (p.second + it.x / kx) }
    }

    /* ---------- Route Options: the same as on the map in the browser (print/src/shared.js, routing.js) ---------- */
    // A shared route takes the same roads on every device: each one asks its router the same.

    /** A Route Option: its key (in files and over the mesh), its name, and BRouter's profile settings for it. */
    class Option(val key: String, val title: String, val settings: Map<String, String>)

    /** The Route Options of each Route Type, in their fixed order. */
    val OPTIONS: Map<RouteType, List<Option>> = mapOf(
        RouteType.DRIVING to listOf(
            Option("avoid-highways", "Avoid Highways", mapOf("avoid_motorways" to "1")),
            Option("dirt-tracks", "Allow Dirt Tracks", mapOf("use_offroad" to "1")),
            Option("avoid-towns", "Avoid Big Towns", mapOf("consider_town" to "1")),
            Option("fuel-saver", "Fuel Saver", mapOf("vmax" to "70")),
        ),
        RouteType.WALKING to listOf(
            Option("avoid-steps", "Avoid Steps", mapOf("allow_steps" to "0")),
            Option("avoid-wet", "Avoid Wet Ground", mapOf("iswet" to "1")),
            // The shortest way on any road, track or trail open on foot (BRouter's shortest_way): busy
            // roads too. It leaves out the profile's preferences, so Trail Difficulty, Avoid Steps and
            // Avoid Wet Ground do not apply with it.
            Option("any-road", "Use Any Road or Trail", mapOf("shortest_way" to "1")),
        ),
    )

    /** Trail Difficulty of a Walking route (the hardest trails it may use): Moderate (no key) is the profile's own. */
    val DIFFICULTY = listOf(
        Option("easy", "Easy", mapOf("SAC_scale_limit" to "1")),
        Option("", "Moderate", emptyMap()),
        Option("hard", "Hard", mapOf("SAC_scale_limit" to "5")),
    )

    /** Way: the router's best way for each leg, or its second, third or fourth (`alternative` 0 to 3). */
    val WAYS = listOf("Best Way", "Second Way", "Third Way", "Fourth Way")

    /** A route's options as kept: only the known keys of its Route Type, in their fixed order; Easy, not Hard, when both. */
    fun cleanOptions(type: RouteType, options: Collection<String>): List<String> {
        val known = OPTIONS[type].orEmpty().map { it.key } + if (type == RouteType.WALKING) listOf("easy", "hard") else emptyList()
        val out = known.filter { it in options }
        return if ("easy" in out && "hard" in out) out - "hard" else out
    }

    /** Points of the avoid areas one leg is asked with, at most: a circle counts 1, an area or a line its points. */
    const val MAX_AVOID = 400
    /** Meters around a marker of the avoided group. */
    const val MARKER_AVOID_METERS = 100.0

    /**
     * What BRouter is asked for a route's legs: its profile, and the rest of the request as the
     * server's BRouter gets it (geo/Router.kt): the Route Options as "profile:<name>" settings,
     * "alternativeidx" for the Way, and the items of the avoided group as "nogos" (circles:
     * lon,lat,meters), "polygons" (areas) and "polylines" (arrows, which cut the roads they cross),
     * "|" between them. `tooMuch`: the group has more than MAX_AVOID points; the items that do not
     * fit are left out.
     */
    class Ask(val profile: String, val params: Map<String, String>, val tooMuch: Boolean) {
        /** What a leg's line is kept under: the profile, and a digest of the rest (which can be long). */
        val key: String = if (params.isEmpty()) profile else profile + "&" + MessageDigest.getInstance("SHA-256")
            .digest(params.entries.joinToString("&") { (k, v) -> "$k=$v" }.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * What the router is asked for a route's legs (print/src/routing.js routeAsk), or null for a
     * Straight Line route. `items`: the markers, arrows and routes; `shapes`: the shared shapes.
     * The avoided group's markers (MARKER_AVOID_METERS around them) and circles are circles, its
     * rectangles and polygons areas, its arrows lines; its routes and tracks are not obstacles.
     * Taken in that order until MAX_AVOID points; coordinates to 5 decimals.
     */
    fun ask(r: PlannedRoute, items: List<Marker>, shapes: List<Shape>): Ask? {
        val profile = r.type.profile ?: return null
        val keys = cleanOptions(r.type, r.options)
        val params = LinkedHashMap<String, String>()
        for (o in OPTIONS[r.type].orEmpty() + DIFFICULTY) if (o.key.isNotEmpty() && o.key in keys) for ((k, v) in o.settings) params["profile:$k"] = v
        if (r.alternative in 1..3) params["alternativeidx"] = r.alternative.toString()
        var tooMuch = false
        if (r.avoid.isNotEmpty()) {
            val group = items.filter { it.group == r.avoid && !it.isRoute && !it.isTrack }
            val inShapes = shapes.filter { it.group == r.avoid }
            var room = MAX_AVOID
            fun <T> take(list: List<T>, size: (T) -> Int) = list.filter { val n = size(it); if (n > room) { tooMuch = true; false } else { room -= n; true } }
            fun line(points: List<Pair<Double, Double>>) = points.joinToString(",") { (lat, lon) -> "${r5(lon)},${r5(lat)}" }
            val circles = group.filter { !it.isArrow }.map { Triple(it.lat, it.lon, MARKER_AVOID_METERS) } +
                inShapes.mapNotNull { s -> s.center?.let { Triple(it.first, it.second, s.radius) } }
            val nogos = take(circles) { 1 }.map { (lat, lon, m) -> "${r5(lon)},${r5(lat)},${maxOf(1L, Math.round(m))}" }
            val polygons = take(inShapes.filter { it.center == null }.map { it.ring }) { it.size }.map(::line)
            val polylines = take(group.filter { it.isArrow }.map { listOf(it.lat to it.lon, it.endLat!! to it.endLon!!) }) { it.size }.map(::line)
            if (nogos.isNotEmpty()) params["nogos"] = nogos.joinToString("|")
            if (polygons.isNotEmpty()) params["polygons"] = polygons.joinToString("|")
            if (polylines.isNotEmpty()) params["polylines"] = polylines.joinToString("|")
        }
        return Ask(profile, params, tooMuch)
    }

    /**
     * Degrees to 5 decimals, written as the map in the browser writes the number (JavaScript:
     * no trailing zeros, no exponent), so that both ask the router the very same.
     */
    fun r5(v: Double): String {
        val n = Math.round(v * 1e5)
        val whole = abs(n) / 100_000
        val part = (abs(n) % 100_000).toString().padStart(5, '0').trimEnd('0')
        return (if (n < 0) "-" else "") + whole + if (part.isEmpty()) "" else ".$part"
    }

    /** A bearing as the app shows it: "042°T". */
    fun bearingText(bearing: Double): String = String.format(java.util.Locale.US, "%03d°T", bearing.roundToInt() % 360)
}
