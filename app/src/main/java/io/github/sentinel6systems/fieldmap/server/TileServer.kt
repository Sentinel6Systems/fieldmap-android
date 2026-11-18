package io.github.sentinel6systems.fieldmap.server

import io.github.sentinel6systems.fieldmap.offline.Box
import io.github.sentinel6systems.fieldmap.offline.TileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One map style of a server. */
data class MapStyle(val id: String, val name: String, val url: String)

/** A tile server that answered: its root address and its styles. */
class Found(val base: String, val styles: List<MapStyle>)

/** What a style draws from, and where its base map has data. */
class StyleInfo(val sources: List<TileSource>, val coverage: Box?, val empty: Boolean)

/** Plain requests to a TileServer GL server (fieldmap-server runs one). */
object TileServer {
    // fieldmap-server's variants of a style for one print scale each ("topo-usgs-24k"): they are
    // for its print tool, which picks them by itself, and would only lengthen the list here.
    private val PRINT_ONLY = Regex("-\\d+k$")

    /**
     * The styles in the text of a server's styles.json, in order of name; null when the text
     * is not such a list. Each style's address is built on `base`, the root that answered.
     */
    fun parseStyles(text: String, base: String): List<MapStyle>? {
        val list = try { JSONArray(text) } catch (e: JSONException) { return null }
        val out = ArrayList<MapStyle>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: return null
            val id = o.optString("id").ifEmpty { return null }
            if (PRINT_ONLY.containsMatchIn(id)) continue
            out += MapStyle(id, o.optString("name").ifEmpty { id }, "${base}styles/$id/style.json")
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun stylesToJson(styles: List<MapStyle>): String =
        JSONArray().apply { styles.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }.toString()

    /** The styles of the server at `base`, or null when no tile server answers there. */
    suspend fun styles(base: String, timeoutMs: Int = 2500, way: Route? = null): List<MapStyle>? = withContext(Dispatchers.IO) {
        get("${base}styles.json", timeoutMs, way)?.let { parseStyles(it, base) }
    }

    /** Tries the places a typed address can mean; the first tile server that answers. */
    suspend fun connect(typed: String): Found? = Net.reach {
        ServerAddress.candidates(typed).firstNotNullOfOrNull { base -> styles(base, 4000, Route.LAN)?.let { Found(base, it) } }
    }

    /** Reads a style and the TileJSON of each of its sources. Null when the server does not answer. */
    suspend fun info(styleUrl: String): StyleInfo? = withContext(Dispatchers.IO) {
        try {
            val style = JSONObject(get(styleUrl, 6000) ?: return@withContext null)
            val sources = style.optJSONObject("sources") ?: JSONObject()
            val out = ArrayList<TileSource>()
            var coverage: Box? = null
            var empty = false
            for (id in sources.keys()) {
                val s = sources.getJSONObject(id)
                val type = s.optString("type")
                if (type != "vector" && type != "raster" && type != "raster-dem") continue
                val json = if (s.has("url")) JSONObject(get(s.getString("url"), 6000) ?: return@withContext null) else s
                val tiles = json.optJSONArray("tiles")?.optString(0).orEmpty()
                if (tiles.isEmpty()) continue
                out += TileSource(id, type != "vector", s.optInt("tileSize", 512), json.optInt("minzoom", 0), json.optInt("maxzoom", 22), tiles)
                if (type == "vector" && coverage == null && !empty) {
                    // A new fieldmap-server install has empty placeholder files.
                    if (json.optString("name") == "empty placeholder") empty = true
                    val b = json.optJSONArray("bounds")
                    if (b != null && b.length() == 4 && b.getDouble(2) - b.getDouble(0) < 300) coverage = Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                }
            }
            StyleInfo(out, coverage, empty)
        } catch (e: JSONException) {
            null
        }
    }

    /** The size of one file as it travels (compressed), 0 when the server has nothing there. */
    suspend fun size(url: String): Long = withContext(Dispatchers.IO) {
        try {
            // Asking for gzip ourselves keeps the answer as it travels: that is what is stored.
            Http.call(url, 4000) { header("Accept-Encoding", "gzip") }.use { r ->
                if (r.code != 200) return@withContext 0L
                var n = 0L
                val buf = ByteArray(16384)
                r.body?.byteStream()?.use { s -> while (true) { val got = s.read(buf); if (got < 0) break; n += got } }
                n
            }
        } catch (e: Exception) { 0L }
    }

    /** The status a request ends with, or null when nothing answers. */
    suspend fun status(url: String, timeoutMs: Int, way: Route? = null): Int? = withContext(Dispatchers.IO) {
        try { Http.call(url, timeoutMs, way).use { it.code } } catch (e: Exception) { null }
    }

    /** The text at an address, or null on any failure. `way` sends it one way whatever is in use. */
    fun get(url: String, timeoutMs: Int, way: Route? = null): String? = try {
        Http.call(url, timeoutMs, way).use { r -> if (r.code == 200) r.body?.string() else null }
    } catch (e: Exception) { null }
}
