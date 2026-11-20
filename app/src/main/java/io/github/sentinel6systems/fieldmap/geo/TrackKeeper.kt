package io.github.sentinel6systems.fieldmap.geo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A point of a recorded track: where and when (ms since 1970), the height when the receiver gave
 * one, and how long the device stayed there afterwards (`still`, seconds; 0: it went on).
 */
data class TrackPoint(val lat: Double, val lon: Double, val alt: Double?, val time: Long, val acc: Double? = null, val still: Int = 0)

/**
 * Which positions of a recording become points of the track. Positions come every
 * KeepRules.sample(interval) seconds; a point is kept
 * - when the interval has passed and the device is outside the last point's noise circle (the
 *   errors of both positions together, at least MIN_MOVE meters): standing still adds nothing,
 *   the time is noted on the last point instead (a stop of STOP_SECONDS or more);
 * - before the interval is up, where the way turns by more than TURN_DEGREES from the way the
 *   device left the last point in, at least TURN_METERS out: a slow, winding trail keeps its
 *   shape at any interval;
 * - not from a position with an error over MAX_ERROR meters, unless none better came for two
 *   intervals.
 * A position just outside the circle at walking speed or less counts only when the next one
 * is outside too: the receiver's drift at a stop does not draw a star of points.
 * One segment: Pause ends it, Resume starts the next one with a new keeper.
 */
class TrackKeeper(var interval: Int) {
    /** The points kept so far, the newest last; the last one's `still` grows during a stop. */
    val points = ArrayList<TrackPoint>()
    private var waiting: TrackPoint? = null          // just outside the circle, slowly: kept if the next one is out too
    private var heading: Double? = null               // the way the device left the last point in
    private var latest: TrackPoint? = null            // the last position out of the last point's circle, not kept (yet)
    private var best: TrackPoint? = null              // the best of the too-rough positions since the last point

    /** Judges a position (`speed` m/s when known). True when the points changed (one kept, or a stop grew). */
    fun take(p: TrackPoint, speed: Double? = null): Boolean {
        val last = points.lastOrNull()
        if ((p.acc ?: 0.0) > MAX_ERROR) {
            if (best == null || (p.acc ?: 0.0) < (best!!.acc ?: 0.0)) best = p
            val since = last?.time ?: return false
            // Nothing better for two intervals: the best rough position is better than a gap.
            val rough = best!!
            if (p.time - since < 2000L * interval) return false
            best = null
            return take(rough.copy(acc = MAX_ERROR), speed)
        }
        best = null
        if (last == null) { keep(p); return true }

        val (meters, bearing) = Arrows.inverse(last.lat, last.lon, p.lat, p.lon)
        val circle = max(MIN_MOVE, hypot(last.acc ?: DEFAULT_ERROR, p.acc ?: DEFAULT_ERROR))
        if (meters <= circle) {
            // Still where the last point is (as far as the receiver can tell): the stop grows.
            waiting = null
            latest = null
            val still = ((p.time - last.time) / 1000).toInt()
            if (still >= STOP_SECONDS && still != last.still) { points[points.lastIndex] = last.copy(still = still); return true }
            return false
        }
        // Outside the circle. Slowly and only just: wait for the next position to say it is real.
        val clear = (speed ?: 0.0) >= WALK_SLOW || meters > 3 * circle
        if (!clear && waiting == null) { waiting = p; return false }
        val prev = latest ?: waiting
        waiting = null
        val out = heading ?: Arrows.inverse(last.lat, last.lon, (prev ?: p).lat, (prev ?: p).lon).second.also { heading = it }
        if (prev != null) {
            val (step, way) = Arrows.inverse(prev.lat, prev.lon, p.lat, p.lon)
            // A step tells its way only when it is well longer than the receiver's error.
            val sure = max(MIN_MOVE, 2 * hypot(prev.acc ?: DEFAULT_ERROR, p.acc ?: DEFAULT_ERROR))
            // A turn: this step goes another way than the device left the last point in. The point is
            // where the way turned: the position before this one.
            if (step >= sure && Arrows.inverse(last.lat, last.lon, prev.lat, prev.lon).first >= TURN_METERS && turn(out, way) > TURN_DEGREES) {
                keep(prev)
                heading = way
                latest = p
                return true
            }
            // Too short a step to tell its way: the earlier position stays the one to measure from.
            if (step < sure && p.time - last.time < 1000L * interval) return false
        }
        if (p.time - last.time >= 1000L * interval) { keep(p); return true }
        latest = p
        return false
    }

    private fun keep(p: TrackPoint) {
        points += p
        heading = null
        latest = null
        waiting = null
    }

    /** At Stop or Pause: the way since the last point is not lost; the newest position away from it is kept. */
    fun flush(): Boolean {
        val p = latest ?: waiting ?: return false
        keep(p)
        return true
    }

    companion object {
        /** Seconds between positions asked for: the interval, but at least every 15 s, for the turns. */
        fun sample(interval: Int) = minOf(interval, 15)
        const val MIN_MOVE = 5.0
        const val DEFAULT_ERROR = 10.0
        const val MAX_ERROR = 30.0
        const val TURN_DEGREES = 35.0
        const val TURN_METERS = 10.0
        const val STOP_SECONDS = 60
        /** Below this (m/s, about 2 mph) a move just outside the circle waits for the next position. */
        const val WALK_SLOW = 0.9

        /** The smaller angle between two bearings (0-180). */
        fun turn(a: Double, b: Double): Double = abs(((b - a) % 360 + 540) % 360 - 180)
    }
}

/** What a recorded track adds up to: its segments are the parts between Pause and Resume. */
class TrackStats(segments: List<List<TrackPoint>>) {
    val meters = segments.sumOf { s -> Routes.length(s.map { it.lat to it.lon }) }
    /** From the first point to the last of each segment, without the pauses. */
    val seconds = segments.sumOf { s -> if (s.isEmpty()) 0L else (s.last().time - s.first().time) / 1000 + s.last().still }
    val stoppedSeconds = segments.sumOf { s -> s.sumOf { it.still.toLong() } }
    val movingSeconds = (seconds - stoppedSeconds).coerceAtLeast(0)
    val points = segments.sumOf { it.size }
    /** Up and down in meters, from the heights the receiver gave, ignoring steps of less than CLIMB_STEP (its noise). */
    val climb: Pair<Double, Double>? = run {
        var up = 0.0
        var down = 0.0
        var any = false
        for (s in segments) {
            var base: Double? = null
            for (h in s.mapNotNull { it.alt }) {
                any = true
                val b = base
                if (b == null) { base = h; continue }
                if (h - b >= CLIMB_STEP) { up += h - b; base = h } else if (b - h >= CLIMB_STEP) { down += b - h; base = h }
            }
        }
        if (any) up to down else null
    }

    companion object {
        const val CLIMB_STEP = 5.0
    }
}
