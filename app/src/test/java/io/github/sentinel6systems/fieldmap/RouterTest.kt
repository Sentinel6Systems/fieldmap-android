package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.geo.Router
import io.github.sentinel6systems.fieldmap.geo.Routes
import io.github.sentinel6systems.fieldmap.offline.Box
import io.github.sentinel6systems.fieldmap.offline.RoutingFile
import io.github.sentinel6systems.fieldmap.offline.RoutingFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Routes along roads and trails: the server's list of routing files, and BRouter itself. */
class RouterTest {
    private val sha = "a".repeat(64)

    @Test fun theServersListIsReadAndCheckedStrictly() {
        val text = """{"version":1,"source":"x","made":"2026-11-20T00:00:00Z","files":[
          {"name":"W80_N35.rd5","west":-80,"south":35,"east":-75,"north":40,"size":1000,"sha256":"$sha"},
          {"name":"../evil.rd5","west":-80,"south":35,"east":-75,"north":40,"size":1000,"sha256":"$sha"},
          {"name":"E5_N45.rd5","west":5,"south":45,"east":10,"north":50,"size":0,"sha256":"$sha"},
          {"name":"E5_N45.rd5","west":5,"south":45,"east":10,"north":50,"size":10,"sha256":"short"},
          {"name":"E175_S5.rd5","west":175,"south":-5,"east":180,"north":0,"size":10,"sha256":"${"B".repeat(64)}"}]}"""
        val files = RoutingFiles.parse(text)!!
        assertEquals(listOf("W80_N35.rd5", "E175_S5.rd5"), files.map { it.name })
        assertEquals("b".repeat(64), files[1].sha256)
        assertNull(RoutingFiles.parse("not json"))
        assertNull(RoutingFiles.parse("""{"files":3}"""))
    }

    @Test fun anAreaNeedsTheFilesItTouches() {
        val f = RoutingFile("W80_N35.rd5", -80, 35, -75, 40, 1, sha)
        assertTrue(f.covers(Box(-78.0, 36.0, -77.0, 37.0)))
        assertTrue("across its edge", f.covers(Box(-81.0, 34.0, -79.0, 36.0)))
        assertFalse("beside it", f.covers(Box(-75.0, 36.0, -74.0, 37.0)))
        assertFalse(f.covers(Box(-78.0, 40.0, -77.0, 41.0)))
        // An area across the 180° line.
        val east = RoutingFile("E175_N50.rd5", 175, 50, 180, 55, 1, sha)
        val west = RoutingFile("W180_N50.rd5", -180, 50, -175, 55, 1, sha)
        val across = Box(179.0, 51.0, -179.0, 52.0)
        assertTrue(east.covers(across))
        assertTrue(west.covers(across))
        assertFalse(RoutingFile("E170_N50.rd5", 170, 50, 175, 55, 1, sha).covers(across))
    }

    @Test fun theOriginServesTheRoutingFiles() {
        assertEquals(listOf("http://192.168.1.10/routing/"), RoutingFiles.bases("http://192.168.1.10/maps/"))
        assertEquals(listOf("http://192.168.1.10:8080/routing/", "http://192.168.1.10/routing/"), RoutingFiles.bases("http://192.168.1.10:8080/"))
    }

    @Test fun alongALine() {
        val line = listOf(48.0 to -122.0, 48.01 to -122.0, 48.01 to -121.99)
        val (lat, lon) = Routes.along(line, 0.5)
        // Half of 1856 m: 928 m north of the start, on the first piece.
        assertEquals(48.0 + 928.0 / 111_195, lat, 1e-5)
        assertEquals(-122.0, lon, 1e-9)
        assertEquals(0.0, Routes.bearingAt(line, 0.5), 1e-6)
        assertEquals(90.0, Routes.bearingAt(line, 0.9), 0.01)
        assertEquals(line.last(), Routes.along(line, 1.0))
    }

    /**
     * BRouter on a real routing file: run with FIELDMAP_TEST_ROUTING=<folder with W115_N35.rd5> (from
     * https://brouter.de/brouter/segments4/); skipped otherwise. The profiles come from the build.
     */
    @Test fun brouterFindsTheWayOnRoadsAndTrails() {
        val segments = System.getenv("FIELDMAP_TEST_ROUTING")?.let(::File)
        assumeTrue(segments != null && File(segments, "W115_N35.rd5").isFile)
        val router = Router(segments!!, File("build/brouter/assets/brouter"))
        val a = 36.0397 to -114.9817
        val b = 35.9786 to -114.8325
        for (type in listOf(RouteType.DRIVING, RouteType.WALKING)) {
            val line = router.leg(a, b, type.profile!!)
            assertNotNull(type.title, line)
            val meters = Routes.length(line!!)
            assertTrue("${type.title}: $meters m", meters in 15_000.0..25_000.0)
            // From the road nearest the first point to the one nearest the second.
            assertTrue(Routes.length(listOf(a, line.first())) < 300)
            assertTrue(Routes.length(listOf(b, line.last())) < 300)
        }
        assertNull("outside the routing files", router.leg(40.0 to -100.0, 40.1 to -100.1, RouteType.DRIVING.profile!!))
        // A point out on the water, far from any road: the line ends at the nearest road, and the leg
        // goes on to the point straight (dashed); the point is not moved.
        val water = 36.0950 to -114.7800
        val road = router.leg(a, water, RouteType.DRIVING.profile!!)
        assertNotNull("to the water", road)
        val leg = Routes.roadLeg(a, water, road!!)!!
        assertTrue("${Routes.length(listOf(water, road.last()))} m", leg.offEnd && !leg.offStart)
        assertEquals(water, leg.line.last())
    }
}
