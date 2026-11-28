package io.github.sentinel6systems.fieldmap.ui

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.geo.History
import io.github.sentinel6systems.fieldmap.geo.RouteEdit
import io.github.sentinel6systems.fieldmap.geo.Routes
import org.maplibre.android.geometry.LatLng
import kotlin.math.hypot

/**
 * New Route and Edit Route: the one way to draw a route and to change its points, as on the map
 * in the browser. While it is open, a point dragged, or the small dot halfway along a leg as it
 * is drawn, shows only a dashed line from the points next to it to the finger; when the finger
 * is lifted, the point moves (or a new point goes on that leg), and only then are its legs worked
 * out. A tap on the map does nothing, unless Add Points at Start or at End is on: then each tap
 * is a point there. With Remove Points on, a point tapped goes (a route keeps two). At most one
 * of the three is on; a new route starts with Add Points at End. Undo takes back the last change,
 * Done keeps the route, Back leaves it as it was. Its Route Type says how the legs go, and its
 * Route Options what the router is asked for them; a point stays where it was put, and a point
 * off the road or trail is joined to it by a straight dashed line (RouteLines).
 */
class RouteEditor(private val a: MainActivity) {
    /** What a tap on the map does: nothing (null), a point before the start or after the end, or a point removed. */
    enum class Tool { START, END, REMOVE }

    /** The route's points while it is drawn (lat to lon); null when no route is being drawn. */
    var points: MutableList<Pair<Double, Double>>? = null
        private set
    /** The route being edited; null for a new one. */
    var editing: String? = null
        private set
    /** Add Points at Start, Add Points at End or Remove Points, whichever is on. */
    var tool: Tool? = null
        private set
    var type = RouteType.STRAIGHT
        private set
    /** Its Route Options (keys of Routes.OPTIONS and DIFFICULTY), the group whose items it avoids ("": none), its Way (0 to 3). */
    private var options = emptyList<String>()
    private var avoid = ""
    private var alternative = 0
    private class Step(val points: List<Pair<Double, Double>>, val type: RouteType,
                       val options: List<String>, val avoid: String, val alternative: Int)
    private val history = History<Step>()

    /** The route as it is drawn now. */
    val route get() = points?.let { PlannedRoute(it.toList(), type = type, options = options, avoid = avoid, alternative = alternative) }

    val active get() = points != null
    val canUndo get() = history.canUndo
    val changed get() = history.canUndo

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
        // A new route has no points yet: each tap is the next one, until Add Points at End is switched off.
        tool = if (id == null) Tool.END else null
        history.clear()
        drag = null
        a.routesChanged()
        a.panels.show(Screen.ROUTE)
    }

    /** Add Points at Start, Add Points at End or Remove Points pressed: on, or off when it was on. */
    fun switchTool(t: Tool) {
        if (points == null) return
        tool = if (tool == t) null else t
        a.panels.rebuild(Screen.ROUTE)
    }

    /** What the panel says to do now. */
    val hint: String get() {
        val n = points?.size ?: 0
        val name = editing?.let { a.markers.get(it)?.label?.ifEmpty { null } } ?: "the route"
        val of = "$n of ${Drawings.MAX_ROUTE_POINTS} points."
        val full = if (n >= Drawings.MAX_ROUTE_POINTS) " The route is full." else ""
        return when (tool) {
            Tool.START -> "Tap the map to add points before the start of $name. $of$full"
            Tool.END -> "Tap the map to add points after the end of $name. $of$full"
            Tool.REMOVE -> "Tap the points to remove (a route keeps two). $of"
            null -> "Drag a point to move it, or drag the small dot on a leg to add a point there. $of$full"
        }
    }

    /** A tap on the map, not on a point: with Add Points on, a new point at the start or the end there. */
    fun tap(at: LatLng) {
        val list = points ?: return
        val start = when (tool) { Tool.START -> true; Tool.END -> false; else -> return }
        val now = RouteEdit.add(list, at.latitude to at.longitude, start) ?: return full()
        change(now)
    }

    fun undo() {
        val list = points ?: return
        val step = history.undo() ?: return
        list.clear()
        list += step.points
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
        tool = null
        history.clear()
        drag = null
        a.preview(emptyList())
        a.routesChanged()
    }

    private fun remember() {
        points?.let { history.remember(Step(it.toList(), type, options, avoid, alternative)) }
    }

    /** The points are now `now`: kept for Undo, and the legs worked out. */
    private fun change(now: List<Pair<Double, Double>>) {
        val list = points ?: return
        remember()
        list.clear()
        list += now
        a.b.map.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        changedNow()
    }

    private fun full() = a.toast("A route has at most ${Drawings.MAX_ROUTE_POINTS} points.")

    private fun changedNow() {
        a.routesChanged()
        a.panels.rebuild(Screen.ROUTE)
    }

    /* ---------- a finger on a point or on the dot of a leg ---------- */

    /** What a finger holds: point `index`, or the dot of the leg after point `index`. */
    private class Drag(val dot: Boolean, val index: Int, val x: Float, val y: Float) { var moved = false }
    private var drag: Drag? = null

    /**
     * Watches a finger on the map while a route is drawn. Returns true while it holds a point or a
     * dot (the map then does not see it). The route does not change until the finger is lifted.
     */
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
                    if (d.dot && list.size >= Drawings.MAX_ROUTE_POINTS) { drag = null; full(); return true }
                    d.moved = true
                }
                val at = map.projection.fromScreenLocation(PointF(e.x, e.y))
                a.preview(RouteEdit.neighbors(list, d.index, d.dot).map { listOf(LatLng(it.first, it.second), at) })
                return true
            }
            MotionEvent.ACTION_UP -> {
                val d = drag ?: return false
                drag = null
                a.preview(emptyList())
                val at = map.projection.fromScreenLocation(PointF(e.x, e.y)).let { it.latitude to it.longitude }
                if (d.moved) {
                    // Let go: the point moves, or a new one goes on the leg; now its legs are worked out.
                    val now = if (d.dot) RouteEdit.insert(list, d.index, at) else RouteEdit.move(list, d.index, at)
                    if (now == null) full() else change(now)
                } else when (tool) {
                    // A tap: with Remove Points, a point goes; with Add Points, a point there all the same
                    // (a finger is wide). Otherwise a point or a dot tapped does nothing.
                    Tool.REMOVE -> if (!d.dot) RouteEdit.remove(list, d.index)?.let(::change)
                        ?: a.toast("A route keeps at least ${RouteEdit.MIN_POINTS} points.")
                    Tool.START, Tool.END -> tap(LatLng(at.first, at.second))
                    null -> {}
                }
                return true
            }
            else -> {                               // a second finger, or the system took the touch
                drag ?: return false
                drag = null
                a.preview(emptyList())
                return true
            }
        }
    }

    /** The point or leg dot under a finger at (x, y), the nearest one, or null. Points come first. */
    private fun grab(list: List<Pair<Double, Double>>, x: Float, y: Float): Drag? {
        val projection = a.map?.projection ?: return null
        fun far(p: Pair<Double, Double>) = projection.toScreenLocation(LatLng(p.first, p.second)).let { hypot(it.x - x, it.y - y) }
        val point = list.indices.minByOrNull { far(list[it]) }?.takeIf { far(list[it]) < a.dp(26) }
        if (point != null) return Drag(false, point, x, y)
        val dots = dots()
        val dot = dots.indices.minByOrNull { far(dots[it]) }?.takeIf { far(dots[it]) < a.dp(22) }
        return dot?.let { Drag(true, it, x, y) }
    }

    /** The dot of each leg: halfway along it as it is drawn (along the road when it follows one, with its dashed ends). */
    fun dots(): List<Pair<Double, Double>> =
        route?.let { r -> a.routeLines.legs(r, ask = false).map { Routes.halfway(it.line) } }.orEmpty()
}
