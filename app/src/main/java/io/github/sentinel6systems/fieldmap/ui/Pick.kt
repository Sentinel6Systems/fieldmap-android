package io.github.sentinel6systems.fieldmap.ui

/**
 * What a tap on the map hit: the items under the finger, each once, in the order they were
 * found (markers, arrows, routes, tracks, shapes). One opens; several are offered to choose
 * from, as on the map in the browser.
 */
object Pick {
    const val MARKER = "marker"
    const val ARROW = "arrow"
    const val ROUTE = "route"
    const val TRACK = "track"
    const val SHAPE = "shape"

    /** Kind to id, each id once; `ignore`: ids that are not items (the route being drawn, the recording). */
    fun unique(hits: List<Pair<String, String>>, ignore: Set<String> = emptySet()): List<Pair<String, String>> =
        hits.filter { it.second !in ignore }.distinctBy { it.second }

    fun title(kind: String) = when (kind) { MARKER -> "Marker"; ARROW -> "Arrow"; ROUTE -> "Route"; TRACK -> "Track"; else -> "Shape" }

    /** A line of the list: "Gate · Marker · Hazards" (the kind for a name when it has none). */
    fun text(name: String, kind: String, group: String) = listOf(name.ifEmpty { kind }, kind, group).filter { it.isNotEmpty() }.joinToString(" · ")
}
