package io.github.sentinel6systems.fieldmap.geo

import io.github.sentinel6systems.fieldmap.data.Drawings

/**
 * Edit Route's changes to a route's points (lat to lon), as on the map in the browser. Each one
 * gives the new list, or null when the route may not change so: a route has at most
 * Drawings.MAX_ROUTE_POINTS points, and Remove Points keeps MIN_POINTS. The list given stays as it is.
 */
object RouteEdit {
    /** The points Remove Points leaves at least. */
    const val MIN_POINTS = 2

    /** Add Points at Start (`atStart`) or at End: p before the first point or after the last. */
    fun add(points: List<Pair<Double, Double>>, p: Pair<Double, Double>, atStart: Boolean,
            max: Int = Drawings.MAX_ROUTE_POINTS): List<Pair<Double, Double>>? =
        if (points.size >= max) null else if (atStart) listOf(p) + points else points + p

    /** The dot of leg `leg` (from point `leg` to the next) dropped at p: a new point there. */
    fun insert(points: List<Pair<Double, Double>>, leg: Int, p: Pair<Double, Double>,
               max: Int = Drawings.MAX_ROUTE_POINTS): List<Pair<Double, Double>>? =
        if (points.size >= max || leg !in 0 until points.size - 1) null
        else points.toMutableList().apply { add(leg + 1, p) }

    /** Point i dropped at p. */
    fun move(points: List<Pair<Double, Double>>, i: Int, p: Pair<Double, Double>): List<Pair<Double, Double>>? =
        if (i !in points.indices) null else points.toMutableList().apply { this[i] = p }

    /** Point i removed (Remove Points). */
    fun remove(points: List<Pair<Double, Double>>, i: Int): List<Pair<Double, Double>>? =
        if (points.size <= MIN_POINTS || i !in points.indices) null else points.toMutableList().apply { removeAt(i) }

    /**
     * While point i (or the dot of leg i, `insert`) is dragged, the points the dashed preview
     * joins to the finger: the one before it and the one after it.
     */
    fun neighbors(points: List<Pair<Double, Double>>, i: Int, insert: Boolean): List<Pair<Double, Double>> =
        if (insert) listOfNotNull(points.getOrNull(i), points.getOrNull(i + 1))
        else listOfNotNull(points.getOrNull(i - 1), points.getOrNull(i + 1))
}

/** Undo: what was there before each change, the last one back first. */
class History<T> {
    private val steps = ArrayList<T>()
    val canUndo get() = steps.isNotEmpty()

    fun remember(before: T) { steps += before }

    /** What was there before the last change, or null when there is nothing to undo. */
    fun undo(): T? = steps.removeLastOrNull()

    fun clear() = steps.clear()
}
