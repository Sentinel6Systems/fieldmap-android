package io.github.sentinel6systems.fieldmap.share

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.Palette
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.abs

// Live sharing through a fieldmap-server box (its /mapshare/ service): every phone that reaches
// the box, at home or through its gateway, sees the positions the others share and one common
// set of markers. This file is what travels and what is done with it; nothing here talks to
// the network or to Android, so all of it is covered by unit tests.

/** What the box says about sharing: seconds between posts and polls, and seconds until a position is dropped. */
data class ShareInfo(val enabled: Boolean, val interval: Int, val stale: Int)

/** Another phone's position as the box gave it. `age`: seconds since the box got it. */
data class Peer(
    val uid: String, val name: String, val lat: Double, val lon: Double,
    val alt: Double? = null, val acc: Double? = null, val course: Double? = null, val speed: Double? = null,
    val age: Int = 0,
) {
    /** Said to be staying where it is: such a phone sends seldom (see Pace), and is not old for that. */
    val still get() = speed != null && speed < 0.3
}

/** This phone's position as it is sent. `time`: when the fix was taken, ms since 1970. */
data class Fix(val lat: Double, val lon: Double, val alt: Double?, val acc: Double?, val course: Double?, val speed: Double?, val time: Long)

/** One answer to "what is there": everyone's position, and the shared markers that changed since `since`. */
class ShareState(val rev: Long, val full: Boolean, val positions: List<Peer>, val markers: List<Marker>, val removed: List<String>)

/**
 * What the box has not heard yet: shared markers changed on this phone (`put`), and shared
 * markers deleted or taken back here (`deleted`, each with the time it was done).
 */
data class Pending(val put: Set<String> = emptySet(), val deleted: Map<String, String> = emptyMap()) {
    val empty get() = put.isEmpty() && deleted.isEmpty()
}

object ShareJson {
    /** What the box takes as a uid and as a marker's id. */
    val ID = Regex("^[A-Za-z0-9-]{8,64}$")
    const val MAX_NAME = 24

    /** A typed name as it is sent: one line, no more than the box takes. */
    fun name(typed: String): String = typed.filterNot { it.isISOControl() }.trim().take(MAX_NAME).trim()

    /** UTC, ISO 8601, to the second: 2026-01-31T12:00:00Z. */
    fun time(ms: Long): String = Instant.ofEpochSecond(Math.floorDiv(ms, 1000L)).toString()

    /** A time of the box or of another phone in ms; 0 when it cannot be read (it then loses to anything). */
    fun millis(iso: String): Long = try { Instant.parse(iso).toEpochMilli() } catch (e: Exception) {
        try { OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (e: Exception) { 0L }
    }

    /** The answer of `info`; null when the text is not that. */
    fun info(text: String): ShareInfo? {
        val o = try { JSONObject(text) } catch (e: JSONException) { return null }
        if (o.optInt("version", 0) < 1 && !o.has("enabled")) return null
        val interval = o.optInt("interval", 5).coerceIn(2, 600)
        return ShareInfo(o.optBoolean("enabled", true), interval, o.optInt("stale", 120).coerceIn(2 * interval, 86_400))
    }

    fun position(uid: String, name: String, fix: Fix): String {
        val o = JSONObject().put("uid", uid).put("name", name).put("lat", round(fix.lat, 7)).put("lon", round(fix.lon, 7))
        fix.alt?.let { o.put("alt", round(it, 1)) }
        fix.acc?.let { o.put("acc", round(it, 1)) }
        fix.course?.let { o.put("course", round(it, 1)) }
        fix.speed?.let { o.put("speed", round(it, 2)) }
        return o.put("time", time(fix.time)).toString()
    }

    fun leave(uid: String): String = JSONObject().put("uid", uid).toString()

    /** A shared marker as the box keeps it: the app's own marker feature, with its id on the feature too. */
    fun marker(m: Marker): String = Drawings.feature(m.copy(shared = true), sharing = true).put("id", m.id).toString()

    /** The answer of `state`; null when the text is not that. What cannot be a position or a marker is left out. */
    fun state(text: String): ShareState? {
        val o = try { JSONObject(text) } catch (e: JSONException) { return null }
        if (!o.has("rev")) return null
        val positions = ArrayList<Peer>()
        val list = o.optJSONArray("positions")
        for (i in 0 until (list?.length() ?: 0)) {
            val p = list?.optJSONObject(i) ?: continue
            val uid = p.optString("uid")
            val lat = p.optDouble("lat")
            val lon = p.optDouble("lon")
            if (!ID.matches(uid) || lat.isNaN() || lon.isNaN() || abs(lat) > 90 || abs(lon) > 180) continue
            positions += Peer(
                uid, name(p.optString("name")).ifEmpty { "?" }, lat, lon,
                number(p, "alt"), number(p, "acc"), number(p, "course"), number(p, "speed"), p.optInt("age", 0).coerceAtLeast(0),
            )
        }
        val markers = ArrayList<Marker>()
        val features = o.optJSONArray("markers")
        for (i in 0 until (features?.length() ?: 0)) {
            val f = features?.optJSONObject(i) ?: continue
            val properties = f.optJSONObject("properties")
            val id = (f.opt("id") as? String) ?: (properties?.optJSONObject("fieldmap")?.opt("id") as? String) ?: continue
            if (!ID.matches(id)) continue
            // Read as any marker file is: a point, its name cut to length, its colour checked.
            val read = try { Drawings.fromGeoJson(f.toString(), Palette.DEFAULT) { id }.markers.singleOrNull() } catch (e: IllegalArgumentException) { null } ?: continue
            markers += read.copy(id = id, shared = true, updated = properties?.optString("updated").orEmpty(), by = properties?.optString("by").orEmpty())
        }
        val removed = o.optJSONArray("removed").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a?.opt(it) as? String } }
        return ShareState(o.optLong("rev"), o.optBoolean("full"), positions, markers, removed)
    }

    private fun number(o: JSONObject, key: String): Double? = o.optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() }

    private fun round(v: Double, places: Int): Double = java.math.BigDecimal(v).setScale(places, java.math.RoundingMode.HALF_UP).toDouble()
}

/**
 * Brings the markers on the phone and an answer of the box together. The last change wins,
 * by its `updated` time. Markers that are not shared are never touched, with one exception:
 * a marker that arrives under the id of one that is here is that marker.
 */
object ShareMerge {
    class Result(val markers: List<Marker>, val pending: Pending)

    fun merge(local: List<Marker>, state: ShareState, pending: Pending): Result {
        val remote = LinkedHashMap<String, Marker>()
        for (m in state.markers) remote[m.id] = m
        // An id that was removed and then put again is there.
        val removed = state.removed.toSet() - remote.keys

        // A delete made here stands while the box has nothing newer; it is done with once the
        // marker is gone there.
        val deleted = pending.deleted.filter { (id, at) ->
            val there = remote[id]
            if (there != null) ShareJson.millis(there.updated) <= ShareJson.millis(at) else id !in removed && !state.full
        }
        val put = pending.put.toMutableSet()
        val out = ArrayList<Marker>()
        val here = HashSet<String>()
        for (m in local) {
            here += m.id
            val there = remote[m.id]
            when {
                // Taken back here, kept on the phone: the box is still to be told.
                there != null && m.id in deleted -> out += m
                // Changed on both sides: this phone's change stays only if it is the later one.
                there != null -> if (m.shared && m.id in put && ShareJson.millis(m.updated) >= ShareJson.millis(there.updated)) out += m else { out += there; put -= m.id }
                !m.shared -> out += m
                // Changed here and not sent yet: it goes to the box again, even if it was deleted there.
                m.id in put -> out += m
                m.id in removed || state.full -> {}
                else -> out += m
            }
        }
        for (m in remote.values) if (m.id !in here && m.id !in deleted) out += m
        put.retainAll(out.filter { it.shared }.map { it.id }.toSet())
        return Result(out, Pending(put, deleted))
    }
}

/** How a position looks as it gets older: as it was, greyed and fading, gone. */
object Ageing {
    enum class Look { FRESH, OLD, GONE }

    /**
     * Seconds a position counts as live. A phone on the move sends every few of the box's
     * intervals, and a post may go missing on a poor link; one that stays where it is sends
     * about twice before the box would drop it.
     */
    fun freshFor(interval: Int, stale: Int, still: Boolean = false): Int =
        (if (still) stale * 3 / 4 else minOf(maxOf(6 * interval, 30), stale / 2)).coerceAtLeast(1)

    fun look(age: Int, interval: Int, stale: Int, still: Boolean = false): Look = when {
        age >= stale -> Look.GONE
        age >= freshFor(interval, stale, still) -> Look.OLD
        else -> Look.FRESH
    }

    /** 1 while live, then from 0.85 down to 0.4 at the moment it is dropped. */
    fun opacity(age: Int, interval: Int, stale: Int, still: Boolean = false): Float {
        val fresh = freshFor(interval, stale, still)
        return when (look(age, interval, stale, still)) {
            Look.FRESH -> 1f
            Look.GONE -> 0f
            Look.OLD -> 0.85f - 0.45f * (age - fresh) / (stale - fresh).coerceAtLeast(1)
        }
    }

    fun text(age: Int): String = when {
        age < 5 -> "now"
        age < 90 -> "$age s ago"
        age < 5400 -> "${age / 60} min ago"
        else -> "${age / 3600} h ago"
    }
}

/**
 * How often a phone takes and sends its position while the app is not on the screen. Every
 * position costs the satellite receiver and the radio a wake-up, so: a few of the box's
 * intervals apart on the move, and about twice in the time the box keeps a position while the
 * phone stays where it is.
 */
object Pace {
    /** Seconds between positions on the move. */
    fun moving(info: ShareInfo): Int = (4 * info.interval).coerceIn(15, maxOf(15, info.stale / 3))

    /** Seconds between positions while staying in one place. */
    fun still(info: ShareInfo): Int = maxOf(moving(info), info.stale / 2)
}

/** Tells from its positions alone whether a phone is on the move or staying where it is. */
class Mover {
    var still = false
        private set
    private var anchor: Pair<Double, Double>? = null
    private var quiet = 0

    /** Takes a position. Returns true when `still` changed with it. */
    fun take(lat: Double, lon: Double, acc: Double?, speed: Double?): Boolean {
        val was = still
        val from = anchor
        // Away from where it rested by more than the position's own doubt explains, or
        // travelling at a walk or more by a position good enough to say so.
        val moved = from != null && metres(from.first, from.second, lat, lon) > maxOf(MIN_MOVE, 2 * (acc ?: 0.0))
        val travelling = (speed ?: 0.0) >= WALK && (acc ?: 0.0) <= GOOD
        if (from == null || moved || travelling) {
            anchor = lat to lon
            quiet = 0
            still = false
        } else if (++quiet >= SETTLE) {
            still = true
        }
        return still != was
    }

    /** The phone's motion sensor says it is being carried off. Returns true when `still` changed. */
    fun nudge(): Boolean {
        val was = still
        quiet = 0
        still = false
        return was
    }

    companion object {
        const val MIN_MOVE = 30.0       // metres
        const val WALK = 1.0            // metres a second
        const val GOOD = 50.0           // metres: a rougher position's speed says nothing
        /** Positions in a row without movement before the phone counts as staying. */
        const val SETTLE = 3

        /** Metres between two places, on a sphere: good to a few parts in a thousand. */
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return 2 * 6_371_008.8 * Math.asin(Math.sqrt(h))
        }
    }
}
