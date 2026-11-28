package io.github.sentinel6systems.fieldmap.data

import org.json.JSONArray
import org.json.JSONObject

/** A group as the Markers list shows it: hidden on the map or not, folded or not ("" is Ungrouped). */
data class GroupState(val name: String, val hidden: Boolean = false, val closed: Boolean = false)

/**
 * Groups, as on the map in the browser (print/src/main.js): an item's `group` names the one it
 * is in; the list of groups keeps the order they were first seen in, and what each one shows.
 * The app's own: no group of its own is needed for anything but the list.
 */
object Groups {
    /** The groups kept, then any that an item names and that is not kept yet (in the items' order). */
    fun known(kept: List<GroupState>, items: List<String>): List<GroupState> {
        val out = kept.filter { it.name.isNotEmpty() }.distinctBy { it.name }.toMutableList()
        for (name in items) if (name.isNotEmpty() && out.none { it.name == name }) out += GroupState(name)
        return out
    }

    /** The group a file from other software goes into: its name without the extension and a date at the end ("Water Points-2026-11-20.geojson": "Water Points"). */
    fun fromFileName(file: String): String = Drawings.groupName(
        file.replace(Regex("\\.[^.]*$"), "").replace(Regex("[-_ ]?\\d{4}-\\d{2}-\\d{2}$"), "").replace(Regex("[-_]+"), " "),
    )

    /** What a file loaded into the app adds: a file of the app or the print tool keeps its items' groups; one from other software is a group named after the file. */
    fun imported(loaded: Drawings.Loaded, file: String): List<Marker> {
        if (loaded.own) return loaded.markers
        val group = fromFileName(file)
        return loaded.markers.map { if (it.group.isEmpty()) it.copy(group = group) else it }
    }

    /** The markers with the ones of group `from` in `to` instead. */
    fun renamed(markers: List<Marker>, from: String, to: String): List<Marker> = markers.map { if (it.group == from) it.copy(group = to) else it }

    /**
     * A shared shape's feature, as the server keeps it, in another group ("" for none): changed
     * here by this phone (`by`) at `updated` (UTC, ISO 8601), with its id on the feature.
     */
    fun moveShape(feature: String, id: String, to: String, by: String, updated: String): String {
        val f = JSONObject(feature).put("id", id)
        val p = f.optJSONObject("properties") ?: JSONObject().also { f.put("properties", it) }
        val fm = p.optJSONObject("fieldmap") ?: JSONObject().also { p.put("fieldmap", it) }
        if (to.isEmpty()) fm.remove("group") else fm.put("group", to)
        fm.put("id", id).put("shared", true)
        p.put("by", by).put("updated", updated)
        return f.toString()
    }

    fun write(groups: List<GroupState>): String = JSONArray().apply {
        for (g in groups) put(JSONObject().put("name", g.name).put("hidden", g.hidden).put("closed", g.closed))
    }.toString()

    fun read(text: String?): List<GroupState> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { GroupState(it.optString("name"), it.optBoolean("hidden"), it.optBoolean("closed")) }
    } catch (e: Exception) { emptyList() }
}
