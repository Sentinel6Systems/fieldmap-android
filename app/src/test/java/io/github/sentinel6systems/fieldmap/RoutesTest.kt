package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.MarkerStore
import io.github.sentinel6systems.fieldmap.geo.Routes
import io.github.sentinel6systems.fieldmap.mesh.MeshCodec
import io.github.sentinel6systems.fieldmap.share.Pending
import io.github.sentinel6systems.fieldmap.share.ShareJson
import io.github.sentinel6systems.fieldmap.share.ShareMerge
import io.github.sentinel6systems.fieldmap.share.ShareState
import io.github.sentinel6systems.fieldmap.ui.LegLine
import io.github.sentinel6systems.fieldmap.ui.LegState
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Planned routes: their legs and times, the file, and lines from other software. */
class RoutesTest {
    private var n = 0
    private fun id() = "new${++n}"

    private val points = listOf(48.0 to -122.0, 48.01 to -122.0, 48.01 to -121.99)
    private val route = Marker("lq3k9zrte", 0.0, 0.0, "R1", "#1f5fa8").withPoints(points)

    @Test fun aRouteStartsAtItsFirstPoint() {
        assertTrue(route.isRoute)
        assertFalse(route.isArrow)
        assertEquals(48.0, route.lat, 0.0)
        assertEquals(-122.0, route.lon, 0.0)
    }

    @Test fun legsLengthAndWalkingTime() {
        val legs = Routes.legs(points)
        assertEquals(2, legs.size)
        assertEquals(1111.95, legs[0].meters, 0.1)
        assertEquals("due north", 0.0, legs[0].bearing, 1e-6)
        assertEquals(744.0, legs[1].meters, 1.0)              // 0.01° of longitude at 48°
        assertEquals(90.0, legs[1].bearing, 0.01)
        assertEquals(legs.sumOf { it.meters }, Routes.length(points), 1e-9)
        // 1856 m at 2 mph (3.22 km/h): 34.6 minutes, shown as 35. A mile takes 30 minutes at 2 mph, 1 at 60.
        assertEquals(35, Routes.minutes(Routes.length(points), 2))
        assertEquals(30, Routes.minutes(1609.344, 2))
        assertEquals(60, Routes.minutes(2 * 1609.344, 2))
        assertEquals(1, Routes.minutes(1609.344, 60))
        assertEquals(2, route.route!!.mph)
        assertEquals("23 min", Routes.timeText(23))
        assertEquals("2 h 05 min", Routes.timeText(125))
        assertEquals("042°T", Routes.bearingText(42.4))
        assertEquals("000°T", Routes.bearingText(359.7))
    }

    @Test fun theMiddleOfALegIsHalfWay() {
        val (lat, lon) = Routes.middle(points[0], points[1])
        assertEquals(48.005, lat, 1e-6)
        assertEquals(-122.0, lon, 1e-9)
    }

    @Test fun simplifyingKeepsTheEndsAndTheCorners() {
        // A straight line north with one sharp corner east in the middle: the corner stays.
        val line = (0..20).map { 48.0 + it * 0.001 to -122.0 } + (1..20).map { 48.02 to -122.0 + it * 0.001 }
        val three = Routes.simplify(line, 3)
        assertEquals(listOf(line.first(), 48.02 to -122.0, line.last()), three)
        assertEquals(line, Routes.simplify(line, 100))
        assertEquals(10, Routes.simplify(line, 10).size)
    }

    @Test fun aRouteSavesAndLoadsBackTheSame() {
        val loaded = Drawings.fromGeoJson(Drawings.toGeoJson(listOf(route)), "#000000", ::id)
        assertEquals(listOf(route), loaded.markers)
        // Two points stay a route: the app's own kind says so (else two points are an arrow).
        val two = route.withPoints(points.take(2))
        assertEquals(listOf(two), Drawings.fromGeoJson(Drawings.toGeoJson(listOf(two)), "#000000", ::id).markers)
        // Its speed goes with it; new points keep it; a speed not in the list is the default.
        val fast = route.copy(route = route.route!!.copy(mph = 45))
        assertEquals(listOf(fast), Drawings.fromGeoJson(Drawings.toGeoJson(listOf(fast)), "#000000", ::id).markers)
        assertEquals(45, fast.withPoints(points.take(2)).route!!.mph)
        val odd = Drawings.toGeoJson(listOf(fast)).replace("\"speedMph\":45", "\"speedMph\":33")
        assertEquals(2, Drawings.fromGeoJson(odd, "#000000", ::id).markers.single().route!!.mph)
    }

    @Test fun theFileIsALineOtherSoftwareReads() {
        val f = JSONObject(Drawings.toGeoJson(listOf(route))).getJSONArray("features").getJSONObject(0)
        val g = f.getJSONObject("geometry")
        assertEquals("LineString", g.getString("type"))
        assertEquals(3, g.getJSONArray("coordinates").length())
        assertEquals(-121.99, g.getJSONArray("coordinates").getJSONArray(2).getDouble(0), 0.0)
        assertEquals(48.01, g.getJSONArray("coordinates").getJSONArray(2).getDouble(1), 0.0)
        val p = f.getJSONObject("properties")
        assertEquals("R1", p.getString("name"))
        assertEquals("#1f5fa8", p.getString("stroke"))
        val fm = p.getJSONObject("fieldmap")
        assertEquals("route", fm.getString("kind"))
        assertEquals(3, fm.getJSONArray("waypoints").length())
        assertEquals(-122.0, fm.getJSONArray("waypoints").getJSONArray(0).getDouble(0), 0.0)
    }

    @Test fun theRoutesPointsComeFromFieldmapWhenItsLineDiffers() {
        // A route whose line follows roads (more points than were tapped): its tapped points are kept.
        val text = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-122,48],[-122.001,48.005],[-122,48.01]]},
          "properties":{"fieldmap":{"kind":"route","label":"Road","color":"#2a9d3f","id":"lq3k9zrtf","waypoints":[[-122,48],[-122,48.01]]}}}"""
        val m = Drawings.fromGeoJson(text, "#000000", ::id).markers.single()
        assertEquals(listOf(48.0 to -122.0, 48.01 to -122.0), m.route!!.points)
        assertEquals("lq3k9zrtf", m.id)
        assertEquals("Road", m.label)
    }

    @Test fun longerLinesFromOtherSoftwareAreRoutesOfAtMostThirtyPoints() {
        val coords = (0 until 200).joinToString(",") { "[${-122 + it * 0.001},${48 + (it % 7) * 0.0003}]" }
        val text = """{"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[1,2],[3,4],[5,6]]},"properties":{"name":"Trail"}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[1,2],[3,4]]},"properties":{}}]}"""
        val loaded = Drawings.fromGeoJson(text, "#111111", ::id)
        assertEquals(3, loaded.markers.size)
        assertEquals(listOf(2.0 to 1.0, 4.0 to 3.0, 6.0 to 5.0), loaded.markers[0].route!!.points)
        assertEquals("Trail", loaded.markers[0].label)
        val long = loaded.markers[1].route!!.points
        assertEquals(Drawings.MAX_ROUTE_POINTS, long.size)
        assertEquals(48.0 to -122.0, long.first())
        assertEquals(-122 + 199 * 0.001, long.last().second, 1e-9)
        assertTrue(loaded.markers[2].isArrow)
        assertEquals(0, loaded.skipped)
    }

    @Test fun newRoutesAreNamedR1R2() {
        assertEquals("R1", Drawings.nextName(listOf(Marker("1", 0.0, 0.0, "P1", "#111111")), route = true))
        assertEquals("R2", Drawings.nextName(listOf(route), route = true))
        assertEquals("P1", Drawings.nextName(listOf(route)))
        assertEquals("A1", Drawings.nextName(listOf(route), arrow = true))
    }

    @Test fun theStoreKeepsRoutes() {
        val file = File.createTempFile("markers", ".geojson").apply { delete(); deleteOnExit() }
        val store = MarkerStore(file)
        val m = store.addRoute(points, "#d7263d")
        assertEquals("R1", m.label)
        assertEquals(listOf(m), MarkerStore(file).markers)
        // The same route from a file is not added twice.
        assertEquals(0, store.addAll(listOf(m.copy(id = "other"))))
    }

    /* ---------- shared ---------- */

    private val uid = "00000000-0000-0000-0000-000000000000"
    private val shared = route.copy(shared = true, updated = "2027-01-15T08:00:00Z", by = uid, route = route.route!!.copy(mph = 15))

    @Test fun aSharedRouteGoesThroughTheServer() {
        val state = ShareJson.state("""{"rev":2,"full":true,"positions":[],"removed":[],"markers":[${ShareJson.marker(shared)}]}""")!!
        assertEquals(listOf(shared), state.markers)
        val json = JSONObject(ShareJson.marker(shared))
        assertEquals("route", json.getJSONObject("properties").getJSONObject("fieldmap").getString("kind"))
        // What the server takes: up to 30 points and 8 KB.
        val longest = shared.copy(id = "a".repeat(64), label = "L".repeat(30), by = "b".repeat(64))
            .withPoints((0 until Drawings.MAX_ROUTE_POINTS).map { -89.123456789 + it * 0.0123456789 to 179.123456789 - it * 0.0123456789 })
        assertTrue(ShareJson.marker(longest).toByteArray().size < 8192)
    }

    @Test fun aSharedRouteGoesOverTheMeshInOnePacket() {
        val bytes = MeshCodec.marker(shared)!!
        assertTrue("${bytes.size} bytes", bytes.size <= 70)
        val message = MeshCodec.decode(bytes)!!
        // Its own kind of message: an app from before routes finds no marker or arrow in it.
        assertNull(message.marker)
        assertNull(message.arrow)
        val back = MeshCodec.change(message.route!!)!!.marker!!
        assertEquals(shared.copy(route = null), back.copy(route = null, lat = shared.lat, lon = shared.lon))
        assertEquals(15, back.route!!.mph)
        // The first point exactly (1e-7°), the others to about a meter (1e-5°).
        assertEquals(points[0].first, back.route!!.points[0].first, 1e-7)
        for ((a, b) in points.zip(back.route!!.points)) {
            assertEquals(a.first, b.first, 1e-5)
            assertEquals(a.second, b.second, 1e-5)
        }
    }

    @Test fun thirtyPointsAndALongNameFitOnePacket() {
        // 30 points about 1 km apart, a 30-letter name, the ids the app makes (a shared route always has one of its own).
        val walk = (0 until Drawings.MAX_ROUTE_POINTS).map { 48.0 + it * 0.009 to -122.0 + (it % 2) * 0.012 }
        val longest = shared.copy(id = "lq3k9zrtq12", label = "L".repeat(30)).withPoints(walk)
        val bytes = MeshCodec.marker(longest)!!
        assertTrue("${bytes.size} bytes", bytes.size <= MeshCodec.MAX_PAYLOAD)
        assertEquals("L".repeat(30), MeshCodec.change(MeshCodec.decode(bytes)!!.route!!)!!.marker!!.label)
        // Points far apart and a name of emoji: the name is left out; a route that still does not fit stays off the mesh.
        val far = (0 until Drawings.MAX_ROUTE_POINTS).map { (it % 2) * 80.0 - 40 to (it % 3) * 100.0 - 100 }
        val emoji = longest.copy(label = "\uD83D\uDE80".repeat(15))
        assertEquals("", MeshCodec.change(MeshCodec.decode(MeshCodec.marker(emoji)!!)!!.route!!)!!.marker!!.label)
        assertNull(MeshCodec.marker(longest.withPoints(far)))
    }

    @Test fun aRouteHeardBadlyIsDropped() {
        val r = MeshCodec.decode(MeshCodec.marker(shared)!!)!!.route!!
        assertNull(MeshCodec.change(r.copy(id = "x")))
        assertNull("odd steps", MeshCodec.change(r.copy(steps = r.steps.dropLast(1))))
        assertNull("one point", MeshCodec.change(r.copy(steps = emptyList())))
        assertNull("off the earth", MeshCodec.change(r.copy(steps = listOf(9_000_000, 0))))
        assertEquals("a speed not offered is the default", 2, MeshCodec.change(r.copy(mph = 33))!!.marker!!.route!!.mph)
    }

    @Test fun anOlderAppCannotTurnARouteIntoAnArrowOrAPoint() {
        // A route of two points reads as an arrow in an app from before routes; its copy back through the server does not replace the route.
        val mine = shared.withPoints(points.take(2))
        val arrow = mine.copy(route = null, endLat = points[1].first, endLon = points[1].second, updated = "2027-01-15T08:05:00Z")
        val merged = ShareMerge.merge(listOf(mine), ShareState(5, true, emptyList(), listOf(arrow), emptyList()), Pending())
        assertEquals(listOf(mine), merged.markers)
        assertEquals(setOf(mine.id), merged.pending.put)
        assertEquals(listOf(2, 1, 0), listOf(mine.rank, arrow.rank, arrow.copy(endLat = null, endLon = null).rank))
    }

    /* ---------- route types ---------- */

    @Test fun aRoutesTypeGoesWithItInFilesAndOverTheMesh() {
        val walking = shared.copy(route = shared.route!!.copy(type = io.github.sentinel6systems.fieldmap.data.RouteType.WALKING))
        assertEquals(listOf(walking), Drawings.fromGeoJson(Drawings.toGeoJson(listOf(walking), sharing = true), "#000000", ::id).markers)
        assertEquals("walking", JSONObject(ShareJson.marker(walking)).getJSONObject("properties").getJSONObject("fieldmap").getString("routing"))
        val back = MeshCodec.change(MeshCodec.decode(MeshCodec.marker(walking)!!)!!.route!!)!!.marker!!
        assertEquals(io.github.sentinel6systems.fieldmap.data.RouteType.WALKING, back.route!!.type)
        // A route from before route types, or of a type not known here: straight.
        assertEquals(io.github.sentinel6systems.fieldmap.data.RouteType.STRAIGHT, route.route!!.type)
        val r = MeshCodec.decode(MeshCodec.marker(walking)!!)!!.route!!
        assertEquals(io.github.sentinel6systems.fieldmap.data.RouteType.STRAIGHT, MeshCodec.change(r.copy(type = 9))!!.marker!!.route!!.type)
    }

    @Test fun aFileGetsTheLineAsItIsDrawnTheServerOnlyThePoints() {
        val line = listOf(48.0 to -122.0, 48.005 to -122.001, 48.01 to -122.0, 48.01 to -121.99)
        val file = JSONObject(Drawings.toGeoJson(listOf(route)) { line }).getJSONArray("features").getJSONObject(0)
        assertEquals(4, file.getJSONObject("geometry").getJSONArray("coordinates").length())
        assertEquals(3, file.getJSONObject("properties").getJSONObject("fieldmap").getJSONArray("waypoints").length())
        // Read back: the points, not the line.
        assertEquals(points, Drawings.fromGeoJson(file.toString(), "#000000", ::id).markers.single().route!!.points)
        assertEquals(3, JSONObject(ShareJson.marker(shared)).getJSONObject("geometry").getJSONArray("coordinates").length())
    }

    @Test fun pointsStayWhereTheyWerePutAndDashedLinesJoinThemToTheRoad() {
        // The road from 30 m east of the start to 2 km short of the end point.
        val p = 48.0 to -122.0
        val q = 48.05 to -122.0
        val road = listOf(48.0 to -121.9996, 48.02 to -121.9996, 48.032 to -121.9996)
        val leg = Routes.roadLeg(p, q, road)!!
        // Any gap over 3 m is dashed; only one over 100 m "ends off road".
        assertTrue(leg.head)
        assertTrue(leg.tail)
        assertTrue(leg.offRoad)
        assertEquals(listOf(p) + road + listOf(q), leg.line)
        val drawn = LegLine(leg.line, LegState.ROUTED, leg.head, leg.tail, leg.offRoad)
        assertEquals(listOf(listOf(p, road.first()) to true, road to false, listOf(road.last(), q) to true), drawn.pieces)
        assertTrue(Routes.length(drawn.line) > Routes.length(road) + 1900)
        // 30 m off at the start only: dashed, but not off road.
        val near = Routes.roadLeg(p, road.last(), road)!!
        assertTrue(near.head && !near.tail && !near.offRoad)
        // On the road (under 3 m): nothing dashed.
        val on = Routes.roadLeg(road.first(), road.last(), road)!!
        assertEquals(road, on.line)
        assertEquals(listOf(road to false), LegLine(on.line, LegState.ROUTED, on.head, on.tail, on.offRoad).pieces)
        // More than 15 km from its point: no way (drawn straight).
        assertNull(Routes.roadLeg(p, 48.2 to -122.0, road))
        // Straight legs and legs not worked out yet are one piece.
        assertEquals(listOf(listOf(p, q) to true), LegLine(listOf(p, q), LegState.NO_WAY).pieces)
        assertEquals(listOf(listOf(p, q) to false), LegLine(listOf(p, q), LegState.STRAIGHT).pieces)
    }

    @Test fun theRoadsNearestAPointAreFarEnoughApart() {
        val p = 48.0 to -122.0
        // A north-south road 1 km east, and an east-west one 2 km north; a far one 20 km south.
        val east = listOf(47.99 to -121.98657, 48.01 to -121.98657)
        val north = listOf(48.018 to -122.01, 48.018 to -121.99)
        val far = listOf(47.82 to -122.01, 47.82 to -121.99)
        val found = Routes.nearestRoadPoints(p, listOf(far, north, east))
        assertEquals(2, found.size)
        assertEquals(1000.0, Routes.length(listOf(p, found[0])), 20.0)
        assertEquals(48.0, found[0].first, 1e-4)
        assertEquals(2000.0, Routes.length(listOf(p, found[1])), 30.0)
        // At least 500 m apart: a second place on the same road next to the first is not taken.
        val twice = Routes.nearestRoadPoints(p, listOf(east, east.map { it.first to it.second + 0.0001 }))
        assertEquals(1, twice.size)
    }
}
