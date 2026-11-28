package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.geo.Arrows
import io.github.sentinel6systems.fieldmap.geo.History
import io.github.sentinel6systems.fieldmap.geo.RouteEdit
import io.github.sentinel6systems.fieldmap.geo.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edit Route: the dot halfway along a leg as drawn, and the changes to a route's points with Undo. */
class RouteEditTest {
    private val a = 39.70 to -105.20
    private val b = 39.70 to -105.10
    private val c = 39.75 to -105.10
    private val points = listOf(a, b, c)

    private fun meters(p: Pair<Double, Double>, q: Pair<Double, Double>) = Arrows.inverse(p.first, p.second, q.first, q.second).first

    @Test fun halfwayAlongAStraightLineIsItsMiddle() {
        val h = Routes.halfway(listOf(a, b))
        assertEquals(meters(a, h), meters(h, b), 0.5)
        assertEquals(39.70, h.first, 0.001)
    }

    @Test fun halfwayAlongABentLineIsOnTheLineNotBetweenItsEnds() {
        // a to b is about 8.6 km east, b to c about 5.6 km north: halfway (7.1 km) is on the first part.
        val h = Routes.halfway(points)
        val total = meters(a, b) + meters(b, c)
        assertEquals(total / 2, meters(a, h), 1.0)
        assertEquals(39.70, h.first, 0.001)
        val straight = Routes.middle(a, c)
        assertTrue(meters(h, straight) > 1000)
    }

    @Test fun halfwayCountsTheDashedPiecesOffTheRoad() {
        // The road starts 2 km from the first point: the drawn line starts at the point, so the dot does too.
        val p = 39.60 to -105.30
        val q = 39.60 to -105.20
        val road = listOf(39.618 to -105.30, 39.618 to -105.20)
        val leg = Routes.roadLeg(p, q, road)!!
        assertTrue(leg.head && leg.tail)
        val h = Routes.halfway(leg.line)
        val total = Routes.length(leg.line)
        assertEquals(total / 2, Routes.length(listOf(p, road[0])) + meters(road[0], h), 1.0)
        assertEquals(39.618, h.first, 0.001)
    }

    @Test fun addAtStartAndAtEnd() {
        val p = 39.80 to -105.00
        assertEquals(listOf(p, a, b, c), RouteEdit.add(points, p, atStart = true))
        assertEquals(listOf(a, b, c, p), RouteEdit.add(points, p, atStart = false))
        assertEquals(listOf(p), RouteEdit.add(emptyList(), p, atStart = false))
        assertEquals(listOf(a, b, c), points)
    }

    @Test fun insertGoesOnTheLegOfTheDot() {
        val p = 39.72 to -105.15
        assertEquals(listOf(a, p, b, c), RouteEdit.insert(points, 0, p))
        assertEquals(listOf(a, b, p, c), RouteEdit.insert(points, 1, p))
        assertNull(RouteEdit.insert(points, 2, p))
        assertNull(RouteEdit.insert(points, -1, p))
    }

    @Test fun moveAPoint() {
        val p = 39.71 to -105.11
        assertEquals(listOf(a, p, c), RouteEdit.move(points, 1, p))
        assertNull(RouteEdit.move(points, 3, p))
    }

    @Test fun removeKeepsTwoPoints() {
        assertEquals(listOf(a, c), RouteEdit.remove(points, 1))
        assertEquals(listOf(b, c), RouteEdit.remove(points, 0))
        assertNull(RouteEdit.remove(listOf(a, b), 0))
        assertNull(RouteEdit.remove(points, 5))
    }

    @Test fun atMostThirtyPoints() {
        assertEquals(30, Drawings.MAX_ROUTE_POINTS)
        val p = 39.90 to -105.00
        val almost = List(29) { 39.0 + it * 0.01 to -105.0 }
        val full = RouteEdit.add(almost, p, atStart = false)!!
        assertEquals(30, full.size)
        assertNull(RouteEdit.add(full, p, atStart = false))
        assertNull(RouteEdit.add(full, p, atStart = true))
        assertNull(RouteEdit.insert(full, 3, p))
        assertEquals(30, RouteEdit.move(full, 3, p)!!.size)
        assertEquals(29, RouteEdit.remove(full, 3)!!.size)
    }

    @Test fun thePreviewJoinsThePointsNextToTheFinger() {
        assertEquals(listOf(b), RouteEdit.neighbors(points, 0, insert = false))
        assertEquals(listOf(a, c), RouteEdit.neighbors(points, 1, insert = false))
        assertEquals(listOf(b), RouteEdit.neighbors(points, 2, insert = false))
        assertEquals(listOf(a, b), RouteEdit.neighbors(points, 0, insert = true))
        assertEquals(listOf(b, c), RouteEdit.neighbors(points, 1, insert = true))
    }

    @Test fun undoTakesBackEachChangeLastFirst() {
        val history = History<List<Pair<Double, Double>>>()
        assertFalse(history.canUndo)
        var now = points
        fun change(next: List<Pair<Double, Double>>?) { history.remember(now); now = next!! }
        change(RouteEdit.add(now, 39.80 to -105.00, atStart = false))
        change(RouteEdit.insert(now, 0, 39.72 to -105.15))
        change(RouteEdit.move(now, 0, 39.69 to -105.21))
        change(RouteEdit.remove(now, 2))
        assertTrue(history.canUndo)
        val sizes = generateSequence { history.undo() }.map { it.size }.toList()
        assertEquals(listOf(5, 5, 4, 3), sizes)
        assertFalse(history.canUndo)
        assertNull(history.undo())
    }
}
