package io.github.sentinel6systems.fieldmap.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/**
 * A coloured dot with a name, or an arrow: the print tool's arrows, from `lat`/`lon` (its start)
 * to `endLat`/`endLon` (its head). A shared one is also on the box, for everyone who reaches it:
 * `updated` is when it was last changed (UTC, ISO 8601) and `by` the phone that changed it.
 */
data class Marker(
    val id: String, val lat: Double, val lon: Double, val label: String, val color: String,
    val shared: Boolean = false, val updated: String = "", val by: String = "",
    val endLat: Double? = null, val endLon: Double? = null,
) {
    val isArrow get() = endLat != null && endLon != null
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

    class Loaded(val markers: List<Marker>, val skipped: Int)

    /**
     * One marker as a GeoJSON feature. sharing: also what belongs to a shared marker (the app's
     * own file and the box get it; a file for the print tool or other software does not).
     */
    fun feature(m: Marker, sharing: Boolean = false): JSONObject {
        val fieldmap = JSONObject().put("kind", if (m.isArrow) "arrow" else "marker").put("label", m.label).put("color", m.color).put("id", m.id)
        val properties = JSONObject().put("name", m.label).put("stroke", m.color).put("marker-color", m.color).put("fieldmap", fieldmap)
        if (sharing && m.shared) {
            fieldmap.put("shared", true)
            properties.put("by", m.by).put("updated", m.updated)
        }
        // An arrow is a line of two points, from its start to its head, as the print tool writes it.
        val geometry = if (m.isArrow) JSONObject().put("type", "LineString")
            .put("coordinates", JSONArray().put(JSONArray().put(m.lon).put(m.lat)).put(JSONArray().put(m.endLon).put(m.endLat)))
            else JSONObject().put("type", "Point").put("coordinates", JSONArray().put(m.lon).put(m.lat))
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
     * point to the second); areas (the print tool's shapes) and longer lines are counted in `skipped`. Throws IllegalArgumentException with a plain message
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
                "LineString" -> arrow(coords)?.let { (a, b) ->
                    markers += Marker(id ?: newId(), a.first, a.second, name, color, shared, if (shared) p.optString("updated") else "",
                        if (shared) p.optString("by") else "", b.first, b.second)
                } ?: skipped++
                "MultiLineString" -> for (i in 0 until (coords?.length() ?: 0)) {
                    arrow(coords?.optJSONArray(i))?.let { (a, b) -> markers += Marker(newId(), a.first, a.second, name, color, endLat = b.first, endLon = b.second) } ?: skipped++
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

    /** An arrow's two ends from a line: exactly two points, else null. */
    private fun arrow(c: JSONArray?): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        if (c == null || c.length() != 2) return null
        return (point(c.optJSONArray(0)) ?: return null) to (point(c.optJSONArray(1)) ?: return null)
    }

    /** The name the next new marker gets: P1, P2, ... (the first number not yet used); A1, A2, ... for an arrow. */
    fun nextName(markers: List<Marker>, arrow: Boolean = false): String {
        val prefix = if (arrow) "A" else "P"
        val same = markers.filter { it.isArrow == arrow }
        val used = same.mapNotNull { Regex("^$prefix(\\d+)$").find(it.label)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = same.size + 1
        while (n in used) n++
        return "$prefix$n"
    }
}
