package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Gpx
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.RecordedTrack
import io.github.sentinel6systems.fieldmap.geo.Arrows
import io.github.sentinel6systems.fieldmap.geo.TrackKeeper
import io.github.sentinel6systems.fieldmap.geo.TrackPoint
import io.github.sentinel6systems.fieldmap.geo.TrackStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Recorded tracks: which positions become points, what a track adds up to, and its files. */
class TrackTest {
    private val start = 0L

    /** A position `east`/`north` meters from a fixed place, at second `s`. */
    private fun at(east: Double, north: Double, s: Int, acc: Double = 5.0, alt: Double? = null): TrackPoint {
        val (lat, lon) = Arrows.destination(45.0, 7.0, 0.0, north).let { (la, lo) -> Arrows.destination(la, lo, 90.0, east) }
        return TrackPoint(lat, lon, alt, start + s * 1000L, acc)
    }

    /** Feeds positions every `every` seconds along a path given as (east, north) at time s. */
    private fun record(keeper: TrackKeeper, seconds: Int, every: Int, speed: Double?, path: (Int) -> Pair<Double, Double>) {
        for (s in 0..seconds step every) {
            val (e, n) = path(s)
            keeper.take(at(e, n, s), speed)
        }
    }

    @Test fun standingStillAddsNoPointsButTheStop() {
        val keeper = TrackKeeper(15)
        val noise = Random(1)
        // Ten minutes in one place, the receiver wandering a few meters, now and then 12 m.
        record(keeper, 600, 15, 0.0) { s -> if (s % 150 == 75) 12.0 to 0.0 else noise.nextDouble(-3.0, 3.0) to noise.nextDouble(-3.0, 3.0) }
        assertEquals(1, keeper.points.size)
        assertTrue("stop ${keeper.points[0].still} s", keeper.points[0].still >= 585)
    }

    @Test fun aSteadyWalkGetsAPointEachInterval() {
        val keeper = TrackKeeper(60)
        // 1.4 m/s north for 10 minutes, positions every 15 s.
        record(keeper, 600, 15, 1.4) { s -> 0.0 to 1.4 * s }
        assertEquals(11, keeper.points.size)
        assertTrue(keeper.points.all { it.still == 0 })
    }

    @Test fun noiseOnASlowStraightWalkIsNotATurn() {
        val keeper = TrackKeeper(300)
        val noise = Random(7)
        // 0.5 m/s north for 20 minutes, the receiver off by up to 3 m each time.
        record(keeper, 1200, 15, 0.5) { s -> noise.nextDouble(-3.0, 3.0) to 0.5 * s + noise.nextDouble(-3.0, 3.0) }
        // One point each 5 minutes, and only a few from the noise.
        assertTrue("${keeper.points.size} points", keeper.points.size <= 8)
    }

    @Test fun aSlowSwitchbackKeepsItsShapeEvenAtFiveMinutes() {
        val keeper = TrackKeeper(300)
        // 0.5 m/s up a hillside in legs of 30 m, turning back each time (zigzag east and west, slowly north).
        val leg = 60                                            // seconds per leg
        fun place(s: Int): Pair<Double, Double> {
            val k = s / leg
            val f = (s % leg) / leg.toDouble()
            val east = if (k % 2 == 0) 30 * f else 30 * (1 - f)
            return east to (k * 6 + 6 * f)
        }
        record(keeper, 20 * leg, 15, 0.5, ::place)
        keeper.flush()
        // 20 legs: a point at (nearly) every turn, not 5 (one per 5 minutes).
        assertTrue("${keeper.points.size} points", keeper.points.size >= 18)
        val stats = TrackStats(listOf(keeper.points))
        assertTrue("${stats.meters} m", stats.meters > 450)
    }

    @Test fun roughPositionsWaitForBetterOnes() {
        val keeper = TrackKeeper(30)
        keeper.take(at(0.0, 0.0, 0), 1.4)
        // Rough (50 m) positions while walking: not kept...
        keeper.take(at(0.0, 42.0, 30, acc = 50.0), 1.4)
        assertEquals(1, keeper.points.size)
        // ...until two intervals passed without a good one: the best rough one is better than a gap.
        keeper.take(at(0.0, 84.0, 60, acc = 40.0), 1.4)
        assertEquals(2, keeper.points.size)
        assertEquals(TrackKeeper.MAX_ERROR, keeper.points[1].acc!!, 0.0)
    }

    @Test fun stopKeepsTheWaySinceTheLastPoint() {
        val keeper = TrackKeeper(300)
        record(keeper, 120, 15, 1.4) { s -> 0.0 to 1.4 * s }
        assertEquals(1, keeper.points.size)
        assertTrue(keeper.flush())
        assertEquals(2, keeper.points.size)
        assertEquals(at(0.0, 168.0, 120).lat, keeper.points[1].lat, 1e-9)
    }

    @Test fun aTurnIsMeasuredFromTheWayTheDeviceLeft() {
        assertEquals(90.0, TrackKeeper.turn(0.0, 90.0), 1e-9)
        assertEquals(20.0, TrackKeeper.turn(350.0, 10.0), 1e-9)
        assertEquals(180.0, TrackKeeper.turn(0.0, 180.0), 1e-9)
        assertEquals(15, TrackKeeper.sample(300))
        assertEquals(15, TrackKeeper.sample(15))
    }

    @Test fun whatATrackAddsUpTo() {
        // Two parts (paused between): 200 m in 200 s with a 2-minute stop, then 100 m in 100 s; heights with noise.
        val first = listOf(at(0.0, 0.0, 0, alt = 100.0), at(0.0, 100.0, 100, alt = 102.0).copy(still = 120), at(0.0, 200.0, 320, alt = 120.0))
        val second = listOf(at(0.0, 300.0, 1000, alt = 118.0), at(0.0, 400.0, 1100, alt = 110.0))
        val t = TrackStats(listOf(first, second))
        assertEquals(300.0, t.meters, 1.0)
        assertEquals(420, t.seconds)
        assertEquals(120, t.stoppedSeconds)
        assertEquals(300, t.movingSeconds)
        assertEquals(5, t.points)
        val (up, down) = t.climb!!
        assertEquals("the 2 m step is noise", 20.0, up, 1e-9)
        assertEquals(8.0, down, 1e-9)
    }

    private val track = Marker("lq3k9ztrk", 0.0, 0.0, "T1", "#d7263d", track = RecordedTrack(listOf(
        listOf(at(0.0, 0.0, 0, alt = 100.0), at(0.0, 100.0, 60, alt = 101.5).copy(still = 90)),
        listOf(at(10.0, 200.0, 600), at(20.0, 300.0, 660)),
    ).map { s -> s.map { it.copy(acc = null, time = 1_800_000_000_000L + it.time) } })).let { it.copy(lat = it.track!!.segments[0][0].lat, lon = it.track.segments[0][0].lon) }

    @Test fun aTrackSavesAndLoadsBackTheSame() {
        val text = Drawings.toGeoJson(listOf(track))
        assertEquals(listOf(track), Drawings.fromGeoJson(text, "#000000") { "x" }.markers)
        val f = JSONObject(text).getJSONArray("features").getJSONObject(0)
        assertEquals("MultiLineString", f.getJSONObject("geometry").getString("type"))
        assertEquals(101.5, f.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getJSONArray(1).getDouble(2), 1e-9)
        assertEquals("track", f.getJSONObject("properties").getJSONObject("fieldmap").getString("kind"))
        assertEquals("T2", Drawings.nextName(listOf(track), track = true))
    }

    @Test fun aTrackExportsAsGpx() {
        val gpx = Gpx.track(track.copy(label = "Ridge & <back>"))
        assertTrue(gpx.contains("""<gpx version="1.1""""))
        assertTrue(gpx.contains("<name>Ridge &amp; &lt;back&gt;</name>"))
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(4, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<ele>101.5</ele>"))
        assertTrue(gpx.contains("<time>2027-01-15T08:00:00Z</time>"))
    }
}
