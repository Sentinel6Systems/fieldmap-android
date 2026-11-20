package io.github.sentinel6systems.fieldmap.geo

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import java.io.File

/**
 * Lines by road or trail between two points, from the BRouter engine (MIT, compiled with the
 * app: see app/build.gradle.kts) and the routing files (.rd5, one per 5° square) in `segments`,
 * which come from the server with the offline areas. `profiles`: BRouter's lookups.dat and the
 * profiles (.brf) beside it. Slow (a second or so): never on the main thread.
 */
class Router(private val segments: File, private val profiles: File) {
    /**
     * The line (lat to lon) from a to b with the profile (RouteType.profile), from the road or
     * trail nearest a to the one nearest b; null when the routing files do not cover the way or
     * no way was found.
     */
    fun leg(a: Pair<Double, Double>, b: Pair<Double, Double>, profile: String): List<Pair<Double, Double>>? {
        val context = RoutingContext().apply { localFunction = File(profiles, "$profile.brf").path }
        val waypoints = listOf(a, b).mapIndexed { i, (lat, lon) ->
            OsmNodeNamed().apply {
                name = if (i == 0) "from" else "to"
                ilon = ((lon + 180) * 1e6 + 0.5).toInt()
                ilat = ((lat + 90) * 1e6 + 0.5).toInt()
            }
        }
        val engine = RoutingEngine(null, null, segments, waypoints, context).apply { quite = true }
        try { engine.doRun(TIMEOUT_MS) } catch (e: Exception) { return null }
        if (engine.errorMessage != null) return null
        return engine.foundTrack?.nodes?.map { (it.iLat / 1e6 - 90) to (it.iLon / 1e6 - 180) }?.takeIf { it.size >= 2 }
    }

    companion object {
        /** A leg that takes longer is given up (it is then drawn straight). */
        const val TIMEOUT_MS = 30_000L
    }
}
