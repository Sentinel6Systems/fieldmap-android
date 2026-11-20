package io.github.sentinel6systems.fieldmap.data

import org.json.JSONArray
import org.json.JSONException
import io.github.sentinel6systems.fieldmap.geo.Routes
import org.json.JSONObject
import kotlin.math.abs

/**
 * A coloured dot with a name, an arrow, or a route. An arrow is the print tool's, from `lat`/`lon`
 * (its start) to `endLat`/`endLon` (its head). A route is the line through its points, from the
 * first (also `lat`/`lon`) to the last. A shared one is also on the box, for everyone who reaches
 * it: `updated` is when it was last changed (UTC, ISO 8601) and `by` the phone that changed it.
 */
data class Marker(
    val id: String, val lat: Double, val lon: Double, val label: String, val color: String,
    val shared: Boolean = false, val updated: String = "", val by: String = "",
    val endLat: Double? = null, val endLon: Double? = null,
    val route: PlannedRoute? = null,
) {
    val isArrow get() = endLat != null && endLon != null
    val isRoute get() = route != null
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
 * A planned route: the points tapped for it, in order (lat to lon), each leg straight from one to
 * the next. At most Drawings.MAX_ROUTE_POINTS of them. `mph`: the speed its time is worked out at
 * (one of Routes.SPEEDS).
 */
data class PlannedRoute(val points: List<Pair<Double, Double>>, val mph: Int = Routes.DEFAULT_MPH)

/**
 * Markers to and from the drawings file of fieldmap-server's print tool (print/src/drawings.js):
 * GeoJSON, with what the print tool needs under "fieldmap" in each feature's properties. A file
 * written here loads in the print tool, and its files (or any GeoJSON with points) load here.
 * The app keeps its own markers in the same form.
 */
object Drawings {
    const val FORMAT = "fieldmap-drawings"
    const val MAX_LABEL = 30                    // as the name fields of the print tool
    /** The points of a route: with its name, color and who changed it, one mesh radio packet. */
    const val MAX_ROUTE_POINTS = 30

    class Loaded(val markers: List<Marker>, val skipped: Int)

    /**
     * One marker as a GeoJSON feature. sharing: also what belongs to a shared marker (the app's
     * own file and the box get it; a file for the print tool or other software does not).
     */
    fun feature(m: Marker, sharing: Boolean = false): JSONObject {
        val kind = when { m.isRoute -> "route"; m.isArrow -> "arrow"; else -> "marker" }
        val fieldmap = JSONObject().put("kind", kind).put("label", m.label).put("color", m.color).put("id", m.id)
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
            m.route != null -> {
                fieldmap.put("waypoints", positions(m.route.points)).put("speedMph", m.route.mph)
                JSONObject().put("type", "LineString").put("coordinates", positions(m.route.points))
            }
            m.isArrow -> JSONObject().put("type", "LineString").put("coordinates", positions(listOf(m.lat to m.lon, m.endLat!! to m.endLon!!)))
            else -> JSONObject().put("type", "Point").put("coordinates", JSONArray().put(m.lon).put(m.lat))
        }
        return JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", properties)
    }

    fun toGeoJson(markers: List<Marker>, sharing: Boolean = false): String {
        val features = JSONArray()
        for (m in markers) features.put(feature(m, sharing))
        return JSONObject().put("type", "FeatureCollection")
            .put("fieldmap", JSONObject().put("format", FORMAT).put("version", 1))
            .put("features", features).toString()
    }

    /**
     * Reads the text of a file. Points become markers, lines of two points arrows (from the first
     * point to the second), longer lines and the app's routes routes (a line of more than
     * MAX_ROUTE_POINTS points keeps the ones that shape it most); areas (the print tool's shapes)
     * are counted in `skipped`. Throws IllegalArgumentException with a plain message
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

        val markers = ArrayList<Marker>()
        var skipped = 0
        for (f in features) {
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
                "LineString" -> line(coords, fm)?.let {
                    markers += it.copy(id = id ?: newId(), label = name, color = color, shared = shared,
                        updated = if (shared) p.optString("updated") else "", by = if (shared) p.optString("by") else "")
                } ?: skipped++
                "MultiLineString" -> for (i in 0 until (coords?.length() ?: 0)) {
                    line(coords?.optJSONArray(i), null)?.let { markers += it.copy(id = newId(), label = name, color = color) } ?: skipped++
                }
                else -> skipped++
            }
        }
        return Loaded(markers, skipped)
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
        return Marker("", 0.0, 0.0, "", "", route = PlannedRoute(listOf(0.0 to 0.0), mph)).withPoints(Routes.simplify(points, MAX_ROUTE_POINTS))
    }

    /** The name the next new marker gets: P1, P2, ... (the first number not yet used); A1, A2, ... for an arrow, R1, R2, ... for a route. */
    fun nextName(markers: List<Marker>, arrow: Boolean = false, route: Boolean = false): String {
        val prefix = if (route) "R" else if (arrow) "A" else "P"
        val same = markers.filter { it.isArrow == arrow && it.isRoute == route }
        val used = same.mapNotNull { Regex("^$prefix(\\d+)$").find(it.label)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = same.size + 1
        while (n in used) n++
        return "$prefix$n"
    }
}
