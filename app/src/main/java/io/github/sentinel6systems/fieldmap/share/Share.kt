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

/**
 * What the box says about sharing: seconds between posts and polls, and seconds until a position
 * is dropped. `mesh`: the rev of the server's mesh radio channel ("" when it gives none), to tell
 * when the channel changed.
 */
data class ShareInfo(val enabled: Boolean, val interval: Int, val stale: Int, val mesh: String = "", val messages: Boolean = false)

/**
 * The server's private channel for Meshtastic radios, as the server gave it out: what the app
 * writes into the paired radio, and how often a position goes out over the mesh (seconds on the
 * move and while staying in one place; moving = farther than `move` meters in `moving` seconds).
 * `psk`: the AES-256 key, Base64; a secret.
 */
data class MeshChannel(
    val name: String, val psk: String, val preset: String, val hopLimit: Int, val moving: Int, val still: Int, val move: Int, val rev: String,
) {
    override fun toString() = "MeshChannel($name, rev $rev)"       // never the key, in a log or anywhere else
}

/** Another phone's position as the box gave it. `age`: seconds since the box got it. */
data class Peer(
    val uid: String, val name: String, val lat: Double, val lon: Double,
    val alt: Double? = null, val acc: Double? = null, val course: Double? = null, val speed: Double? = null,
    val age: Int = 0,
    /** Heard over the mesh radio, which is slower than the server: it ages by the mesh pace. */
    val mesh: Boolean = false,
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
        val mesh = (o.opt("mesh") as? String)?.takeIf { REV.matches(it) }.orEmpty()
        return ShareInfo(o.optBoolean("enabled", true), interval, o.optInt("stale", 120).coerceIn(2 * interval, 86_400), mesh, o.optBoolean("messages"))
    }

    private val REV = Regex("^[A-Za-z0-9-]{1,64}$")
    /** Meshtastic's channel names: up to 11 characters; ours are letters, digits and dashes. */
    private val CHANNEL = Regex("^[A-Za-z0-9-]{1,11}$")
    private val PRESETS = setOf("LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "LONG_TURBO", "MEDIUM_FAST", "MEDIUM_SLOW",
        "SHORT_FAST", "SHORT_SLOW", "SHORT_TURBO")

    /** The answer of `mesh`; null when the text is not a channel this app can use. */
    fun mesh(text: String): MeshChannel? {
        val o = try { JSONObject(text) } catch (e: JSONException) { return null }
        val name = o.opt("name") as? String ?: return null
        val psk = o.opt("psk") as? String ?: return null
        val rev = o.opt("rev") as? String ?: return null
        val key = try { java.util.Base64.getDecoder().decode(psk) } catch (e: IllegalArgumentException) { return null }
        if (o.optInt("version", 0) != 1 || !CHANNEL.matches(name) || key.size != 32 || !REV.matches(rev)) return null
        val preset = (o.opt("preset") as? String)?.takeIf { it in PRESETS } ?: "LONG_FAST"
        val moving = o.optInt("moving", 60).coerceIn(60, 3600)
        return MeshChannel(name, psk, preset, o.optInt("hopLimit", 3).coerceIn(1, 7), moving, o.optInt("still", 600).coerceIn(moving, 7200),
            o.optInt("move", 15).coerceIn(5, 500), rev)
    }

    fun meshToJson(c: MeshChannel): String = JSONObject().put("version", 1).put("name", c.name).put("psk", c.psk).put("preset", c.preset)
        .put("hopLimit", c.hopLimit).put("moving", c.moving).put("still", c.still).put("move", c.move).put("rev", c.rev).toString()

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
            // Read as any marker file is: a point, its name cut to length, its colour checked. Routes
            // are not shared yet: a line of more than two points is left out.
            val read = try { Drawings.fromGeoJson(f.toString(), Palette.DEFAULT) { id }.markers.singleOrNull() } catch (e: IllegalArgumentException) { null }
                ?.takeIf { !it.isRoute } ?: continue
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
                // A plain marker never takes the place of an arrow: it is a copy from an app that does
                // not know arrows. The arrow stays, and a shared one goes to the box again.
                there != null && m.isArrow && !there.isArrow -> { out += m; if (m.shared) put += m.id }
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

/**
 * Over the mesh: tells whether this device is moving, by the server's rule. Moving is farther
 * than `channel.move` meters within `channel.moving` seconds; less counts as staying where it
 * is, so that a position wandering a few meters does not fill the mesh. Starts as moving, so
 * that the first position goes out soon.
 */
class MeshMover(private val channel: MeshChannel) {
    var moving = true
        private set
    private var anchor: Triple<Double, Double, Long>? = null

    /** Takes a position (`time`: ms). Returns true when `moving` changed with it. */
    fun take(lat: Double, lon: Double, time: Long): Boolean {
        val was = moving
        val from = anchor
        if (from == null) {
            anchor = Triple(lat, lon, time)
            return false
        }
        val far = Mover.metres(from.first, from.second, lat, lon) > channel.move
        val over = time - from.third >= channel.moving * 1000L
        if (far || over) {
            moving = far
            anchor = Triple(lat, lon, time)
        }
        return moving != was
    }

    /** Seconds until the next position goes out over the mesh. */
    val pace get() = if (moving) channel.moving else channel.still
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
