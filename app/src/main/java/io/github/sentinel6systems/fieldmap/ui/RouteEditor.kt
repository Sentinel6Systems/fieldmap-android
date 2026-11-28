package io.github.sentinel6systems.fieldmap.ui

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.geo.Routes
import org.maplibre.android.geometry.LatLng
import kotlin.math.hypot

/**
 * New Route and Edit Route: the one way to draw a route and to change its points. While it is
 * open, a tap on the map adds a point at the end; a point dragged moves; the dot in the middle
 * of a leg dragged (or tapped) adds a point there; a point tapped is chosen, for Remove Point.
 * Undo takes back the last change, Done keeps the route, Back leaves it as it was. Its Route
 * Type says how the legs go, and its Route Options what the router is asked for them (as on the
 * map in the browser); a point stays where it was put, and a point off the road or trail is
 * joined to it by a straight dashed line (RouteLines).
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
    var type = RouteType.STRAIGHT
        private set
    /** Its Route Options (keys of Routes.OPTIONS and DIFFICULTY), the group whose items it avoids ("": none), its Way (0 to 3). */
    private var options = emptyList<String>()
    private var avoid = ""
    private var alternative = 0
    private class Step(val points: List<Pair<Double, Double>>, val chosen: Int?, val type: RouteType,
                       val options: List<String>, val avoid: String, val alternative: Int)
    private val history = ArrayList<Step>()

    /** The route as it is drawn now. */
    val route get() = points?.let { PlannedRoute(it.toList(), type = type, options = options, avoid = avoid, alternative = alternative) }
    /** A finger moves a point: its legs are not worked out until it is lifted. */
    val dragging get() = drag?.moved == true

    val active get() = points != null
    val canUndo get() = history.isNotEmpty()
    val changed get() = history.isNotEmpty()

    /** Draws a new route (id null) or changes the points of one. */
    fun start(id: String?) {
        val route = id?.let { a.markers.get(it) }?.route
        if (id != null && route == null) return
        points = route?.points.orEmpty().toMutableList()
        type = route?.type ?: a.prefs.routeType
        options = route?.options.orEmpty()
        avoid = route?.avoid.orEmpty()
        alternative = route?.alternative ?: 0
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
        val step = history.removeLastOrNull() ?: return
        list.clear()
        list += step.points
        chosen = step.chosen?.takeIf { it < list.size }
        type = step.type
        options = step.options
        avoid = step.avoid
        alternative = step.alternative
        changedNow()
    }

    /** Another Route Type; new routes start with the last one used. Route Options the new type does not have are dropped. */
    fun changeType(to: RouteType) {
        if (points == null || to == type) return
        remember()
        type = to
        options = Routes.cleanOptions(to, options)
        a.prefs.routeType = to
        changedNow()
    }

    /** A Route Option switched on or off. */
    fun setOption(key: String, on: Boolean) {
        if (points == null || (key in options) == on) return
        remember()
        options = Routes.cleanOptions(type, if (on) options + key else options - key)
        changedNow()
    }

    /** Trail Difficulty: "easy", "hard", or "" (Moderate). */
    fun setDifficulty(key: String) {
        val now = Routes.cleanOptions(type, options.filter { it != "easy" && it != "hard" } + listOfNotNull(key.ifEmpty { null }))
        if (points == null || now == options) return
        remember()
        options = now
        changedNow()
    }

    /** Avoid Areas In: a group's name, or "" for No Group. */
    fun setAvoid(group: String) {
        if (points == null || group == avoid) return
        remember()
        avoid = group
        changedNow()
    }

    /** Way: 0 the best, 1 to 3 the router's second to fourth. */
    fun setWay(n: Int) {
        if (points == null || n == alternative || n !in Routes.WAYS.indices) return
        remember()
        alternative = n
        changedNow()
    }

    /** Done: the route is kept (a new one is made), and its panel opens. */
    fun finish() {
        val list = points?.toList()?.takeIf { it.size >= 2 } ?: return
        val id = editing
        close()
        val type = type
        val (options, avoid, alternative) = Triple(options, avoid, alternative)
        if (id == null) {
            val m = a.markers.addRoute(list, a.prefs.color, type, options, avoid, alternative)
            a.markersChanged()
            a.openMarker(m.id, fromList = true)
        } else {
            a.markers.get(id)?.let { m ->
                m.route?.let { a.updateMarker(m.copy(route = it.copy(type = type, options = options, avoid = avoid, alternative = alternative)).withPoints(list)) }
            }
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
        points?.let { history += Step(it.toList(), chosen, type, options, avoid, alternative) }
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
                        list.add(d.index + 1, middleOf(d.index))
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
        val middle = (0 until list.size - 1).minByOrNull { far(middleOf(it)) }?.takeIf { far(middleOf(it)) < a.dp(22) }
        return middle?.let { Drag(true, it, x, y) }
    }

    /** The middle of leg i as it is drawn (along the road when it follows one). */
    fun middleOf(i: Int): Pair<Double, Double> {
        val legs = route?.let { a.routeLines.legs(it, ask = false) } ?: return 0.0 to 0.0
        return Routes.along(legs[i].line, 0.5)
    }
}
