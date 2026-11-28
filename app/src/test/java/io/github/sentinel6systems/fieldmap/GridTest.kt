package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.geo.Grid
import io.github.sentinel6systems.fieldmap.geo.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/** The MGRS grid on the map: the same tests as the map in the browser's (print/test/grid.test.mjs). */
class GridTest {
    private fun lines(f: List<Grid.Feature>) = f.filter { it.isLine }
    private fun names(f: List<Grid.Feature>) = f.filter { it.kind == "name" }.map { it.label }

    @Test fun theSpacingFollowsTheZoom() {
        assertEquals(0, Grid.step(40.0, 4.0))
        assertEquals(100000, Grid.step(40.0, 8.0))
        assertEquals(10000, Grid.step(40.0, 10.0))
        assertEquals(1000, Grid.step(40.0, 13.0))
        assertEquals(100, Grid.step(40.0, 16.0))
    }

    @Test fun lineLabelsAreTheDigitsWithinThe100KmSquare() {
        assertEquals("43", Grid.lineLabel(4543000.0, 1000))
        assertEquals("10 km lines: one digit", "4", Grid.lineLabel(4540000.0, 10000))
        assertEquals("0", Grid.lineLabel(4500000.0, 10000))
        assertEquals("432", Grid.lineLabel(4543200.0, 100))
        assertEquals("", Grid.lineLabel(4500000.0, 100000))
    }

    @Test fun theWayBackFromUtmIsTheWayThere() {
        val u = Utm.toUtm(39.8, -105.2)
        val (lat, lon) = Utm.fromUtm(u.zone, u.easting, u.northing)
        assertEquals(39.8, lat, 1e-9)
        assertEquals(-105.2, lon, 1e-9)
        // Another zone's reckoning, outside that zone.
        val other = Utm.toUtm(39.8, -101.9, 13)
        assertEquals(-101.9, Utm.fromUtm(13, other.easting, other.northing).second, 1e-8)
    }

    @Test fun oneKmLinesFallOnWholeKilometersOfTheViewsZone() {
        val f = Grid.features(-105.3, 39.9, -105.2, 40.0, 13.0)
        assertTrue(lines(f).size > 10)
        for (line in lines(f)) for ((lat, lon) in line.points) {
            val u = Utm.toUtm(lat, lon, 13)
            assertTrue("$lon,$lat is on no 1 km line", listOf(u.easting, u.northing).any { abs(it - (it / 1000).roundToLong() * 1000.0) < 1 })
        }
    }

    @Test fun aViewAcrossAZoneEdgeHasEachZonesLinesCutAtTheEdge() {
        val one = Grid.features(-106.0, 39.5, -104.0, 40.5, 10.0)       // zone 13 only
        val both = Grid.features(-103.0, 39.5, -101.0, 40.5, 10.0)      // -102 is the edge of zones 13 and 14
        for (line in lines(both)) {
            val lons = line.points.map { it.second }
            assertTrue("a line crosses the zone edge", lons.max() <= -102 + 1e-9 || lons.min() >= -102 - 1e-9)
        }
        assertTrue(names(both).any { it.startsWith("13") } || names(both).any { it.startsWith("14") })
        assertTrue(lines(one).isNotEmpty())
    }

    @Test fun hundredKmSquaresCarryTheirLetters() {
        val f = Grid.features(-107.0, 38.0, -103.0, 41.0, 8.0)
        assertTrue(names(f).joinToString(), names(f).any { Regex("^13[ST] [A-Z]{2}$").matches(it) })
    }

    @Test fun zoomedOutTheGridZoneLinesAndNames() {
        val f = Grid.features(-120.0, 30.0, -90.0, 50.0, 3.0)
        assertTrue("13T" in names(f))
        assertTrue(lines(f).all { it.kind == "zone" })
    }

    @Test fun belowTheEquatorOneZoneRunsOnAcrossIt() {
        val f = Grid.features(-105.2, -0.05, -105.1, 0.05, 13.0)
        assertTrue(lines(f).any { l -> l.points.any { it.first < 0 } && l.points.any { it.first > 0 } })
    }

    @Test fun zoomedInTheLinesDigitsSitOnTheViewsTopAndLeftEdgesAndTheSquareIsNamed() {
        val f = Grid.features(-105.3, 39.9, -105.2, 40.0, 13.0)
        val edges = f.filter { it.kind == "edge" }
        assertTrue(edges.any { it.anchor == "top" && abs(it.points[0].first - 40.0) < 1e-6 })
        assertTrue(edges.any { it.anchor == "left" && abs(it.points[0].second + 105.3) < 1e-6 })
        assertTrue(edges.all { Regex("^\\d\\d$").matches(it.label) })
        assertTrue(names(f).joinToString(), names(f).any { Regex("^13S [A-Z]{2}$").matches(it) })
        // At 100 m lines too: the square stays named.
        assertTrue(names(Grid.features(-105.252, 39.95, -105.248, 39.953, 17.0)).isNotEmpty())
    }

    @Test fun pikesPeakIsInSquareDC() {
        // The example of the user guide: 13S DC 9616 9908.
        assertEquals("13S DC 9616 9908", Utm.toMgrs(38.840544, -105.04421, 4))
    }
}
