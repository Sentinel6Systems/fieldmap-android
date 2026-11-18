package io.github.sentinel6systems.fieldmap.offline

import android.content.Context
import io.github.sentinel6systems.fieldmap.server.MapStyle
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** One saved map area: a MapLibre offline region and what the app notes with it. */
class SavedArea(val region: OfflineRegion, val name: String, val styleId: String, val styleName: String, val created: Long) {
    private val definition = region.definition as OfflineTilePyramidRegionDefinition
    val styleUrl: String get() = definition.styleURL.orEmpty()
    val bounds: LatLngBounds? get() = definition.bounds

    var files = 0L          // downloaded so far
    var filesNeeded = 0L
    var bytes = 0L
    var complete = false
    var downloading = false
    /** Set while the server cannot be reached during a download. */
    var waiting = false

    val percent: Int get() = if (filesNeeded > 0) (files * 100 / filesNeeded).toInt().coerceIn(0, 100) else 0

    /** Everything needed to make this area again. */
    fun toJson(): JSONObject = JSONObject().put("name", name).put("styleId", styleId).put("styleName", styleName).put("created", created)
        .put("styleUrl", styleUrl).put("pixelRatio", definition.pixelRatio.toDouble())
        .put("north", bounds?.latitudeNorth).put("east", bounds?.longitudeEast).put("south", bounds?.latitudeSouth).put("west", bounds?.longitudeWest)
}

/**
 * The saved areas, on top of MapLibre's offline regions. MapLibre keeps the files (style,
 * fonts, sprites and every tile of every source the style draws from) in its own database and
 * serves them to the map when there is no network. The same tile is stored once, however many
 * areas and styles use it.
 */
class Areas(context: Context) {
    private val manager = OfflineManager.getInstance(context)
    private val database = File(context.filesDir, "mbgl-offline.db")
    /** Areas being made again by updateAll(). */
    private val waiting = File(context.filesDir, "areas-to-restore.json")
    var list: List<SavedArea> = emptyList()
        private set
    /** Called on the main thread whenever the list or a download's progress changes. */
    var onChange: () -> Unit = {}
    /** Called once when a download finishes. */
    var onSaved: (SavedArea) -> Unit = {}

    val downloading: SavedArea? get() = list.firstOrNull { it.downloading }

    init {
        // MapLibre stops at 6,000 tiles unless told otherwise; the app has its own limits.
        manager.setOfflineMapboxTileCountLimit(Long.MAX_VALUE)
        // Maps that were only looked at are kept too, up to this much, oldest dropped first.
        manager.setMaximumAmbientCacheSize(AMBIENT_BYTES, null)
    }

    /** Reads the saved areas. Unfinished downloads carry on. */
    fun load(done: () -> Unit = {}) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                list = offlineRegions.orEmpty().mapNotNull(::read).sortedByDescending { it.created }
                var left = list.size
                if (waiting.isFile) restore()                 // an update that was cut short
                if (left == 0) { onChange(); done() }
                for (area in list) {
                    area.region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) note(area, status)
                            if (!area.complete) start(area)
                            if (--left == 0) { onChange(); done() }
                        }
                        override fun onError(error: String?) { if (--left == 0) { onChange(); done() } }
                    })
                }
            }
            override fun onError(error: String) { done() }
        })
    }

    /** Starts saving an area of a style. `failed` gets a plain message if it cannot start. */
    fun save(name: String, style: MapStyle, bounds: LatLngBounds, pixelRatio: Float, failed: (String) -> Unit) {
        val meta = JSONObject().put("name", name).put("styleId", style.id).put("styleName", style.name).put("created", System.currentTimeMillis())
        create(style.url, bounds, pixelRatio, meta, failed)
    }

    private fun create(styleUrl: String, bounds: LatLngBounds, pixelRatio: Float, meta: JSONObject, failed: (String) -> Unit = {}, done: () -> Unit = {}) {
        val definition = OfflineTilePyramidRegionDefinition(styleUrl, bounds, Tiles.MIN_ZOOM.toDouble(), Tiles.MAX_ZOOM.toDouble(), pixelRatio)
        manager.createOfflineRegion(definition, meta.toString().toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                read(offlineRegion)?.let { area ->
                    list = (listOf(area) + list).sortedByDescending { it.created }
                    start(area)
                    onChange()
                }
                done()
            }
            override fun onError(error: String) { failed(error); done() }
        })
    }

    /**
     * Removes an area and what only it used. MapLibre would keep the area's files as "maps
     * that were looked at" (up to AMBIENT_BYTES); those are dropped too, so that deleting
     * frees the space and an area saved again is fetched fresh.
     */
    fun delete(area: SavedArea) {
        area.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        area.region.setObserver(null)
        list = list - area
        onChange()
        area.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = manager.clearAmbientCache(null)
            override fun onError(error: String) {}
        })
    }

    /**
     * Downloads every saved area again from nothing, for after the maps on the server were
     * rebuilt. One area cannot be refreshed alone: MapLibre takes a file that is already on the
     * phone as done, whatever its age, and areas share files. So the areas are written down,
     * the store is emptied, and they are made again. The list is kept in a file until all are
     * back, in case the app is closed in between.
     */
    fun updateAll() {
        if (list.isEmpty()) return
        waiting.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        for (area in list) { area.region.setDownloadState(OfflineRegion.STATE_INACTIVE); area.region.setObserver(null) }
        list = emptyList()
        onChange()
        manager.resetDatabase(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = restore()
            override fun onError(message: String) = load()
        })
    }

    /** Makes the areas of the waiting list that are not there (again). */
    private fun restore() {
        val entries = try { JSONArray(waiting.readText()).let { a -> (0 until a.length()).map(a::getJSONObject) } } catch (e: Exception) { emptyList() }
        val have = list.map { it.created to it.name }.toSet()
        val missing = entries.filter { (it.optLong("created") to it.optString("name")) !in have && it.has("north") }
        var left = missing.size
        if (left == 0) waiting.delete()
        for (e in missing) {
            val bounds = LatLngBounds.from(e.getDouble("north"), e.getDouble("east"), e.getDouble("south"), e.getDouble("west"))
            val meta = JSONObject().put("name", e.optString("name")).put("styleId", e.optString("styleId")).put("styleName", e.optString("styleName")).put("created", e.optLong("created"))
            create(e.getString("styleUrl"), bounds, e.optDouble("pixelRatio", 1.0).toFloat(), meta) { if (--left == 0) waiting.delete() }
        }
    }

    /**
     * What all saved maps take on the phone. Not the sum of the areas' sizes: a file that
     * several areas share is stored once.
     */
    fun storedBytes(): Long = database.length()

    /** Maps that were only looked at may be out of date after the server's maps were rebuilt: check them again. */
    fun refreshViewed() = manager.invalidateAmbientCache(null)

    private fun read(region: OfflineRegion): SavedArea? {
        if (region.definition !is OfflineTilePyramidRegionDefinition) return null
        val meta = try { JSONObject(String(region.metadata)) } catch (e: Exception) { JSONObject() }
        return SavedArea(region, meta.optString("name", "Area"), meta.optString("styleId"), meta.optString("styleName"), meta.optLong("created"))
    }

    private fun note(area: SavedArea, status: OfflineRegionStatus) {
        area.files = status.completedResourceCount
        area.filesNeeded = status.requiredResourceCount
        area.bytes = status.completedResourceSize
        area.complete = status.isComplete
    }

    private fun start(area: SavedArea) {
        area.downloading = true
        area.region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                note(area, status)
                area.waiting = false
                if (status.isComplete && area.downloading) {
                    area.downloading = false
                    area.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    area.region.setObserver(null)
                    onSaved(area)
                }
                onChange()
            }
            override fun onError(error: OfflineRegionError) {
                // The download keeps trying by itself; say that it is waiting.
                if (error.reason == OfflineRegionError.REASON_CONNECTION || error.reason == OfflineRegionError.REASON_SERVER) {
                    if (!area.waiting) { area.waiting = true; onChange() }
                }
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {}
        })
        area.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    companion object {
        const val AMBIENT_BYTES = 200L * 1024 * 1024
        /** More tiles than this in one area is refused: split it. */
        const val MAX_TILES = 200_000L
        /** Space left free on the phone after a download. */
        const val KEEP_FREE_BYTES = 500L * 1024 * 1024
        /** Above this the size is shown as a warning. */
        const val LARGE_BYTES = 1_000_000_000L
        /** Style, fonts and sprites of one area, roughly. */
        const val FIXED_BYTES = 3_000_000L
    }
}
