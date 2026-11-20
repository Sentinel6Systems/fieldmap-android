package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.MarkerStore
import io.github.sentinel6systems.fieldmap.geo.Routes
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
}
