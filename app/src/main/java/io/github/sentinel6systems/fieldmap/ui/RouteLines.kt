package io.github.sentinel6systems.fieldmap.ui

import android.os.Handler
import android.os.Looper
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.geo.Router
import io.github.sentinel6systems.fieldmap.geo.Routes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** How a leg of a route is drawn: along roads or trails, straight by its type, or straight for now. */
enum class LegState {
    ROUTED, STRAIGHT,
    /** Being worked out: straight until it is known. */
    WAITING,
    /** The routing files on the device do not cover it, or no way was found: straight (dashed). */
    NO_WAY,
}

/**
 * One leg of a route as it is drawn, from one of its points to the next (lat to lon). Along a road,
 * `head` / `tail`: the line's first / last part is the straight line between the point and the road
 * (Routes.roadLeg); `offRoad`: the leg ends off the road.
 */
class LegLine(val line: List<Pair<Double, Double>>, val state: LegState, val head: Boolean = false, val tail: Boolean = false, val offRoad: Boolean = false) {
    /** The parts to draw, each with true when dashed: a straight leg for want of a way, and the straight ends off the road. */
    val pieces: List<Pair<List<Pair<Double, Double>>, Boolean>> get() {
        if (state != LegState.ROUTED) return listOf(line to (state != LegState.STRAIGHT))
        val out = ArrayList<Pair<List<Pair<Double, Double>>, Boolean>>()
        if (head) out += line.subList(0, 2) to true
        out += line.subList(if (head) 1 else 0, line.size - if (tail) 1 else 0) to false
        if (tail) out += line.subList(line.size - 2, line.size) to true
        return out
    }
}

/**
 * The lines of routes' legs along roads and trails (geo/Router.kt), worked out one at a time on
 * a thread of their own and kept, also in a file, so that they are not worked out again at every
 * start. A leg is kept under what the router is asked (Routes.Ask): a change of a route's Route
 * Options, or of the items of the group it avoids, works its legs out again. Only a route's points travel between devices; each one draws the legs from its own
 * routing files, which all come from the same server. The points stay where they were put
 * (Routes.roadLeg). When BRouter finds no way (the road nearest a point cannot be reached), the
 * roads nearest the end point on the map are tried, then those nearest the start, then both: the
 * line along the roads, then straight to the point. Only then is the leg drawn straight. The same
 * rules as the map in the browser.
 */
class RouteLines(private val a: MainActivity) {
    private val file = File(a.filesDir, "route-lines.json")
    private val profiles = File(a.filesDir, "brouter")
    private val known = HashMap<String, List<Pair<Double, Double>>?>()     // null: no way
    private val asked = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var router: Router? = null
    /** On the main thread: the lines of the roads near a point that a Route Type can use (from the map's tiles). */
    var roadsNear: (Pair<Double, Double>, RouteType) -> List<List<Pair<Double, Double>>> = { _, _ -> emptyList() }

    init { load() }

    /** What the router is asked for a route's legs, with the markers and shapes there are now; null: Straight Line. */
    fun request(r: PlannedRoute): Routes.Ask? = Routes.ask(r, a.markers.markers, a.shapes.shapes)

    /** The legs of a route as they are drawn now. `ask`: work out the ones not known yet. */
    fun legs(r: PlannedRoute, ask: Boolean = true): List<LegLine> {
        val request = request(r)
        return r.points.zipWithNext().map { (p, q) ->
            if (request == null) return@map LegLine(listOf(p, q), LegState.STRAIGHT)
            val k = key(request, p, q)
            if (known.containsKey(k)) known[k]?.let { Routes.roadLeg(p, q, it) }?.let { LegLine(it.line, LegState.ROUTED, it.head, it.tail, it.offRoad) }
                ?: LegLine(listOf(p, q), LegState.NO_WAY)
            else {
                if (ask) ask(k, p, q, r.type, request)
                LegLine(listOf(p, q), LegState.WAITING)
            }
        }
    }

    /** Legs being worked out now or waiting their turn, of all routes. */
    val working get() = asked.size

    /** This route's legs being worked out now or waiting their turn (not "no route found": not known yet). */
    fun working(r: PlannedRoute): Int {
        val request = request(r) ?: return 0
        return r.points.zipWithNext().count { (p, q) -> key(request, p, q) in asked }
    }

    /** The whole line of a route as it is drawn now (for a file). */
    fun line(r: PlannedRoute): List<Pair<Double, Double>> = legs(r, ask = false).flatMapIndexed { i, l -> if (i == 0) l.line else l.line.drop(1) }

    /** New routing files came: legs are worked out again. */
    fun dataChanged() {
        known.clear()
        file.delete()
        a.routesChanged()
        a.panels.refresh()
    }

    private fun ask(k: String, p: Pair<Double, Double>, q: Pair<Double, Double>, type: RouteType, request: Routes.Ask) {
        if (!asked.add(k)) return
        fun done(line: List<Pair<Double, Double>>?) = main.post {
            asked.remove(k)
            known[k] = line
            saveSoon()
            a.routesChanged()
            a.panels.refresh()
        }
        // A line that ends too far from its point is no way either.
        fun tryLeg(from: Pair<Double, Double>, to: Pair<Double, Double>) =
            (try { router().leg(from, to, request.profile, request.params) } catch (e: Exception) { null })?.takeIf { Routes.roadLeg(p, q, it) != null }
        worker.execute {
            tryLeg(p, q)?.let { done(it); return@execute }
            // No way: the roads near both ends, from the map (main thread), then tried here.
            main.post {
                val nearQ = try { Routes.nearestRoadPoints(q, roadsNear(q, type)) } catch (e: Exception) { emptyList() }
                val nearP = try { Routes.nearestRoadPoints(p, roadsNear(p, type)) } catch (e: Exception) { emptyList() }
                worker.execute {
                    val line = nearQ.firstNotNullOfOrNull { tryLeg(p, it) }
                        ?: nearP.firstNotNullOfOrNull { tryLeg(it, q) }
                        ?: if (nearP.isNotEmpty() && nearQ.isNotEmpty()) tryLeg(nearP[0], nearQ[0]) else null
                    done(line)
                }
            }
        }
    }

    /** On the worker: BRouter's profiles from the app's assets, copied where BRouter reads them. */
    private fun router(): Router = router ?: run {
        profiles.mkdirs()
        for (name in a.assets.list("brouter").orEmpty()) {
            val to = File(profiles, name)
            a.assets.open("brouter/$name").use { input -> to.outputStream().use { input.copyTo(it) } }
        }
        Router(a.routingFiles.dir, profiles).also { router = it }
    }

    // Written a little after the last change, with the legs of the routes there are now.
    private val save = Runnable {
        val legs = JSONObject()
        val wanted = (a.markers.markers.mapNotNull { it.route } + listOfNotNull(a.routes.route))
            .flatMap { r -> request(r)?.let { request -> r.points.zipWithNext().map { (p, q) -> key(request, p, q) } }.orEmpty() }.toSet()
        for ((k, line) in known) if (k in wanted) legs.put(k, line?.let { l -> JSONArray().apply { for ((lat, lon) in l) put(JSONArray().put(lat).put(lon)) } } ?: JSONObject.NULL)
        try { file.writeText(JSONObject().put("version", 1).put("legs", legs).toString()) } catch (e: Exception) {}
    }

    /** The routes changed: the file is written again soon (with their legs only). */
    fun saveSoon() {
        main.removeCallbacks(save)
        main.postDelayed(save, 3000)
    }

    private fun load() {
        val legs = try { JSONObject(file.readText()).getJSONObject("legs") } catch (e: Exception) { return }
        for (k in legs.keys()) {
            val line = legs.optJSONArray(k)
            known[k] = line?.let { l -> (0 until l.length()).mapNotNull { i -> l.optJSONArray(i)?.let { it.optDouble(0) to it.optDouble(1) } } }
        }
    }

    companion object {
        fun key(request: Routes.Ask, p: Pair<Double, Double>, q: Pair<Double, Double>) =
            String.format(Locale.US, "%s|%.6f,%.6f|%.6f,%.6f", request.key, p.first, p.second, q.first, q.second)
    }
}
