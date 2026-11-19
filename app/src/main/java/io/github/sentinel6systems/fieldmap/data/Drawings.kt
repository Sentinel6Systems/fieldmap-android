package io.github.sentinel6systems.fieldmap.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/**
 * A coloured dot with a name. A shared marker is also on the box, for everyone who reaches it:
 * `updated` is when it was last changed (UTC, ISO 8601) and `by` the phone that changed it.
 */
data class Marker(
    val id: String, val lat: Double, val lon: Double, val label: String, val color: String,
    val shared: Boolean = false, val updated: String = "", val by: String = "",
)

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
        val fieldmap = JSONObject().put("kind", "marker").put("label", m.label).put("color", m.color).put("id", m.id)
        val properties = JSONObject().put("name", m.label).put("stroke", m.color).put("marker-color", m.color).put("fieldmap", fieldmap)
        if (sharing && m.shared) {
            fieldmap.put("shared", true)
            properties.put("by", m.by).put("updated", m.updated)
        }
        return JSONObject().put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(m.lon).put(m.lat)))
            .put("properties", properties)
    }

    fun toGeoJson(markers: List<Marker>, sharing: Boolean = false): String {
        val features = JSONArray()
        for (m in markers) features.put(feature(m, sharing))
        return JSONObject().put("type", "FeatureCollection")
            .put("fieldmap", JSONObject().put("format", FORMAT).put("version", 1))
            .put("features", features).toString()
    }

    /**
     * Reads the text of a file. Points become markers; lines and areas (the print tool's arrows
     * and shapes) are counted in `skipped`. Throws IllegalArgumentException with a plain message
     * when the text is not GeoJSON at all. newId: makes the id of a marker that has none.
     */
    fun fromGeoJson(text: String, defaultColor: String, newId: () -> String): Loaded {
        val data = try { JSONObject(text) } catch (e: JSONException) {
            throw IllegalArgumentException("This file is not a map drawing file (GeoJSON).")
        }
        val features: List<JSONObject> = when (data.optString("type")) {
            "FeatureCollection" -> data.optJSONArray("features")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            "Feature" -> listOf(data)
            else -> if (data.has("coordinates")) listOf(JSONObject().put("type", "Feature").put("geometry", data)) else null
        } ?: throw IllegalArgumentException("This file has no map features in it (it is not GeoJSON).")

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

    /** The name the next new marker gets: P1, P2, ... (the first number not yet used). */
    fun nextName(markers: List<Marker>): String {
        val used = markers.mapNotNull { Regex("^P(\\d+)$").find(it.label)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = markers.size + 1
        while (n in used) n++
        return "P$n"
    }
}
