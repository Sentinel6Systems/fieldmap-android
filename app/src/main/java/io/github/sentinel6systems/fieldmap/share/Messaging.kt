package io.github.sentinel6systems.fieldmap.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.sentinel6systems.fieldmap.R
import io.github.sentinel6systems.fieldmap.data.Message
import io.github.sentinel6systems.fieldmap.data.MessageJson
import io.github.sentinel6systems.fieldmap.data.MessageRoute
import io.github.sentinel6systems.fieldmap.data.MessageStatus
import io.github.sentinel6systems.fieldmap.data.MessageStore
import io.github.sentinel6systems.fieldmap.mesh.MeshRadio
import io.github.sentinel6systems.fieldmap.mesh.MeshShare
import io.github.sentinel6systems.fieldmap.ui.MainActivity
import io.github.sentinel6systems.fieldmap.ui.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Short text messages between the devices of a server, by whichever way there is (see
 * MessageRoute): the server at home or through the remote link, or the mesh radio.
 *
 * In the background (the app not on the screen) messages are only taken in: kept, and a
 * notification says one is waiting. Sending what waits and passing messages on (relaying)
 * happen while the app is on the screen.
 *
 * A device that has both the server and the mesh radio passes messages on (a relay): messages
 * on the server for people heard only over the mesh go to their radios, and messages heard over
 * the mesh for people not on it go to the server. Each message has an id, so that one that
 * comes both ways shows once.
 */
object Messaging {
    lateinit var store: MessageStore
        private set
    /** Something changed: a message came, or one of ours went out or arrived. */
    var onChange: () -> Unit = {}
    /** The app is on the screen (no notification then), and the conversation open in it, if any. */
    var shown = false
    var open: String? = null

    /** Where the server's sharing service answers now (its /mapshare/ base); null when it cannot be reached. */
    @Volatile var serverBase: String? = null
    /** The uids the server has positions of now. */
    @Volatile var serverPeers: Set<String> = emptySet()

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val posting = HashSet<String>()                 // ids on their way to the server
    private val notDirect = HashSet<String>()               // ids that did not reach a radio directly: another way next
    private val relayed = HashMap<String, String>()         // ids passed on (or to be) from the server to the mesh -> the uid it is for
    private val passedOn = LinkedHashMap<String, String>()  // ids passed on from the mesh to the server -> the uid that sent it
    private val noted = HashSet<String>()                   // ids whose sender was told over the mesh that they arrived
    private var fetching = false

    fun init(c: Context) {
        if (::context.isInitialized) return
        context = c.applicationContext
        prefs = Prefs(context)
        store = MessageStore(File(context.filesDir, "messages.json"))
    }

    /** Writes a message to one person (their uid), to everybody of the server (Message.EVERYONE), or on the public channel (Message.PUBLIC). */
    fun send(to: String, typed: String): Message? {
        val text = Message.clean(typed)
        if (text.isEmpty()) return null
        val m = Message(Message.newId(), prefs.uid, prefs.shareName.ifEmpty { "?" }, to, text, ShareJson.time(System.currentTimeMillis()), mine = true, read = true)
        store.add(m)
        onChange()
        push()
        return m
    }

    /** Which way a message to `to` would go now. */
    fun ways(to: String, id: String? = null): List<MessageRoute.Way> = MessageRoute.choose(
        to, serverBase != null, to in serverPeers, MeshRadio.status == MeshRadio.Status.READY,
        MeshShare.nodeOf[to].takeIf { id == null || id !in notDirect },
    )

    /** Sends what waits, each by the way there is now. */
    fun push() {
        if (!::store.isInitialized || !shown) return
        for (m in store.waiting()) {
            if (m.to == Message.PUBLIC) {
                if (MeshRadio.sendPublic(m.text)) sent(m.id, "mesh")
                continue
            }
            for (way in ways(m.to, m.id)) when (way) {
                MessageRoute.Way.SERVER -> post(m)
                MessageRoute.Way.MESH_DIRECT -> if (MeshShare.sendText(m, MeshShare.nodeOf[m.to])) sent(m.id, "mesh")
                MessageRoute.Way.MESH_BROADCAST -> if (MeshShare.sendText(m, null)) sent(m.id, "mesh")
            }
        }
        // Over the mesh and not confirmed: through the server as well, now that it can be reached.
        if (serverBase != null) {
            val now = System.currentTimeMillis()
            for (m in store.all) if (MessageRoute.retryThroughServer(m, now)) post(m)
        }
    }

    /** A message went out one way. One that goes both ways (to everybody) says so: "server+mesh". */
    private fun sent(id: String, via: String) {
        store.update(id) {
            when {
                it.status == MessageStatus.WAITING -> it.copy(status = MessageStatus.SENT, via = via)
                via !in it.via.split("+") -> it.copy(via = listOf(it.via, via).sorted().joinToString("+"))
                else -> it
            }
        }
        onChange()
    }

    private fun post(m: Message) {
        val base = serverBase ?: return
        if (!posting.add(m.id)) return
        scope.launch {
            val r = ShareClient.post(base + "messages", MessageJson.toServer(m))
            posting.remove(m.id)
            if (r.code == 200 && m.mine) sent(m.id, "server")
        }
    }

    /** Meshtastic answered for a message sent straight to a radio. */
    fun meshAnswer(id: String, ok: Boolean) {
        if (!::store.isInitialized) return
        val forUid = relayed.remove(id)
        if (forUid != null) {
            // Passed on for the server: it is told the message arrived, and keeps it no longer.
            val base = serverBase
            if (ok && base != null) scope.launch { ShareClient.post(base + "messages/delivered", MessageJson.delivered(listOf(id), forUid)) }
            return
        }
        if (ok) store.update(id) { it.copy(status = MessageStatus.DELIVERED, via = "mesh") }
        else {
            notDirect += id
            store.update(id) { if (it.status != MessageStatus.DELIVERED) it.copy(status = MessageStatus.WAITING) else it }
            push()
        }
        onChange()
    }

    /**
     * Asks the server for the messages for this device, and, with the mesh radio ready, for the
     * people heard only over the mesh (a relay); says which ones reached this device; learns
     * which of this device's messages were delivered.
     */
    suspend fun fetch(base: String) {
        if (fetching || !::store.isInitialized) return
        fetching = true
        try {
            val me = prefs.uid
            // A relay only while the app is on the screen; in the background just this device's messages.
            val meshReady = shown && MeshRadio.status == MeshRadio.Status.READY
            // People heard only over the mesh, who get their messages from a relay; and people whose
            // mesh messages were passed on to the server, who learn here that they were delivered.
            val carried = if (meshReady) MeshShare.others().map { it.uid }.filter { it !in serverPeers && it in MeshShare.nodeOf } else emptyList()
            val asked = (listOf(me) + carried + passedOn.values.takeIf { shown }.orEmpty()).distinct().take(MAX_FOR)
            val since = prefs.messageSeq.takeIf { prefs.messageSeqFor == base }
            val r = ShareClient.get(base + "messages?for=" + asked.joinToString(",") + (since?.let { "&since=$it" } ?: ""))
            val answer = (if (r.code == 200) MessageJson.fromServer(r.body) else null) ?: return
            val mine = ArrayList<String>()
            for (m in answer.messages) {
                if (m.to == me || m.to == Message.EVERYONE) receive(m)
                // Ours, and to everybody: the server is told this device has them.
                if (m.to == me || m.to == Message.EVERYONE) mine += m.id
                val forMesh = (m.to in carried && m.to in asked) || (m.to == Message.EVERYONE && m.from !in MeshShare.nodeOf)
                if (meshReady && forMesh && m.id !in MeshShare.textsHeard && m.id !in relayed) relayLater(m, base)
            }
            if (mine.isNotEmpty()) ShareClient.post(base + "messages/delivered", MessageJson.delivered(mine, me))
            for (id in answer.delivered) store.update(id) { it.copy(status = MessageStatus.DELIVERED) }
            for ((id, uids) in answer.group) store.update(id) { if (it.mine) it.copy(deliveredTo = it.deliveredTo + uids) else it }
            // Delivered messages of mesh people that a relay passed on: their senders are told over the mesh.
            val notes = answer.delivered.filter { it in passedOn && it !in noted }.groupBy { passedOn.getValue(it) }
            if (shown) for ((uid, ids) in notes) if (MeshShare.sendDelivered(uid, ids)) noted += ids
            prefs.messageSeq = answer.seq
            prefs.messageSeqFor = base
            if (answer.delivered.isNotEmpty() || answer.group.isNotEmpty()) onChange()
        } finally {
            fetching = false
        }
    }

    /** The devices heard both through the server and over the mesh, this one too: the relays. */
    private fun relays(): List<String> = (serverPeers.filter { it in MeshShare.nodeOf } + prefs.uid).distinct()

    /**
     * Passes a message from the server on over the mesh: at once when this device is the one to do
     * it (MessageRoute.relay), otherwise after a while and only when it has not gone out by then.
     */
    private fun relayLater(m: Message, base: String) {
        relayed[m.id] = m.to
        val first = MessageRoute.relay(m.id, relays()) == prefs.uid
        if (first) return relayNow(m)
        scope.launch {
            delay(MessageRoute.RELAY_BACKUP_MS)
            if (m.id in MeshShare.textsHeard) return@launch                     // a broadcast went out: heard
            if (m.to != Message.EVERYONE) {
                // Straight to a radio: nobody else hears that. The server says whether it is still waiting.
                val r = ShareClient.get(base + "messages?for=" + m.to)
                val waiting = (if (r.code == 200) MessageJson.fromServer(r.body) else null)?.messages?.any { it.id == m.id } ?: false
                if (!waiting) return@launch
            }
            relayNow(m)
        }
    }

    private fun relayNow(m: Message) {
        if (m.to == Message.EVERYONE) MeshShare.sendText(m, null)
        else if (!MeshShare.sendText(m, MeshShare.nodeOf[m.to])) relayed.remove(m.id)
    }

    /**
     * A device says over the mesh that it has these messages of ours (`by`; empty: the one they
     * were for, said by a relay). A message to a group counts the devices that have it.
     */
    fun deliveredOverMesh(ids: List<String>, by: String = "") {
        if (!::store.isInitialized) return
        for (id in ids) store.update(id) {
            when {
                !it.mine -> it
                it.to == Message.EVERYONE -> if (by.isEmpty()) it else it.copy(deliveredTo = it.deliveredTo + by)
                else -> it.copy(status = MessageStatus.DELIVERED)
            }
        }
        onChange()
    }

    /** Messages that came over the mesh without Meshtastic's own confirmation: their senders hear from us, gathered for a while. */
    private val notes = LinkedHashMap<String, MutableSet<String>>()
    private val sendNotes = Runnable {
        for ((uid, ids) in notes) MeshShare.sendDelivered(uid, ids.toList(), prefs.uid)
        notes.clear()
    }

    private fun noteLater(sender: String, id: String) {
        if (notes.isEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(sendNotes, NOTE_GATHER_MS)
        notes.getOrPut(sender) { LinkedHashSet() } += id
    }

    /**
     * A text heard on Meshtastic's public channel, from radio `node` named `name`. Kept like the
     * others, under Group (Public); no notification (the public channel can be busy).
     */
    fun publicHeard(node: Int, name: String, packet: Int, text: String, rxTime: Int) {
        if (!::store.isInitialized) return
        // Meshtastic's own texts may be longer than Fieldmap's: kept whole, on one line.
        val clean = text.replace(Regex("\\s+"), " ").filterNot { it.isISOControl() }.trim().take(240)
        if (clean.isEmpty()) return
        val at = (rxTime.toLong() and 0xffffffffL).takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis()
        val m = Message("p%08x%08x".format(node, packet), "node-%08x".format(node), ShareJson.name(name).ifEmpty { "?" }, Message.PUBLIC, clean,
            ShareJson.time(at), mine = false, status = MessageStatus.DELIVERED, via = "mesh", read = shown && open == Message.PUBLIC)
        if (store.add(m)) onChange()
    }

    /** A message heard over the mesh. For somebody who is not on the mesh, or for everybody, it goes on to the server too. */
    fun fromMesh(m: Message, direct: Boolean = false) {
        if (!::store.isInitialized) return
        if (m.to == prefs.uid || m.to == Message.EVERYONE) {
            receive(m)
            // Meshtastic confirms only what was sent straight to this radio; the rest we confirm ourselves.
            if (!direct) noteLater(m.from, m.id)
        }
        val forOthers = m.to != prefs.uid && (m.to == Message.EVERYONE || m.to !in MeshShare.nodeOf)
        if (forOthers && shown && serverBase != null) {
            post(m)
            if (m.to != Message.EVERYONE) {
                passedOn[m.id] = m.from
                while (passedOn.size > 200) passedOn.remove(passedOn.keys.first())
            }
        }
    }

    private fun receive(m: Message) {
        val reading = shown && open == m.partner
        if (!store.add(m.copy(read = reading))) return
        onChange()
        if (!shown) notify(m)
    }

    private fun notify(m: Message) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (m.to == Message.EVERYONE) "${m.name} · Group (Private)" else m.name
        manager.notify(NOTIFIED, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title).setContentText(m.text).setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private const val CHANNEL = "messages"
    private const val MAX_FOR = 8                           // devices one request to the server may ask for
    private const val NOTE_GATHER_MS = 20_000L              // delivery notes over the mesh are gathered into one packet
    private const val NOTIFIED = 2
}
