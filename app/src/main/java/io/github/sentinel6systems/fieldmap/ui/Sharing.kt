package io.github.sentinel6systems.fieldmap.ui

import android.os.SystemClock
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.server.Http
import io.github.sentinel6systems.fieldmap.server.Route
import io.github.sentinel6systems.fieldmap.share.Ageing
import io.github.sentinel6systems.fieldmap.share.Answer
import io.github.sentinel6systems.fieldmap.share.Peer
import io.github.sentinel6systems.fieldmap.share.Pending
import io.github.sentinel6systems.fieldmap.share.ShareClient
import io.github.sentinel6systems.fieldmap.share.ShareInfo
import io.github.sentinel6systems.fieldmap.share.ShareJson
import io.github.sentinel6systems.fieldmap.share.ShareLine
import io.github.sentinel6systems.fieldmap.share.ShareMerge
import io.github.sentinel6systems.fieldmap.share.ShareService
import io.github.sentinel6systems.fieldmap.share.ShareState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * Live sharing while the app is open: one round at the box's pace. A round sends this phone's
 * position (only while the person has that switched on), asks who else is there and which
 * shared markers changed, and sends the marker changes made here. Out of reach it does
 * nothing; what is waiting goes out when the box answers again.
 *
 * With the app in the background its rounds stop. The position is then kept shared by
 * share/ShareService.kt, which only sends; the two go by the same ShareLine.
 */
class Sharing(private val a: MainActivity) {
    /** What the server said about sharing. */
    enum class Box { UNKNOWN, NONE, OFF, ON }

    var box = Box.UNKNOWN
        private set
    // Where the box answers, its pace, and when the position last got through: the background service goes by them too.
    var info: ShareInfo
        get() = ShareLine.info
        private set(v) { ShareLine.info = v }
    private var base: String?
        get() = ShareLine.base
        set(v) { ShareLine.base = v }
    private var sentAt: Long
        get() = ShareLine.sentAt
        set(v) { ShareLine.sentAt = v }

    private var asked = 0L                      // when the box was last asked what it offers
    private var rev: Long? = null               // the marker changes this phone has seen
    private var peers: List<Peer> = emptyList()
    private var peersAt = 0L
    private var busy = false
    private var nextAt = 0L
    private var editedInRound = false
    private var leaveAfter = false
    private var saidOff = false
    private var drawn = ""
    private var said = ""

    private fun now() = SystemClock.elapsedRealtime()

    /** The person has switched sharing their position on. */
    val on get() = a.prefs.shareOn

    /** This phone's position is reaching the box now. */
    val live get() = on && a.reachable && sentAt != 0L && now() - sentAt < 3000L * info.interval + 5000

    /** The other phones, each with its age as of now; the ones that are too old are left out. */
    fun others(): List<Peer> {
        val waited = ((now() - peersAt) / 1000).toInt()
        return peers.map { it.copy(age = it.age + waited) }.filter { Ageing.look(it.age, info.interval, info.stale, it.still) != Ageing.Look.GONE }
    }

    fun other(uid: String): Peer? = others().firstOrNull { it.uid == uid }

    /* ---------- what the person does ---------- */

    fun switchOn(name: String) {
        a.prefs.shareName = ShareJson.name(name)
        a.prefs.shareOn = true
        leaveAfter = false
        nextAt = 0
        a.shareChanged()
    }

    /** Stops sending, and takes this phone's position off the box at once. */
    fun switchOff() {
        a.prefs.shareOn = false
        ShareService.stop(a)
        if (busy) leaveAfter = true else leave()
        a.shareChanged()
    }

    /** The app is being closed for good. */
    fun closing() {
        if (!ShareService.close() && on) leave()
    }

    private fun leave() {
        leaveAfter = false
        val to = base
        if (to != null && sentAt != 0L) ShareClient.send(to + "leave", ShareJson.leave(a.prefs.uid))
        sentAt = 0
    }

    /** Another server from now on: what was shared with the old one stays here, as this phone's own. */
    fun serverChanged() {
        if (on) leave()
        a.markers.replaceAll(a.markers.markers.map { if (it.shared) it.copy(shared = false, updated = "", by = "") else it })
        a.prefs.pending = Pending()
        box = Box.UNKNOWN
        base = null
        rev = null
        peers = emptyList()
        saidOff = false
        nextAt = 0
        a.peersChanged()
    }

    /** A shared marker was changed here: when, by whom, and the box is to hear of it. */
    fun edited(m: Marker): Marker {
        val p = a.prefs.pending
        a.prefs.pending = p.copy(put = p.put + m.id, deleted = p.deleted - m.id)
        soon()
        return m.copy(shared = true, updated = ShareJson.time(System.currentTimeMillis()), by = a.prefs.uid)
    }

    /** A shared marker was deleted here, or taken back to this phone alone: it goes from the box. */
    fun removed(id: String) {
        val p = a.prefs.pending
        a.prefs.pending = p.copy(put = p.put - id, deleted = p.deleted + (id to ShareJson.time(System.currentTimeMillis())))
        soon()
    }

    /** Still to be sent to the box. */
    fun waiting(id: String) = id in a.prefs.pending.put

    // A name is typed letter by letter: the box hears of it when the typing has stopped.
    private fun soon() {
        if (busy) editedInRound = true else nextAt = now() + EDIT_WAIT_MS
    }

    /* ---------- the rounds ---------- */

    /** Once a second while the app is on the screen. */
    fun tick() {
        // Positions grow older between answers, and with no answers at all.
        val look = others().joinToString { "${it.uid}:${(Ageing.opacity(it.age, info.interval, info.stale, it.still) * 10).toInt()}" }
        if (look != drawn) { drawn = look; a.peersChanged() }
        val words = summary() + live
        if (words != said) { said = words; a.shareChanged() }
        if (a.prefs.server == null || !a.reachable || busy || now() < nextAt) return
        busy = true
        editedInRound = false
        a.scope.launch {
            val wait = try { round() } catch (e: CancellationException) { throw e } catch (e: Exception) { info.interval * 1000L }
            busy = false
            if (leaveAfter) leave()
            nextAt = now() + if (editedInRound) minOf(wait, EDIT_WAIT_MS) else wait
        }
    }

    /** One round. Returns how long to wait before the next, in ms. */
    private suspend fun round(): Long {
        val home = a.prefs.server ?: return IDLE_MS
        if (box != Box.ON || now() - asked > ASK_AGAIN_MS) {
            ask(home)
            if (box != Box.ON) return when (box) { Box.NONE -> NONE_MS; Box.OFF -> OFF_MS; else -> IDLE_MS }
        }
        val to = base ?: return IDLE_MS
        val every = info.interval * 1000L

        if (on) a.fix()?.takeIf { System.currentTimeMillis() - it.time < info.stale * 1000L }?.let { fix ->
            val r = ShareClient.post(to + "position", ShareJson.position(a.prefs.uid, a.prefs.shareName, fix))
            if (r.code == 204 || r.code == 200) sentAt = now() else trouble(r)?.let { return it }
        }

        val r = ShareClient.get(to + "state" + (rev?.let { "?since=$it" } ?: ""))
        if (r.code != 200) return trouble(r) ?: every
        val state = ShareJson.state(r.body) ?: return every
        if (home != a.prefs.server || box != Box.ON) return 0      // another server was set meanwhile
        take(state)
        return push(to) ?: every
    }

    /** Asks the server what it offers. */
    private suspend fun ask(home: String) {
        asked = now()
        // From outside only the home address itself is carried to the box.
        val places = ShareClient.bases(home).let { if (Http.route == Route.REMOTE) it.take(1) else it }
        var answered = false
        for (place in places) {
            val r = ShareClient.get(place + "info")
            if (home != a.prefs.server) return
            val said = if (r.code == 200 || r.code == 503) ShareJson.info(r.body) else null
            when {
                r.code == 200 && said != null && said.enabled -> { info = said; base = place; saidOff = false; box = Box.ON; return }
                (r.code == 200 && said != null) || r.code == 503 -> { base = place; switchedOff(); return }
                r.code == 401 || r.code == 403 -> { a.probe(); box = Box.UNKNOWN; return }
                r.code != null -> answered = true
            }
        }
        // A server that answers but knows nothing of sharing has none; no answer at all says nothing.
        box = if (answered) Box.NONE else Box.UNKNOWN
        if (box == Box.NONE) peers = emptyList()
    }

    private fun switchedOff() {
        box = Box.OFF
        peers = emptyList()
        sentAt = 0
        rev = null
        if (!saidOff && (on || !a.prefs.pending.empty)) a.toast("Sharing is switched off on the box. Switch it on on the box's Phones page.")
        saidOff = true
    }

    /** An answer that was not the hoped-for one. Returns how long to wait, or null to carry on with the round. */
    private fun trouble(r: Answer): Long? = when (r.code) {
        null -> info.interval * 1000L                                   // out of reach: quietly, until it answers again
        503 -> { switchedOff(); OFF_MS }                                // switched off on the box: no faster than once a minute
        401, 403 -> { a.probe(); IDLE_MS }                              // the gateway: the probe says what it means
        404 -> { box = Box.UNKNOWN; OFF_MS }
        429 -> maxOf(2000L * info.interval, 10_000L)
        else -> null
    }

    private fun take(state: ShareState) {
        val since = rev
        // A count that went backwards: the box started over. Ask for everything the next time.
        rev = if (!state.full && since != null && state.rev < since) null else state.rev
        peers = state.positions.filter { it.uid != a.prefs.uid }
        peersAt = now()
        a.peersChanged()
        if (state.full || state.markers.isNotEmpty() || state.removed.isNotEmpty()) {
            val merged = ShareMerge.merge(a.markers.markers, state, a.prefs.pending)
            a.prefs.pending = merged.pending
            a.sharedMarkers(merged.markers)
        }
    }

    /** Sends what was changed here. Returns how long to wait when the box stopped answering, else null. */
    private suspend fun push(to: String): Long? {
        if (a.prefs.pending.empty) return null
        try { return send(to) } finally { a.shareChanged() }
    }

    private suspend fun send(to: String): Long? {
        for (id in a.prefs.pending.put) {
            val m = a.markers.get(id)?.takeIf { it.shared }
            val r = if (m != null) ShareClient.put(to + "markers/" + id, ShareJson.marker(m)) else null
            val p = a.prefs.pending
            when {
                m == null || r == null -> a.prefs.pending = p.copy(put = p.put - id)
                // Changed again while it travelled: the newer one goes the next time.
                r.code == 200 -> if (a.markers.get(id)?.updated == m.updated) a.prefs.pending = p.copy(put = p.put - id)
                // Refused as it is, or the box is full: asking again would change nothing.
                r.code == 400 || r.code == 413 || r.code == 507 -> {
                    a.prefs.pending = p.copy(put = p.put - id)
                    a.sharedMarkers(a.markers.markers.map { if (it.id == id) it.copy(shared = false, updated = "", by = "") else it })
                    val why = try { JSONObject(r.body).optString("error") } catch (e: JSONException) { "" }
                    a.toast("The box did not take the marker ${m.label.ifEmpty { "(no name)" }}: it stays on this phone only." + if (why.isEmpty()) "" else " The box says: $why")
                }
                else -> return trouble(r) ?: info.interval * 1000L
            }
        }
        for (id in a.prefs.pending.deleted.keys) {
            val r = ShareClient.delete(to + "markers/" + id)
            val p = a.prefs.pending
            when (r.code) {
                200, 204, 400, 404 -> a.prefs.pending = p.copy(deleted = p.deleted - id)     // gone, or never there
                else -> return trouble(r) ?: info.interval * 1000L
            }
        }
        return null
    }

    /* ---------- in words ---------- */

    /** One line for the Menu. */
    fun summary(): String {
        val mine = "My position: " + if (on) "on" else "off"
        return when {
            a.prefs.server == null -> "Needs a box"
            box == Box.NONE -> "This server has none"
            box == Box.OFF -> "Switched off on the box"
            !a.reachable -> "$mine · out of reach now"
            box == Box.UNKNOWN -> mine
            else -> "$mine · " + others().size.let { n -> if (n == 0) "nobody else here" else if (n == 1) "1 other here" else "$n others here" }
        }
    }

    /** What sharing is and how it stands with this box. */
    fun about(): String = when {
        a.prefs.server == null -> "Sharing goes through a fieldmap-server box. Connect to one first, under Menu, Server."
        box == Box.NONE -> "This server has no sharing. A fieldmap-server box with sharing is needed."
        box == Box.OFF -> "Sharing is switched off on the box. Switch it on on the box's Phones page."
        !a.reachable -> "The box is out of reach now. Shared markers stay on this phone, and changes to them are sent when the box answers again."
        else -> "Phones that reach this box, at home or from outside, can show each other where they are and share markers."
    }

    /** How it stands with this phone's own position, while sharing it is on. */
    fun mine(): String = when {
        box == Box.NONE || box == Box.OFF || !a.reachable -> "On, but not sent now."
        live -> "On. The others see you as ${a.prefs.shareName}."
        a.fix() == null -> "On. Waiting for a position from GPS."
        else -> "On. Sending…"
    }

    private companion object {
        const val IDLE_MS = 15_000L
        /** Sharing switched off on the box: ask again no faster than this. */
        const val OFF_MS = 60_000L
        /** A server without sharing is asked again seldom. */
        const val NONE_MS = 600_000L
        /** How often a box that has sharing is asked again for its pace. */
        const val ASK_AGAIN_MS = 300_000L
        /** How long after the last change to a marker it is sent. */
        const val EDIT_WAIT_MS = 2000L
    }
}
