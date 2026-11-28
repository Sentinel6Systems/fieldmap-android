package io.github.sentinel6systems.fieldmap.data

import io.github.sentinel6systems.fieldmap.share.Pending
import io.github.sentinel6systems.fieldmap.share.ShareMerge
import io.github.sentinel6systems.fieldmap.share.ShareState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The shared shapes of the map in the browser, kept in a file of the app's own storage: the map
 * shows them, and a route that avoids a group keeps away from that group's (Routes.ask), also
 * with no server in reach. The app does not draw shapes of its own.
 */
class ShapeStore(private val file: File) {
    var shapes: List<Shape> = emptyList()
        private set

    init {
        shapes = try { if (file.isFile) read(file.readText()) else emptyList() } catch (e: Exception) { emptyList() }
    }

    fun get(id: String): Shape? = shapes.firstOrNull { it.id == id }

    /** Takes an answer of the server; `pending`: what this phone has not sent yet. Returns true when the shapes changed. */
    fun take(state: ShareState, pending: Pending = Pending()): Boolean = set(ShareMerge.shapes(shapes, state, pending))

    /** One shape changed here (another group), or gone (null). */
    fun update(id: String, to: Shape?) = set(if (to == null) shapes.filter { it.id != id } else shapes.map { if (it.id == id) to else it })

    /** All of them gone: another server. */
    fun clear() = set(emptyList())

    private fun set(now: List<Shape>): Boolean {
        if (now == shapes) return false
        shapes = now
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(write(now))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {}
        return true
    }

    companion object {
        /** Positions as GeoJSON has them: [lon, lat]. */
        private fun positions(points: List<Pair<Double, Double>>) = JSONArray().apply { for ((lat, lon) in points) put(JSONArray().put(lon).put(lat)) }
        private fun points(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONArray(i)?.let { it.optDouble(1) to it.optDouble(0) } }

        fun write(shapes: List<Shape>): String = JSONObject().put("version", 2).put("shapes", JSONArray().apply {
            for (s in shapes) put(JSONObject().put("id", s.id).put("group", s.group).put("kind", s.kind).put("label", s.label).put("color", s.color)
                .put("feature", s.feature).apply {
                    if (s.center != null) put("center", positions(listOf(s.center))).put("radius", s.radius) else put("ring", positions(s.ring))
                })
        }).toString()

        fun read(text: String): List<Shape> {
            val list = JSONObject(text).optJSONArray("shapes") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val center = points(o.optJSONArray("center")).firstOrNull()
                val kind = o.optString("kind").ifEmpty { if (center != null) "circle" else "polygon" }
                Shape(o.optString("id"), o.optString("group"), if (center == null) points(o.optJSONArray("ring")) else emptyList(), center,
                    o.optDouble("radius", 0.0), kind, o.optString("label"), o.optString("color", Palette.DEFAULT), o.optString("feature"))
                    .takeIf { it.id.isNotEmpty() && (it.center != null || it.ring.size >= 3) }
            }
        }
    }
}
