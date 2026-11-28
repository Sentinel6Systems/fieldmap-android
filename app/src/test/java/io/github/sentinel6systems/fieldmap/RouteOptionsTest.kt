package io.github.sentinel6systems.fieldmap

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingParamCollector
import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.data.Shape
import io.github.sentinel6systems.fieldmap.data.ShapeStore
import io.github.sentinel6systems.fieldmap.geo.Routes
import io.github.sentinel6systems.fieldmap.mesh.MeshCodec
import io.github.sentinel6systems.fieldmap.share.Pending
import io.github.sentinel6systems.fieldmap.share.ShareJson
import io.github.sentinel6systems.fieldmap.share.ShareMerge
import io.github.sentinel6systems.fieldmap.share.ShareState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route Options, Avoid Areas and Way: kept, shared and asked of the router as on the map in the browser. */
class RouteOptionsTest {
    private var n = 0
    private fun id() = "new${++n}"

    private val points = listOf(39.70 to -105.20, 39.75 to -105.15, 39.80 to -105.10)
    private fun route(r: PlannedRoute, group: String = "") =
        Marker("lq3k9zrte", 0.0, 0.0, "R1", "#1f5fa8", shared = true, updated = "2027-01-15T08:00:00Z", by = "phone-aaaa", route = r, group = group)
            .withPoints(r.points)
    private val driving = route(PlannedRoute(points, 25, RouteType.DRIVING, listOf("avoid-highways", "fuel-saver"), "Hazards", 2), group = "Plan")

    @Test fun optionsGoInTheFileAsTheMapInTheBrowserWritesThem() {
        val fm = JSONObject(Drawings.toGeoJson(listOf(driving))).getJSONArray("features").getJSONObject(0)
            .getJSONObject("properties").getJSONObject("fieldmap")
        assertEquals("avoid-highways fuel-saver", fm.getString("options"))
        assertEquals("Hazards", fm.getString("avoid"))
        assertEquals(2, fm.getInt("alternative"))
        assertEquals("Plan", fm.getString("group"))
        // Nothing set: nothing written.
        val plain = JSONObject(Drawings.toGeoJson(listOf(route(PlannedRoute(points, 2, RouteType.WALKING))))).getJSONArray("features")
            .getJSONObject(0).getJSONObject("properties").getJSONObject("fieldmap")
        assertFalse(plain.has("options") || plain.has("avoid") || plain.has("alternative") || plain.has("group"))
    }

    @Test fun optionsGroupsAndBicycleLoadBackTheSame() {
        val list = listOf(
            driving,
            route(PlannedRoute(points, 2, RouteType.WALKING, listOf("avoid-wet", "hard"), "", 0)).copy(id = "lq3k9zrt2"),
            route(PlannedRoute(points, 10, RouteType.BICYCLE, emptyList(), "Hazards", 3)).copy(id = "lq3k9zrt3"),
            Marker("lq3k9zmk1", 39.71, -105.21, "Gate", "#d7263d", group = "Hazards"),
            Marker("lq3k9zar1", 39.71, -105.21, "A1", "#d7263d", endLat = 39.72, endLon = -105.22, group = "Hazards"),
        )
        val loaded = Drawings.fromGeoJson(Drawings.toGeoJson(list, sharing = true), "#000000", ::id)
        assertEquals(list, loaded.markers)
    }

    @Test fun optionsGroupsAndBicycleComeBackFromTheServer() {
        val list = listOf(driving, Marker("lq3k9zmk1", 39.71, -105.21, "Gate", "#d7263d", shared = true, updated = "2027-01-15T08:00:00Z",
            by = "phone-aaaa", group = "Hazards"))
        val text = JSONObject().put("rev", 3).put("full", true).put("markers", JSONArray().apply { for (m in list) put(JSONObject(ShareJson.marker(m))) })
        val state = ShareJson.state(text.toString())!!
        assertEquals(list, state.markers)
    }

    @Test fun whatDoesNotApplyIsDropped() {
        val text = """{"type":"FeatureCollection","fieldmap":{"format":"fieldmap-drawings","version":1,"group":"Whole   File"},"features":[
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-105.2,39.7],[-105.1,39.8]]},
           "properties":{"fieldmap":{"kind":"route","label":"W","color":"#1f5fa8","routing":"walking","speedMph":2,
             "options":"hard avoid-highways easy avoid-steps bogus","avoid":"  Danger \u00a0\n  Areas ","alternative":5}}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-105.2,39.7],[-105.1,39.8]]},
           "properties":{"fieldmap":{"kind":"route","label":"B","color":"#1f5fa8","routing":"bicycle","speedMph":10,
             "options":["avoid-highways","easy"],"avoid":"${"x".repeat(40)}","alternative":2.0,"group":"Own"}}}]}"""
        val (walk, bike) = Drawings.fromGeoJson(text, "#000000", ::id).markers
        assertEquals(listOf("avoid-steps", "easy"), walk.route!!.options)
        assertEquals("Danger Areas", walk.route!!.avoid)
        assertEquals("only 1 to 3", 0, walk.route!!.alternative)
        assertEquals("the file's group", "Whole File", walk.group)
        assertEquals(RouteType.BICYCLE, bike.route!!.type)
        assertEquals(10, bike.route!!.mph)
        assertEquals(emptyList<String>(), bike.route!!.options)
        assertEquals(30, bike.route!!.avoid.length)
        assertEquals(2, bike.route!!.alternative)
        assertEquals("Own", bike.group)
        // Another Route Type: only its own options stay.
        assertEquals(emptyList<String>(), Routes.cleanOptions(RouteType.DRIVING, listOf("avoid-steps", "easy")))
        assertEquals(listOf("dirt-tracks", "avoid-towns"), Routes.cleanOptions(RouteType.DRIVING, listOf("avoid-towns", "dirt-tracks", "hard")))
        assertEquals(emptyList<String>(), Routes.cleanOptions(RouteType.STRAIGHT, listOf("avoid-towns")))
    }

    @Test fun overTheMeshOptionsWayAvoidAndBicycleComeBack() {
        for (r in listOf(
            driving,
            route(PlannedRoute(points, 2, RouteType.WALKING, listOf("avoid-steps", "avoid-wet", "easy"))),
            route(PlannedRoute(points, 2, RouteType.WALKING, listOf("hard"))),
            route(PlannedRoute(points, 2, RouteType.WALKING, listOf("avoid-wet", "any-road", "easy"))),
            route(PlannedRoute(points, 10, RouteType.BICYCLE, emptyList(), "Hazards", 1)),
        )) {
            val back = MeshCodec.change(MeshCodec.decode(MeshCodec.marker(r)!!)!!.route!!)!!.marker!!
            // The mesh carries no groups.
            assertEquals(r.copy(group = ""), back)
        }
        val raw = MeshCodec.decode(MeshCodec.marker(driving)!!)!!.route!!
        assertEquals(1 or 8, raw.options)
        assertEquals(1, raw.type)
        // Bits of another type's options are dropped, as from a file; Easy when both difficulties.
        val odd = MeshCodec.change(raw.copy(type = 2, options = 1 or 16 or 64 or 128, alternative = 7))!!.marker!!.route!!
        assertEquals(listOf("avoid-steps", "easy"), odd.options)
        assertEquals(0, odd.alternative)
        assertEquals(RouteType.STRAIGHT, MeshCodec.change(raw.copy(type = 9))!!.marker!!.route!!.type)
    }

    @Test fun aRouteThatDoesNotFitOnePacketLeavesOutItsAvoidedGroupFirst() {
        fun far(count: Int) = route(PlannedRoute((0 until count).map { 39.0 + 0.05 * it to -105.9 + 0.07 * (it % 2) }, 25, RouteType.DRIVING,
            listOf("avoid-highways"), "G".repeat(30), 1)).copy(label = "L".repeat(30))
        val heard = (2..30).map { count -> MeshCodec.marker(far(count))?.let { MeshCodec.change(MeshCodec.decode(it)!!.route!!)!!.marker!! } }
        assertTrue(heard.all { it == null || it.route!!.points.size >= 2 })
        val noAvoid = heard.filterNotNull().first { it.route!!.avoid.isEmpty() }
        assertEquals("the name stays while the group goes", "L".repeat(30), noAvoid.label)
        assertEquals(listOf("avoid-highways"), noAvoid.route!!.options)
        val noLabel = heard.filterNotNull().firstOrNull { it.label.isEmpty() }
        if (noLabel != null) assertTrue(noLabel.route!!.points.size > noAvoid.route!!.points.size)
        for (bytes in (2..30).mapNotNull { MeshCodec.marker(far(it)) }) assertTrue(bytes.size <= MeshCodec.MAX_PAYLOAD)
    }

    private val items = listOf(
        driving,
        Marker("m1", 39.70123456, -105.20123456, "Gate", "#d7263d", group = "Hazards"),
        Marker("m2", 39.9, -105.9, "Elsewhere", "#d7263d", group = "Other"),
        Marker("a1", 39.71, -105.21, "A1", "#d7263d", endLat = 39.715, endLon = -105.205, group = "Hazards"),
        // A route in the group is not an obstacle.
        route(PlannedRoute(points, 2, RouteType.WALKING), group = "Hazards").copy(id = "r2"),
    )
    private val shapes = listOf(
        Shape("c1", "Hazards", center = 39.72 to -105.22, radius = 250.4),
        Shape("c2", "Hazards", center = 39.725 to -105.225, radius = 0.3),
        Shape("s1", "Hazards", listOf(39.73 to -105.24, 39.73 to -105.23, 39.74 to -105.23, 39.74 to -105.24)),
        Shape("s2", "Other", listOf(39.0 to -105.0, 39.1 to -105.0, 39.1 to -105.1)),
    )

    @Test fun theRouterIsAskedExactlyWhatTheServerGets() {
        val ask = Routes.ask(driving.route!!, items, shapes)!!
        assertEquals("car-vario", ask.profile)
        assertEquals(linkedMapOf(
            "profile:avoid_motorways" to "1",
            "profile:vmax" to "70",
            "alternativeidx" to "2",
            "nogos" to "-105.20123,39.70123,100|-105.22,39.72,250|-105.225,39.725,1",
            "polygons" to "-105.24,39.73,-105.23,39.73,-105.23,39.74,-105.24,39.74",
            "polylines" to "-105.21,39.71,-105.205,39.715",
        ), ask.params)
        assertEquals(listOf("profile:avoid_motorways", "profile:vmax", "alternativeidx", "nogos", "polygons", "polylines"), ask.params.keys.toList())
        assertFalse(ask.tooMuch)
        val walk = Routes.ask(PlannedRoute(points, 2, RouteType.WALKING, listOf("avoid-steps", "avoid-wet", "hard")), items, shapes)!!
        assertEquals(mapOf("profile:allow_steps" to "0", "profile:iswet" to "1", "profile:SAC_scale_limit" to "5"), walk.params)
        assertEquals(mapOf("profile:SAC_scale_limit" to "1"), Routes.ask(PlannedRoute(points, 2, RouteType.WALKING, listOf("easy")), items, shapes)!!.params)
        assertEquals(mapOf("profile:shortest_way" to "1"), Routes.ask(PlannedRoute(points, 2, RouteType.WALKING, listOf("any-road")), items, shapes)!!.params)
        assertEquals(listOf("avoid-wet", "any-road", "hard"), Routes.cleanOptions(RouteType.WALKING, listOf("hard", "any-road", "avoid-wet")))
        val bike = Routes.ask(PlannedRoute(points, 10, RouteType.BICYCLE, avoid = "Nobody", alternative = 3), items, shapes)!!
        assertEquals("trekking", bike.profile)
        assertEquals(mapOf("alternativeidx" to "3"), bike.params)
        assertNull("a straight route asks nothing", Routes.ask(PlannedRoute(points, avoid = "Hazards"), items, shapes))
        // The key the legs are kept under changes with any of it, the items of the group too.
        val moved = items.map { if (it.id == "m1") it.copy(lat = 39.7013) else it }
        assertTrue(ask.key != Routes.ask(driving.route!!, moved, shapes)!!.key)
        assertEquals("car-vario", Routes.ask(PlannedRoute(points, 25, RouteType.DRIVING), items, shapes)!!.key)
    }

    @Test fun bRouterTakesTheseSettings() {
        val ask = Routes.ask(driving.route!!, items, shapes)!!
        val context = RoutingContext()
        val waypoints = listOf(OsmNodeNamed(), OsmNodeNamed())
        val params = HashMap(ask.params)
        RoutingParamCollector().setParams(context, waypoints, params)
        assertEquals(mapOf("avoid_motorways" to "1", "vmax" to "70"), context.keyValues)
        assertEquals(2, context.getAlternativeIdx(0, 3))
        assertEquals("3 circles, an area, a line", 5, context.nogopoints.size)
        assertEquals("the router's own copy was used", 6, ask.params.size)
    }

    @Test fun everyProfileReadsWithEveryOption() {
        val profiles = java.io.File("build/brouter/assets/brouter")
        for (type in RouteType.entries.filter { it.profile != null }) {
            val all = Routes.OPTIONS[type].orEmpty().map { it.key } + if (type == RouteType.WALKING) listOf("hard") else emptyList()
            val params = Routes.ask(PlannedRoute(points, 2, type, all), items, shapes)!!.params
            val context = RoutingContext()
            RoutingParamCollector().setParams(context, listOf(OsmNodeNamed(), OsmNodeNamed()), HashMap(params))
            context.localFunction = java.io.File(profiles, "${type.profile}.brf").path
            btools.router.ProfileCache.parseProfile(context)
            // Each setting is a variable of the profile, and takes the value asked for.
            for ((k, v) in params) if (k.startsWith("profile:")) {
                assertEquals("${type.title}: $k", v.toFloat(), context.expctxWay.getVariableValue(k.removePrefix("profile:"), -1f))
            }
        }
    }

    @Test fun aGroupWithTooManyPointsLeavesOutWhatDoesNotFit() {
        val big = Shape("s9", "Big", (0 until 400).map { 39.0 + it * 1e-4 to -105.0 })
        val small = Shape("s8", "Big", listOf(39.0 to -105.0, 39.1 to -105.0, 39.1 to -105.1))
        val list = listOf(Marker("m1", 39.5, -105.5, "", "#d7263d", group = "Big"),
            Marker("a1", 39.5, -105.5, "", "#d7263d", endLat = 39.6, endLon = -105.6, group = "Big"))
        val ask = Routes.ask(PlannedRoute(points, 2, RouteType.WALKING, avoid = "Big"), list, listOf(big, small))!!
        assertTrue(ask.tooMuch)
        assertEquals("-105.5,39.5,100", ask.params["nogos"])
        assertEquals("-105,39,-105,39.1,-105.1,39.1", ask.params["polygons"])
        assertEquals("-105.5,39.5,-105.6,39.6", ask.params["polylines"])
    }

    @Test fun numbersAreWrittenAsTheBrowserWritesThem() {
        assertEquals("39.5", Routes.r5(39.5))
        assertEquals("-105", Routes.r5(-105.000001))
        assertEquals("0", Routes.r5(-0.000004))
        assertEquals("0.00001", Routes.r5(0.00001))
        assertEquals("-105.12346", Routes.r5(-105.123456))
        assertEquals("179.99999", Routes.r5(179.99999))
    }

    @Test fun sharedShapesAreKeptAsTheMapInTheBrowserReadsThem() {
        fun feature(id: String, fm: String, ring: String) = JSONObject("""{"type":"Feature","id":"$id","geometry":{"type":"Polygon","coordinates":[$ring]},
            "properties":{"name":"","fieldmap":$fm,"by":"phone-aaaa","updated":"2027-01-15T08:00:00Z"}}""")
        val square = "[[-105.3,39.6],[-105.2,39.6],[-105.2,39.7],[-105.3,39.7],[-105.3,39.6]]"
        val outline = listOf(39.6 to -105.3, 39.6 to -105.2, 39.7 to -105.2, 39.7 to -105.3)
        val text = JSONObject().put("rev", 5).put("full", true).put("markers", JSONArray()
            .put(feature("shape-circle1", """{"kind":"circle","center":[-105.25,39.65],"radius":120,"group":"Hazards","id":"shape-circle1","label":"Pond","color":"#1f5fa8"}""", square))
            .put(feature("shape-rect01", """{"kind":"rect","points":[[-105.2,39.7],[-105.3,39.6]],"group":"Hazards"}""", square))
            .put(feature("shape-poly01", """{"kind":"polygon","group":" Hazards "}""", square))
            .put(feature("shape-none01", """{"kind":"polygon"}""", square)))
        val state = ShareJson.state(text.toString())!!
        assertEquals(emptyList<Marker>(), state.markers)
        assertEquals(listOf("shape-circle1" to "Hazards", "shape-rect01" to "Hazards", "shape-poly01" to "Hazards", "shape-none01" to ""),
            state.shapes.map { it.id to it.group })
        val (circle, rect, poly) = state.shapes
        assertEquals(39.65 to -105.25, circle.center)
        assertEquals(120.0, circle.radius, 0.0)
        assertEquals("circle" to "Pond", circle.kind to circle.label)
        assertEquals("#1f5fa8", circle.color)
        assertEquals("rect", rect.kind)
        assertEquals(outline, rect.ring)
        assertEquals("polygon", poly.kind)
        assertEquals(outline, poly.ring)
        assertTrue("the server's own feature, to send back", JSONObject(poly.feature).has("geometry"))
        // All are kept; a change, a removal and a full answer each do what they say.
        val kept = ShareMerge.shapes(emptyList(), state)
        assertEquals(listOf("shape-circle1", "shape-rect01", "shape-poly01", "shape-none01"), kept.map { it.id })
        val changed = ShareMerge.shapes(kept, ShareState(6, false, emptyList(), emptyList(), listOf("shape-rect01"),
            listOf(kept[0].copy(group = ""), kept[2].copy(label = "Swamp"))))
        assertEquals(listOf("shape-circle1", "shape-poly01", "shape-none01"), changed.map { it.id })
        assertEquals(listOf("", "Swamp"), listOf(changed[0].group, changed[1].label))
        assertEquals(emptyList<Shape>(), ShareMerge.shapes(kept, ShareState(7, true, emptyList(), emptyList(), emptyList())))
        // What this phone has not sent yet stands: a shape moved to another group, a shape deleted.
        val mine = kept.map { if (it.id == "shape-poly01") it.copy(group = "Old Mine") else it }
        val pending = Pending(put = setOf("shape-poly01"), deleted = mapOf("shape-none01" to "2027-01-15T09:00:00Z"))
        val after = ShareMerge.shapes(mine, state, pending)
        assertEquals(listOf("Hazards", "Hazards", "Old Mine"), after.map { it.group })
        // And the file they are kept in reads back the same.
        assertEquals(kept, ShapeStore.read(ShapeStore.write(kept)))
    }
}
