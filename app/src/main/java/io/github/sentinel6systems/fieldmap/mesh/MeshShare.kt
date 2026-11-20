package io.github.sentinel6systems.fieldmap.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.share.Fix
import io.github.sentinel6systems.fieldmap.share.MeshChannel
import io.github.sentinel6systems.fieldmap.share.MeshMover
import io.github.sentinel6systems.fieldmap.share.Peer
import io.github.sentinel6systems.fieldmap.ui.Prefs
import kotlin.random.Random

/**
 * Location Sharing over the mesh radio: the same switch, the same people, the same shared
 * markers as through the server, carried by the radios when there is no network.
 *
 * - This device's position goes out at the server's mesh pace (MeshMover): every minute on the
 *   move, every ten minutes while it stays in one place.
 * - Shared markers go out when they are changed here. A marker change that cannot go out now
 *   waits (the latest per marker) until the radio is ready.
 * - When a Fieldmap device is heard for the first time in a while, the shared markers go out
 *   again, slowly, so that it has them too; the ones another device sent lately are left out.
 * - What is heard goes to the app (positions to the list of people, markers to the merge of
 *   ui/Sharing.kt); with the app off the screen, marker changes wait for it.
 */
object MeshShare {
    /** The shared markers on this device now; null while the app is not on the screen. */
    var markers: (() -> List<Marker>)? = null
    /** Somebody's position came, or got old. */
    var onPeers: () -> Unit = {}
    /** A shared marker changed over the mesh; null: keep them in `inbox` for later. */
    var onMarker: ((MeshMarkerChange) -> Unit)? = null
    /** Marker changes heard while nobody took them. */
    val inbox = ArrayList<MeshMarkerChange>()
    /** The radios (node numbers) that Fieldmap messages came from: the other Fieldmap devices. */
    val fieldmapNodes = HashSet<Int>()

    private class Heard(val peer: Peer, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private val peers = HashMap<String, Heard>()
    private val seen = HashMap<String, Long>()         // Fieldmap uids heard -> when
    private val markersHeard = HashMap<String, Long>()  // marker ids heard over the mesh -> when
    private val outbox = LinkedHashMap<String, ByteArray>()
    private var mover: MeshMover? = null
    private var moverFor: MeshChannel? = null
    private var sentAt = 0L
    private var lastFix = 0L
    private var flushing = false

    fun init(c: Context) {
        if (!::prefs.isInitialized) prefs = Prefs(c.applicationContext)
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** How a mesh position ages: (seconds between positions on the move, seconds until it is dropped). */
    fun pace(): Pair<Int, Int> {
        val c = prefs.meshChannel
        return (c?.moving ?: 60) to 3 * (c?.still ?: 600)
    }

    /** True when this device's position is going out over the mesh. */
    val sending get() = ::prefs.isInitialized && prefs.shareOn && MeshRadio.status == MeshRadio.Status.READY && sentAt != 0L

    /** The other Fieldmap devices heard over the mesh, each with its age as of now. */
    fun others(): List<Peer> {
        if (!::prefs.isInitialized) return emptyList()
        val stale = pace().second
        val t = now()
        peers.values.removeAll { it.peer.age + (t - it.at) / 1000 >= stale }
        return peers.values.map { it.peer.copy(age = it.peer.age + ((t - it.at) / 1000).toInt()) }
    }

    /** A new position of this device: it goes out when the mesh pace says so. */
    fun offer(fix: Fix?) {
        if (fix == null || !::prefs.isInitialized || !prefs.shareOn || fix.time == lastFix) return
        lastFix = fix.time
        val channel = prefs.meshChannel ?: return
        if (System.currentTimeMillis() - fix.time > 120_000) return       // too old to tell anybody
        if (moverFor != channel) { mover = MeshMover(channel); moverFor = channel }
        val m = mover ?: return
        val became = m.take(fix.lat, fix.lon, fix.time)
        val since = now() - sentAt
        val due = sentAt == 0L || since >= m.pace * 1000L || (became && m.moving && since >= channel.moving * 1000L)
        if (!due || MeshRadio.status != MeshRadio.Status.READY) return
        if (MeshRadio.sendApp(MeshCodec.position(prefs.uid, prefs.shareName, fix, m.moving))) sentAt = now()
    }

    /** Sharing switched off, or the radio went: the next position goes out at once when it is back. */
    fun reset() {
        sentAt = 0
    }

    /** A shared marker was changed here. */
    fun markerChanged(m: Marker) = queue(m.id, MeshCodec.marker(m))

    /** A shared marker was deleted here, or taken back. */
    fun markerDeleted(id: String) = queue(id, MeshCodec.deleted(id, System.currentTimeMillis(), prefs.uid))

    private fun queue(id: String, bytes: ByteArray) {
        if (!::prefs.isInitialized || prefs.meshChannel == null || prefs.radioAddress == null) return
        outbox.remove(id)
        outbox[id] = bytes
        // A name is typed letter by letter: it goes out when the typing has stopped.
        main.removeCallbacks(settled)
        main.postDelayed(settled, EDIT_WAIT_MS)
    }

    private val settled = Runnable { flush() }

    /** The radio is ready (again): what waits goes out. */
    fun ready() {
        sentAt = 0
        flush()
    }

    /** Sends what waits, one marker every so often: the mesh is slow and shared. */
    private fun flush() {
        if (flushing) return
        val (id, bytes) = outbox.entries.firstOrNull()?.toPair() ?: return
        if (!MeshRadio.sendApp(bytes)) return
        outbox.remove(id)
        if (outbox.isEmpty()) return
        flushing = true
        main.postDelayed({ flushing = false; flush() }, MARKER_GAP_MS)
    }

    /** Something another Fieldmap device sent over the mesh. */
    fun received(from: Int, bytes: ByteArray) {
        if (!::prefs.isInitialized) return
        val message = MeshCodec.decode(bytes) ?: return
        fieldmapNodes += from
        message.position?.let { p ->
            val peer = MeshCodec.peer(p, System.currentTimeMillis()) ?: return@let
            if (peer.uid == prefs.uid) return@let
            val last = seen[peer.uid]
            seen[peer.uid] = now()
            peers[peer.uid] = Heard(peer, now())
            if (last == null || now() - last > NEW_AFTER_MS) announceLater()
            onPeers()
        }
        message.marker?.let { m ->
            val change = MeshCodec.change(m) ?: return@let
            if (change.marker?.by == prefs.uid) return@let
            markersHeard[change.id] = now()
            onMarker?.invoke(change) ?: inbox.add(change)
        }
    }

    /** A Fieldmap device came near: after a random while (so that not all answer at once), the shared markers go out again. */
    private fun announceLater() = main.postDelayed({
        val list = markers?.invoke() ?: return@postDelayed
        val t = now()
        for (m in list.filter { it.shared }.take(MAX_ANNOUNCE)) {
            if ((markersHeard[m.id]?.let { t - it < RECENT_MS }) == true || m.id in outbox) continue
            outbox[m.id] = MeshCodec.marker(m)
        }
        flush()
    }, Random.nextLong(5_000, 60_000))

    private const val MARKER_GAP_MS = 15_000L
    private const val EDIT_WAIT_MS = 5_000L
    private const val NEW_AFTER_MS = 30 * 60_000L
    private const val RECENT_MS = 10 * 60_000L
    private const val MAX_ANNOUNCE = 50
}
