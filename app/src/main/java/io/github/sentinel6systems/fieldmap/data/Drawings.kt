package io.github.sentinel6systems.fieldmap.data

import org.json.JSONArray
import org.json.JSONException
import io.github.sentinel6systems.fieldmap.geo.Routes
import io.github.sentinel6systems.fieldmap.geo.Shapes
import io.github.sentinel6systems.fieldmap.geo.TrackPoint
import org.json.JSONObject
import kotlin.math.abs

/**
 * A coloured dot with a name, an arrow, or a route. An arrow is the print tool's, from `lat`/`lon`
 * (its start) to `endLat`/`endLon` (its head). A route is the line through its points, from the
 * first (also `lat`/`lon`) to the last. A shared one is also on the box, for everyone who reaches
 * it: `updated` is when it was last changed (UTC, ISO 8601) and `by` the phone that changed it.
 * `group`: the group the map in the browser keeps it in ("" for none). The app has no groups of
 * its own: it keeps the name as it came, and a route can avoid a group's areas (Routes.ask).
 */
data class Marker(
    val id: String, val lat: Double, val lon: Double, val label: String, val color: String,
    val shared: Boolean = false, val updated: String = "", val by: String = "",
    val endLat: Double? = null, val endLon: Double? = null,
    val route: PlannedRoute? = null,
    val track: RecordedTrack? = null,
    val group: String = "",
) {
    val isArrow get() = endLat != null && endLon != null
    val isRoute get() = route != null
    /** A recorded track: kept on this device and exported, not shared. */
    val isTrack get() = track != null
    /**
     * What it can be: a route (2) more than an arrow (1) more than a point (0). An app from before
     * routes sees a route of two points as an arrow; such a copy never takes the route's place.
     */
    val rank get() = if (isRoute) 2 else if (isArrow) 1 else 0

    /** This route with other points (at least one); its `lat`/`lon` go with the first. */
    fun withPoints(points: List<Pair<Double, Double>>) =
        copy(lat = points[0].first, lon = points[0].second, route = route?.copy(points = points) ?: PlannedRoute(points))
}

/**
 * A planned route: the points tapped for it, in order (lat to lon), at most
 * Drawings.MAX_ROUTE_POINTS. `type`: how its legs go from one point to the next, along roads,
 * along trails or straight; each device draws them from its own routing data (ui/RouteLines.kt),
 * so only the points travel, with what the router is asked for: `options`, the keys of its Route
 * Options (Routes.OPTIONS, only those of its type, in their fixed order), `avoid`, the group whose
 * items it keeps away from ("" for none), and `alternative`, the router's second, third or fourth
 * way (0: the best one). `mph`: the speed its time is worked out at (one of Routes.SPEEDS).
 */
data class PlannedRoute(
    val points: List<Pair<Double, Double>>, val mph: Int = Routes.DEFAULT_MPH, val type: RouteType = RouteType.STRAIGHT,
    val options: List<String> = emptyList(), val avoid: String = "", val alternative: Int = 0,
)

/**
 * A shape of the map in the browser, shared through the server: a circle (`center`, `radius` in
 * meters) or an outline (a rectangle or polygon: `ring`, without its closing point), lat to lon.
 * `kind`: "circle", "rect" or "polygon". The app shows the shared ones (data/ShapeStore.kt) and
 * keeps routes away from a group's, but draws none of its own. `feature`: the feature as the
 * server gave it, to send it back with another group (Rename Group).
 */
data class Shape(
    val id: String, val group: String, val ring: List<Pair<Double, Double>> = emptyList(),
    val center: Pair<Double, Double>? = null, val radius: Double = 0.0,
    val kind: String = if (center != null) "circle" else "polygon", val label: String = "", val color: String = Palette.DEFAULT,
    val feature: String = "",
)

/**
 * A track recorded by this device (Menu > Track Recording): its segments, the parts between
 * Pause and Resume, each of at least two points.
 */
data class RecordedTrack(val segments: List<List<TrackPoint>>)

/**
 * How a route's legs go: by road (BRouter's car profile), on foot by roads, tracks and trails (its
 * hiking profile), by bicycle (its trekking profile), or straight. `key`: in files, `profile`:
 * BRouter's profile, `mph`: the speed a new route of the type starts with, `roads`: the road
 * classes it can use (OpenMapTiles' transportation layer, as on the map in the browser), to look
 * for the nearest road when no way was found.
 */
enum class RouteType(val key: String, val title: String, val profile: String?, val mph: Int, val roads: List<String> = emptyList()) {
    DRIVING("driving", "Driving", "car-vario", 25, listOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")),
    WALKING("walking", "Walking", "hiking-mountain", 2, listOf("trunk", "primary", "secondary", "tertiary", "minor", "service", "track", "path")),
    BICYCLE("bicycle", "Bicycle", "trekking", 10, listOf("primary", "secondary", "tertiary", "minor", "service", "track", "path")),
    STRAIGHT("straight", "Straight Line", null, 2);

    companion object {
        fun of(key: String?): RouteType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Markers to and from the drawings file of fieldmap-server's print tool (print/src/drawings.js):
 * GeoJSON, with what the print tool needs under "fieldmap" in each feature's properties. A file
 * written here loads in the print tool, and its files (or any GeoJSON with points) load here.
 * The app keeps its own markers in the same form.
 */
object Drawings {
    const val FORMAT = "fieldmap-drawings"
    const val MAX_LABEL = 30                    // as the name fields of the print tool
    const val MAX_GROUP = 30                    // as a group's name on the map in the browser
    /** The points of a route: with its name, color and who changed it, one mesh radio packet. */
    const val MAX_ROUTE_POINTS = 30

    /** `own`: the file is a drawings file of the app or the print tool (its items keep their groups as they are). */
    class Loaded(val markers: List<Marker>, val skipped: Int, val own: Boolean = false)

    /**
     * One marker as a GeoJSON feature. sharing: also what belongs to a shared marker (the app's
     * own file and the box get it; a file for the print tool or other software does not).
     * line: a route's line as it is drawn (for other software); without it, its points.
     */
    fun feature(m: Marker, sharing: Boolean = false, line: List<Pair<Double, Double>>? = null): JSONObject {
        val kind = when { m.isTrack -> "track"; m.isRoute -> "route"; m.isArrow -> "arrow"; else -> "marker" }
        val fieldmap = JSONObject().put("kind", kind).put("label", m.label).put("color", m.color).put("id", m.id)
        if (m.group.isNotEmpty()) fieldmap.put("group", m.group)
        val properties = JSONObject().put("name", m.label).put("stroke", m.color).put("marker-color", m.color).put("fieldmap", fieldmap)
        if (sharing && m.shared) {
            fieldmap.put("shared", true)
            properties.put("by", m.by).put("updated", m.updated)
        }
        fun positions(points: List<Pair<Double, Double>>) = JSONArray().apply { for ((lat, lon) in points) put(JSONArray().put(lon).put(lat)) }
        // An arrow is a line of two points, from its start to its head, as the print tool writes it.
        // A route is its line, for other software; its points are in "fieldmap" too, so that it can
        // be edited again when its line no longer goes straight from point to point.
        val geometry = when {
            // A track: its segments, with heights; the times and stops in "fieldmap" (seconds since 1970, seconds stopped).
            m.track != null -> {
                fieldmap.put("times", JSONArray().apply { for (seg in m.track.segments) put(JSONArray().apply { for (p in seg) put(p.time / 1000) }) })
                fieldmap.put("still", JSONArray().apply { for (seg in m.track.segments) put(JSONArray().apply { for (p in seg) put(p.still) }) })
                JSONObject().put("type", "MultiLineString").put("coordinates", JSONArray().apply {
                    for (seg in m.track.segments) put(JSONArray().apply { for (p in seg) put(JSONArray().put(p.lon).put(p.lat).apply { p.alt?.let { put(Math.round(it * 10) / 10.0) } }) })
                })
            }
            m.route != null -> {
                fieldmap.put("waypoints", positions(m.route.points)).put("speedMph", m.route.mph).put("routing", m.route.type.key)
                // Only what is set, as the map in the browser writes it (print/src/shared.js routeExtrasOut).
                Routes.cleanOptions(m.route.type, m.route.options).takeIf { it.isNotEmpty() }?.let { fieldmap.put("options", it.joinToString(" ")) }
                if (m.route.avoid.isNotEmpty()) fieldmap.put("avoid", m.route.avoid)
                if (m.route.alternative in 1..3) fieldmap.put("alternative", m.route.alternative)
                JSONObject().put("type", "LineString").put("coordinates", positions(line?.takeIf { it.size >= 2 } ?: m.route.points))
            }
            m.isArrow -> JSONObject().put("type", "LineString").put("coordinates", positions(listOf(m.lat to m.lon, m.endLat!! to m.endLon!!)))
            else -> JSONObject().put("type", "Point").put("coordinates", JSONArray().put(m.lon).put(m.lat))
        }
        return JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", properties)
    }

    /** A drawings file. `shapes`: the shared shapes too (for a file, not the app's own); `group`: a file of one group names it at the top. */
    fun toGeoJson(markers: List<Marker>, sharing: Boolean = false, shapes: List<Shape> = emptyList(), group: String = "",
                  line: (Marker) -> List<Pair<Double, Double>>? = { null }): String {
        val features = JSONArray()
        for (m in markers) features.put(feature(m, sharing, line(m)))
        for (s in shapes) features.put(shapeFeature(s))
        val head = JSONObject().put("format", FORMAT).put("version", 1)
        if (group.isNotEmpty()) head.put("group", group)
        return JSONObject().put("type", "FeatureCollection").put("fieldmap", head).put("features", features).toString()
    }

    /**
     * Reads the text of a file. Points become markers, lines of two points arrows (from the first
     * point to the second), longer lines and the app's routes routes (a line of more than
     * MAX_ROUTE_POINTS points keeps the ones that shape it most); areas (the print tool's shapes)
     * are counted in `skipped`. Each keeps its group, or the file's when it names one for all.
     * Throws IllegalArgumentException with a plain message
     * when the text is not GeoJSON at all. newId: makes the id of a marker that has none.
     */
    fun fromGeoJson(text: String, defaultColor: String, newId: () -> String): Loaded {
        val data = try { JSONObject(text) } catch (e: JSONException) {
            throw IllegalArgumentException("This file is not a GeoJSON file.")
        }
        val features: List<JSONObject> = when (data.optString("type")) {
            "FeatureCollection" -> data.optJSONArray("features")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            "Feature" -> listOf(data)
            else -> if (data.has("coordinates")) listOf(JSONObject().put("type", "Feature").put("geometry", data)) else null
        } ?: throw IllegalArgumentException("This file contains no map data. It isn't a GeoJSON file.")

        val fileGroup = groupName(data.optJSONObject("fieldmap")?.opt("group"))
        val markers = ArrayList<Marker>()
        var skipped = 0
        for (f in features) {
            val before = markers.size
            val g = f.optJSONObject("geometry")
            val p = f.optJSONObject("properties") ?: JSONObject()
            val fm = p.optJSONObject("fieldmap")
            val color = colour(fm?.opt("color")) ?: listOf("marker-color", "stroke", "fill", "color").firstNotNullOfOrNull { colour(p.opt(it)) } ?: defaultColor
            // The print tool's own name as it was (it may be empty); else the name other software gave.
            val name = (if (fm != null) (fm.opt("label") as? String).orEmpty()
            else listOf("name", "title", "label", "callsign").firstNotNullOfOrNull { (p.opt(it) as? String)?.trim()?.ifEmpty { null } }.orEmpty())
                .trim().take(MAX_LABEL)
            val id = (fm?.opt("id") as? String)?.takeIf { it.isNotBlank() }
            val coords = g?.optJSONArray("coordinates")
            val shared = fm?.optBoolean("shared") == true
            when (g?.optString("type")) {
                "Point" -> point(coords)?.let {
                    markers += Marker(id ?: newId(), it.first, it.second, name, color, shared, if (shared) p.optString("updated") else "", if (shared) p.optString("by") else "")
                } ?: skipped++
                "MultiPoint" -> for (i in 0 until (coords?.length() ?: 0)) {
                    point(coords?.optJSONArray(i))?.let { markers += Marker(newId(), it.first, it.second, name, color) } ?: skipped++
                }
                "MultiLineString", "LineString" -> if (fm?.optString("kind") == "track") {
                    track(g.optString("type"), coords, fm)?.let { markers += Marker(id ?: newId(), it.segments[0][0].lat, it.segments[0][0].lon, name, color, track = it) } ?: skipped++
                } else if (g.optString("type") == "LineString") {
                    line(coords, fm)?.let {
                        markers += it.copy(id = id ?: newId(), label = name, color = color, shared = shared,
                            updated = if (shared) p.optString("updated") else "", by = if (shared) p.optString("by") else "")
                    } ?: skipped++
                } else for (i in 0 until (coords?.length() ?: 0)) {
                    line(coords?.optJSONArray(i), null)?.let { markers += it.copy(id = newId(), label = name, color = color) } ?: skipped++
                }
                else -> skipped++
            }
            val group = groupName(fm?.opt("group")).ifEmpty { fileGroup }
            if (group.isNotEmpty()) for (i in before until markers.size) markers[i] = markers[i].copy(group = group)
        }
        return Loaded(markers, skipped, data.optJSONObject("fieldmap")?.optString("format") == FORMAT)
    }

    /** A group's name as the map in the browser keeps it: one space for any run of spaces, trimmed, cut to MAX_GROUP; "" for none. */
    fun groupName(v: Any?): String = (v as? String)?.replace(SPACES, " ")?.trim()?.take(MAX_GROUP).orEmpty()
    // JavaScript's \s, written out: Android's regular expressions and the JVM's do not agree on \s alone.
    private val SPACES = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

    /**
     * A shape of the map in the browser from a shared feature (a Polygon), as the map itself reads
     * one (print/src/shared.js itemFromFeature): a circle from its center and radius in "fieldmap",
     * a rectangle from its two corners there, any other outline from the geometry; null when the
     * feature is not an area.
     */
    fun shape(f: JSONObject, id: String): Shape? {
        val g = f.optJSONObject("geometry") ?: return null
        val ring = g.optJSONArray("coordinates")?.optJSONArray(0)
        if (g.optString("type") != "Polygon" || ring == null) return null
        val outline = points(ring)?.takeIf { it.size >= 4 } ?: return null
        val p = f.optJSONObject("properties") ?: JSONObject()
        val fm = p.optJSONObject("fieldmap") ?: JSONObject()
        val group = groupName(fm.opt("group"))
        val kind = fm.optString("kind")
        // Named and colored as the map in the browser reads it (print/src/shared.js describe).
        val label = ((fm.opt("label") as? String) ?: (p.opt("name") as? String)).orEmpty().take(MAX_LABEL)
        val color = colour(fm.opt("color")) ?: listOf("marker-color", "stroke", "fill").firstNotNullOfOrNull { colour(p.opt(it)) } ?: Palette.DEFAULT
        val base = Shape(id, group, label = label, color = color, feature = f.toString())
        val radius = (fm.opt("radius") as? Number)?.toDouble()
        val center = if (kind == "circle") place(fm.opt("center")) else null
        if (center != null && radius != null && radius.isFinite() && radius > 0) return base.copy(center = center, radius = radius, kind = "circle")
        val corners = if (kind == "rect") fm.optJSONArray("points")?.takeIf { it.length() == 2 }?.let { c -> listOf(place(c.opt(0)), place(c.opt(1))) } else null
        if (corners != null && corners.all { it != null }) {
            val (a, b) = corners[0]!! to corners[1]!!
            val (s, n) = minOf(a.first, b.first) to maxOf(a.first, b.first)
            val (w, e) = minOf(a.second, b.second) to maxOf(a.second, b.second)
            return base.copy(ring = listOf(s to w, s to e, n to e, n to w), kind = "rect")
        }
        return base.copy(ring = outline.dropLast(1), kind = "polygon")
    }

    /**
     * A shape as the print tool's own file writes it (print/src/drawings.js toGeoJSON): its
     * outline, with a circle's center and radius or a rectangle's corners in "fieldmap", so that
     * Import (the Markers tab) makes it again exactly.
     */
    fun shapeFeature(s: Shape): JSONObject {
        fun ll(p: Pair<Double, Double>) = JSONObject().put("lng", p.second).put("lat", p.first)
        val fieldmap = JSONObject().put("kind", s.kind).put("label", s.label).put("color", s.color)
        if (s.group.isNotEmpty()) fieldmap.put("group", s.group)
        when {
            s.center != null -> fieldmap.put("center", ll(s.center)).put("radius", s.radius)
            s.kind == "rect" -> fieldmap.put("points", JSONArray().put(ll(s.ring[0])).put(ll(s.ring[2])))
            else -> fieldmap.put("points", JSONArray().apply { for (p in s.ring) put(ll(p)) })
        }
        val ring = Shapes.ring(s)
        val coords = JSONArray().put(JSONArray().apply { for ((lat, lon) in ring + ring.take(1)) put(JSONArray().put(lon).put(lat)) })
        return JSONObject().put("type", "Feature").put("geometry", JSONObject().put("type", "Polygon").put("coordinates", coords))
            .put("properties", JSONObject().put("name", s.label).put("stroke", s.color).put("marker-color", s.color).put("fill", s.color).put("fieldmap", fieldmap))
    }

    /** A position of "fieldmap": [lon, lat], or { lng, lat } as the print tool's files have it. */
    private fun place(v: Any?): Pair<Double, Double>? = when (v) {
        is JSONArray -> point(v)
        is JSONObject -> if (v.has("lng") && v.has("lat")) point(JSONArray().put(v.optDouble("lng")).put(v.optDouble("lat"))) else null
        else -> null
    }

    private fun colour(v: Any?): String? = (v as? String)?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }?.lowercase()

    /** lat to lon from a GeoJSON position, or null. Longitudes past ±180 are brought back into range. */
    private fun point(c: JSONArray?): Pair<Double, Double>? {
        if (c == null || c.length() < 2) return null
        val lon = c.optDouble(0)
        val lat = c.optDouble(1)
        if (lon.isNaN() || lat.isNaN() || abs(lat) > 90 || abs(lon) > 540) return null
        return lat to if (abs(lon) <= 180) lon else ((lon + 540) % 360 + 360) % 360 - 180
    }

    /** The positions of a line, or null when one of them is not a position. */
    private fun points(c: JSONArray?): List<Pair<Double, Double>>? =
        c?.let { (0 until it.length()).map { i -> point(it.optJSONArray(i)) ?: return null } }

    /**
     * An arrow or a route (id, name and color still to be set) from a line: a route when the app
     * wrote it as one (its points from "fieldmap"), else an arrow from two points, a route from more.
     */
    private fun line(c: JSONArray?, fm: JSONObject?): Marker? {
        val line = points(c)?.takeIf { it.size >= 2 } ?: return null
        val route = fm?.optString("kind") == "route"
        val points = (if (route) points(fm?.optJSONArray("waypoints"))?.takeIf { it.size >= 2 } else null) ?: line
        if (!route && points.size == 2) return Marker("", points[0].first, points[0].second, "", "", endLat = points[1].first, endLon = points[1].second)
        val mph = fm?.optInt("speedMph")?.takeIf { it in Routes.SPEEDS } ?: Routes.DEFAULT_MPH
        val type = RouteType.of(fm?.optString("routing")) ?: RouteType.STRAIGHT
        // Its Route Options, Avoid Areas group and Way, as the map in the browser reads them (print/src/shared.js routeExtras).
        val options = Routes.cleanOptions(type, when (val o = fm?.opt("options")) {
            is String -> o.split(Regex("\\s+"))
            is JSONArray -> (0 until o.length()).mapNotNull { o.opt(it) as? String }
            else -> emptyList()
        })
        val alternative = (fm?.opt("alternative") as? Number)?.toDouble()?.takeIf { it == Math.floor(it) && it > 0 && it < 4 }?.toInt() ?: 0
        val planned = PlannedRoute(listOf(0.0 to 0.0), mph, type, options, groupName(fm?.opt("avoid")), alternative)
        return Marker("", 0.0, 0.0, "", "", route = planned).withPoints(Routes.simplify(points, MAX_ROUTE_POINTS))
    }

    /** A track from the app's own file: its segments with times (and heights, stops); null when they do not match. */
    private fun track(type: String, c: JSONArray?, fm: JSONObject): RecordedTrack? {
        val parts = (if (type == "LineString") listOf(c) else (0 until (c?.length() ?: 0)).map { c?.optJSONArray(it) })
        val times = fm.optJSONArray("times")
        val still = fm.optJSONArray("still")
        val segments = parts.mapIndexedNotNull { i, part ->
            val t = times?.optJSONArray(i)
            val positions = part ?: return@mapIndexedNotNull null
            (0 until positions.length()).map { k ->
                val pos = positions.optJSONArray(k) ?: return null
                val (lat, lon) = point(pos) ?: return null
                val alt = pos.optDouble(2).takeIf { pos.length() > 2 && it.isFinite() }
                TrackPoint(lat, lon, alt, (t?.optLong(k) ?: 0L) * 1000, still = still?.optJSONArray(i)?.optInt(k) ?: 0)
            }.takeIf { it.size >= 2 }
        }
        return if (segments.isEmpty()) null else RecordedTrack(segments)
    }

    /** The name the next new marker gets: P1, P2, ... (the first number not yet used); A1, A2, ... for an arrow, R1, R2, ... for a route. */
    fun nextName(markers: List<Marker>, arrow: Boolean = false, route: Boolean = false, track: Boolean = false): String {
        val prefix = if (track) "T" else if (route) "R" else if (arrow) "A" else "P"
        val same = markers.filter { it.isArrow == arrow && it.isRoute == route && it.isTrack == track }
        val used = same.mapNotNull { Regex("^$prefix(\\d+)$").find(it.label)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = same.size + 1
        while (n in used) n++
        return "$prefix$n"
    }
}
