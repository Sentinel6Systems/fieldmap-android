package io.github.sentinel6systems.fieldmap.ui

import android.os.Handler
import android.os.Looper
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.geo.Router
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

/** One leg of a route as it is drawn, from one of its points to the next (lat to lon). */
class LegLine(val line: List<Pair<Double, Double>>, val state: LegState)

/**
 * The lines of routes' legs along roads and trails (geo/Router.kt), worked out one at a time on
 * a thread of their own and kept, also in a file, so that they are not worked out again at every
 * start. Only a route's points travel between devices; each one draws the legs from its own
 * routing files, which all come from the same server.
 */
class RouteLines(private val a: MainActivity) {
    private val file = File(a.filesDir, "route-lines.json")
    private val profiles = File(a.filesDir, "brouter")
    private val known = HashMap<String, List<Pair<Double, Double>>?>()     // null: no way
    private val asked = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var router: Router? = null
    /** A leg's line was worked out: from, to, type, line (null: no way). */
    var onLeg: (Pair<Double, Double>, Pair<Double, Double>, RouteType, List<Pair<Double, Double>>?) -> Unit = { _, _, _, _ -> }

    init { load() }

    /** The legs of a route as they are drawn now. `ask`: work out the ones not known yet. */
    fun legs(r: PlannedRoute, ask: Boolean = true): List<LegLine> = r.points.zipWithNext().map { (p, q) ->
        val profile = r.type.profile ?: return@map LegLine(listOf(p, q), LegState.STRAIGHT)
        val k = key(r.type, p, q)
        if (known.containsKey(k)) known[k]?.let { LegLine(it, LegState.ROUTED) } ?: LegLine(listOf(p, q), LegState.NO_WAY)
        else {
            if (ask) ask(k, p, q, r.type, profile)
            LegLine(listOf(p, q), LegState.WAITING)
        }
    }

    /** The whole line of a route as it is drawn now (for a file). */
    fun line(r: PlannedRoute): List<Pair<Double, Double>> = legs(r, ask = false).flatMapIndexed { i, l -> if (i == 0) l.line else l.line.drop(1) }

    /** A leg's line is also the one between its ends where they were moved to (onto the road): no need to work it out again. */
    fun alias(type: RouteType, p: Pair<Double, Double>, q: Pair<Double, Double>, line: List<Pair<Double, Double>>) {
        known[key(type, p, q)] = line
        saveSoon()
    }

    /** New routing files came: legs are worked out again. */
    fun dataChanged() {
        known.clear()
        file.delete()
        a.routesChanged()
        a.panels.refresh()
    }

    private fun ask(k: String, p: Pair<Double, Double>, q: Pair<Double, Double>, type: RouteType, profile: String) {
        if (!asked.add(k)) return
        worker.execute {
            val line = try { router().leg(p, q, profile) } catch (e: Exception) { null }
            main.post {
                asked.remove(k)
                known[k] = line
                saveSoon()
                onLeg(p, q, type, line)
                a.routesChanged()
                a.panels.refresh()
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
        val wanted = (a.markers.markers.mapNotNull { it.route } + listOfNotNull(a.routes.points?.let { PlannedRoute(it.toList(), type = a.routes.type) }))
            .flatMap { r -> r.points.zipWithNext().map { (p, q) -> key(r.type, p, q) } }.toSet()
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
        fun key(type: RouteType, p: Pair<Double, Double>, q: Pair<Double, Double>) =
            String.format(Locale.US, "%s|%.6f,%.6f|%.6f,%.6f", type.key, p.first, p.second, q.first, q.second)
    }
}
