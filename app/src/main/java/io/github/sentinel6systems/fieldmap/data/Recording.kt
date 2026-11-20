package io.github.sentinel6systems.fieldmap.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.sentinel6systems.fieldmap.geo.TrackKeeper
import io.github.sentinel6systems.fieldmap.geo.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The track being recorded (Menu > Track Recording): Start, Pause, Resume, Stop. The positions
 * come from the background service (share/ShareService.kt), on the screen or not; which become
 * points is TrackKeeper's choice. Kept in a file at every new point, so a recording survives the
 * app being closed by Android; Stop makes it a track in the Markers list.
 */
object Recording {
    enum class State { OFF, ON, PAUSED }

    var state = State.OFF
        private set
    /** Seconds between points (one of INTERVALS). */
    var interval = 30
        private set
    /** When it was started (ms since 1970). */
    var started = 0L
        private set
    private val done = ArrayList<List<TrackPoint>>()     // segments before the last Pause
    private var keeper = TrackKeeper(interval)
    private var file: File? = null
    private val main = Handler(Looper.getMainLooper())

    /** The app's screen: the line, the panel, the top bar. */
    var onChange: () -> Unit = {}
    /** The background service: positions are asked for while it is on. */
    var onState: () -> Unit = {}

    val INTERVALS = listOf(15, 30, 60, 300)

    /** The segments so far, the one being recorded last. */
    val segments: List<List<TrackPoint>> get() = done + listOf(keeper.points.toList())

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "recording.json")
        load()
    }

    fun start(seconds: Int) {
        if (state != State.OFF) return
        interval = seconds
        started = System.currentTimeMillis()
        done.clear()
        keeper = TrackKeeper(interval)
        state = State.ON
        changed(service = true)
    }

    fun pause() {
        if (state != State.ON) return
        keeper.flush()
        state = State.PAUSED
        changed(service = true)
    }

    fun resume() {
        if (state != State.PAUSED) return
        // A new segment: the line has a gap where the recording was paused.
        if (keeper.points.isNotEmpty()) done += keeper.points.toList()
        keeper = TrackKeeper(interval)
        state = State.ON
        changed(service = true)
    }

    /** Another interval, from now on. */
    fun interval(seconds: Int) {
        interval = seconds
        keeper.interval = seconds
        changed(service = true)
    }

    /** Ends the recording. The track: segments of at least two points; null when nothing was recorded. */
    fun stop(): RecordedTrack? {
        if (state == State.OFF) return null
        keeper.flush()
        val segments = segments.filter { it.size >= 2 }.map { s -> s.map { it.copy(acc = null) } }
        state = State.OFF
        done.clear()
        keeper = TrackKeeper(interval)
        changed(service = true)
        return if (segments.isEmpty()) null else RecordedTrack(segments)
    }

    /** A position from the service (on the main thread); `speed` m/s when known. */
    fun take(p: TrackPoint, speed: Double?) {
        if (state != State.ON) return
        if (keeper.take(p, speed)) changed(service = false)
    }

    private fun changed(service: Boolean) {
        save()
        main.post {
            if (service) onState()
            onChange()
        }
    }

    private fun save() {
        val f = file ?: return
        if (state == State.OFF) { f.delete(); return }
        fun segment(s: List<TrackPoint>) = JSONArray().apply {
            for (p in s) put(JSONArray().put(p.lat).put(p.lon).put(p.alt ?: JSONObject.NULL).put(p.time).put(p.acc ?: JSONObject.NULL).put(p.still))
        }
        val o = JSONObject().put("state", state.name).put("interval", interval).put("started", started)
            .put("segments", JSONArray().apply { for (s in done) put(segment(s)) }).put("current", segment(keeper.points))
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { /* the next point tries again */ }
    }

    private fun load() {
        val o = try { JSONObject(file!!.readText()) } catch (e: Exception) { return }
        fun segment(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val p = a?.optJSONArray(i) ?: return@mapNotNull null
            TrackPoint(p.optDouble(0), p.optDouble(1), if (p.isNull(2)) null else p.optDouble(2), p.optLong(3), if (p.isNull(4)) null else p.optDouble(4), p.optInt(5))
        }
        state = try { State.valueOf(o.optString("state")) } catch (e: IllegalArgumentException) { return }
        interval = o.optInt("interval", 30).takeIf { it in INTERVALS } ?: 30
        started = o.optLong("started")
        val segments = o.optJSONArray("segments")
        for (i in 0 until (segments?.length() ?: 0)) done += segment(segments?.optJSONArray(i))
        keeper = TrackKeeper(interval).apply { points += segment(o.optJSONArray("current")) }
    }
}
