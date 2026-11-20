package io.github.sentinel6systems.fieldmap.ui

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.geo.Routes
import org.maplibre.android.geometry.LatLng
import kotlin.math.hypot

/**
 * New Route and Edit Route: the one way to draw a route and to change its points. While it is
 * open, a tap on the map adds a point at the end; a point dragged moves; the dot in the middle
 * of a leg dragged (or tapped) adds a point there; a point tapped is chosen, for Remove Point.
 * Undo takes back the last change, Done keeps the route, Back leaves it as it was.
 */
class RouteEditor(private val a: MainActivity) {
    /** The route's points while it is drawn (lat to lon); null when no route is being drawn. */
    var points: MutableList<Pair<Double, Double>>? = null
        private set
    /** The route being edited; null for a new one. */
    var editing: String? = null
        private set
    /** The chosen point (its index), for Remove Point. */
    var chosen: Int? = null
        private set
    private val history = ArrayList<Pair<List<Pair<Double, Double>>, Int?>>()

    val active get() = points != null
    val canUndo get() = history.isNotEmpty()
    val changed get() = history.isNotEmpty()

    /** Draws a new route (id null) or changes the points of one. */
    fun start(id: String?) {
        val route = id?.let { a.markers.get(it) }?.route
        if (id != null && route == null) return
        points = route?.points.orEmpty().toMutableList()
        editing = id
        chosen = null
        history.clear()
        drag = null
        a.routesChanged()
        a.panels.show(Screen.ROUTE)
    }

    /** A tap on the map, not on a point: a new last point there. */
    fun tap(at: LatLng) {
        val list = points ?: return
        if (list.size >= Drawings.MAX_ROUTE_POINTS) {
            a.toast("A route has at most ${Drawings.MAX_ROUTE_POINTS} points.")
            return
        }
        remember()
        list += at.latitude to at.longitude
        chosen = null
        a.b.map.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        changedNow()
    }

    fun removeChosen() {
        val list = points ?: return
        val i = chosen ?: return
        remember()
        list.removeAt(i)
        chosen = null
        changedNow()
    }

    fun undo() {
        val list = points ?: return
        val (before, was) = history.removeLastOrNull() ?: return
        list.clear()
        list += before
        chosen = was?.takeIf { it < list.size }
        changedNow()
    }

    /** Done: the route is kept (a new one is made), and its panel opens. */
    fun finish() {
        val list = points?.toList()?.takeIf { it.size >= 2 } ?: return
        val id = editing
        close()
        if (id == null) {
            val m = a.markers.addRoute(list, a.prefs.color)
            a.markersChanged()
            a.openMarker(m.id, fromList = true)
        } else {
            a.markers.get(id)?.let { a.updateMarker(it.withPoints(list)) }
            a.panels.show(Screen.MARKER)
        }
    }

    /** Leaving for `next` without Done: asks first when something would be lost. */
    fun leave(next: Screen) {
        if (!changed) { close(); a.panels.show(next); return }
        AlertDialog.Builder(a)
            .setTitle("Discard Changes?")
            .setMessage(if (editing == null) "This route is not saved." else "The changes to this route are not saved.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Discard") { _, _ -> close(); a.panels.show(next) }
            .show()
    }

    private fun close() {
        points = null
        chosen = null
        history.clear()
        drag = null
        a.routesChanged()
    }

    private fun remember() {
        points?.let { history += it.toList() to chosen }
    }

    private fun changedNow() {
        a.routesChanged()
        a.panels.rebuild(Screen.ROUTE)
    }

    /* ---------- a finger on a point or on the middle of a leg ---------- */

    /** What a finger holds: a point, or the middle of the leg after point `index`. */
    private class Drag(val middle: Boolean, var index: Int, val x: Float, val y: Float) { var moved = false }
    private var drag: Drag? = null

    /** Watches a finger on the map while a route is drawn. Returns true while it holds a point (the map then does not see it). */
    fun touch(e: MotionEvent): Boolean {
        val list = points ?: return false
        val map = a.map ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = grab(list, e.x, e.y)
                return drag != null
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drag ?: return false
                if (!d.moved) {
                    if (hypot(e.x - d.x, e.y - d.y) < a.dp(8)) return true
                    d.moved = true
                    remember()
                    if (d.middle) {
                        if (list.size >= Drawings.MAX_ROUTE_POINTS) {
                            history.removeLastOrNull()
                            drag = null
                            a.toast("A route has at most ${Drawings.MAX_ROUTE_POINTS} points.")
                            return true
                        }
                        list.add(d.index + 1, list[d.index])
                        d.index++
                    }
                    chosen = null
                }
                val at = map.projection.fromScreenLocation(PointF(e.x, e.y))
                list[d.index] = at.latitude to at.longitude
                a.routesChanged()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val d = drag ?: return false
                drag = null
                if (!d.moved) {
                    // A tap: on a point, it is chosen (or no longer); on the middle of a leg, a point there.
                    if (d.middle) {
                        if (list.size >= Drawings.MAX_ROUTE_POINTS) { a.toast("A route has at most ${Drawings.MAX_ROUTE_POINTS} points."); return true }
                        remember()
                        list.add(d.index + 1, Routes.middle(list[d.index], list[d.index + 1]))
                        chosen = null
                    } else {
                        chosen = if (chosen == d.index) null else d.index
                    }
                }
                a.b.map.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                changedNow()
                return true
            }
            else -> {                               // a second finger, or the system took the touch
                val d = drag ?: return false
                drag = null
                if (d.moved) changedNow()
                return true
            }
        }
    }

    /** The point or leg middle under a finger at (x, y), the nearest one, or null. */
    private fun grab(list: List<Pair<Double, Double>>, x: Float, y: Float): Drag? {
        val projection = a.map?.projection ?: return null
        fun far(p: Pair<Double, Double>) = projection.toScreenLocation(LatLng(p.first, p.second)).let { hypot(it.x - x, it.y - y) }
        val point = list.indices.minByOrNull { far(list[it]) }?.takeIf { far(list[it]) < a.dp(26) }
        if (point != null) return Drag(false, point, x, y)
        val middle = (0 until list.size - 1).minByOrNull { far(Routes.middle(list[it], list[it + 1])) }
            ?.takeIf { far(Routes.middle(list[it], list[it + 1])) < a.dp(22) }
        return middle?.let { Drag(true, it, x, y) }
    }
}
