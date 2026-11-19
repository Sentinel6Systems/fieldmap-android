package io.github.sentinel6systems.fieldmap.data

import java.io.File
import kotlin.random.Random

/** The markers, kept in one drawings file in the app's own storage. */
class MarkerStore(private val file: File) {
    var markers: List<Marker> = emptyList()
        private set

    init {
        markers = try {
            if (file.isFile) Drawings.fromGeoJson(file.readText(), Palette.DEFAULT, ::newId).markers else emptyList()
        } catch (e: Exception) {
            // An unreadable file is kept beside the new one, not written over.
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            emptyList()
        }
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + Random.nextInt(36 * 36 * 36).toString(36)

    fun get(id: String): Marker? = markers.firstOrNull { it.id == id }

    fun add(lat: Double, lon: Double, color: String): Marker {
        val m = Marker(newId(), lat, lon, Drawings.nextName(markers), color)
        markers = markers + m
        save()
        return m
    }

    fun update(m: Marker) {
        markers = markers.map { if (it.id == m.id) m else it }
        save()
    }

    fun delete(id: String) {
        markers = markers.filterNot { it.id == id }
        save()
    }

    /** The whole list at once: what a round with the box left of it. */
    fun replaceAll(list: List<Marker>) {
        if (list == markers) return
        markers = list
        save()
    }

    /** Adds the markers of a loaded file, leaving out the ones already here. Returns how many were added. */
    fun addAll(loaded: List<Marker>): Int {
        val have = markers.map { Triple(it.lat, it.lon, it.label) }.toMutableSet()
        val ids = markers.map { it.id }.toMutableSet()
        val fresh = ArrayList<Marker>()
        for (m in loaded) {
            if (!have.add(Triple(m.lat, m.lon, m.label))) continue
            // What comes from a file is this phone's own until the person shares it.
            val own = m.copy(shared = false, updated = "", by = "")
            fresh += if (ids.add(own.id)) own else own.copy(id = newId().also { ids.add(it) })
        }
        markers = markers + fresh
        save()
        return fresh.size
    }

    /** The markers as a file for the print tool and other software. */
    fun toGeoJson(): String = Drawings.toGeoJson(markers)

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Drawings.toGeoJson(markers, sharing = true))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
