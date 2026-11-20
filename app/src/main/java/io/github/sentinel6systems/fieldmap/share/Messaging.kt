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
import kotlinx.coroutines.launch
import java.io.File

/**
 * Short text messages between the devices of a server, by whichever way there is (see
 * MessageRoute): the server at home or through the remote link, or the mesh radio.
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
    private val relayed = HashMap<String, String>()         // ids passed on to a radio for the server -> the uid it is for
    private var fetching = false

    fun init(c: Context) {
        if (::context.isInitialized) return
        context = c.applicationContext
        prefs = Prefs(context)
        store = MessageStore(File(context.filesDir, "messages.json"))
    }

    /** Writes a message to one person (their uid) or to everybody (Message.EVERYONE). */
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
        if (!::store.isInitialized) return
        for (m in store.waiting()) {
            for (way in ways(m.to, m.id)) when (way) {
                MessageRoute.Way.SERVER -> post(m)
                MessageRoute.Way.MESH_DIRECT -> if (MeshShare.sendText(m, MeshShare.nodeOf[m.to])) sent(m.id, "mesh")
                MessageRoute.Way.MESH_BROADCAST -> if (MeshShare.sendText(m, null)) sent(m.id, "mesh")
            }
        }
    }

    private fun sent(id: String, via: String) {
        store.update(id) { if (it.status == MessageStatus.WAITING) it.copy(status = MessageStatus.SENT, via = via) else it }
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
            val meshReady = MeshRadio.status == MeshRadio.Status.READY
            val carried = if (meshReady) MeshShare.others().map { it.uid }.filter { it !in serverPeers && it in MeshShare.nodeOf }.take(7) else emptyList()
            val since = prefs.messageSeq.takeIf { prefs.messageSeqFor == base }
            val r = ShareClient.get(base + "messages?for=" + (listOf(me) + carried).joinToString(",") + (since?.let { "&since=$it" } ?: ""))
            val answer = (if (r.code == 200) MessageJson.fromServer(r.body) else null) ?: return
            val mine = ArrayList<String>()
            for (m in answer.messages) {
                if (m.to == me || m.to == Message.EVERYONE) receive(m)
                if (m.to == me) mine += m.id
                if (m.to in carried && m.id !in relayed && m.id !in MeshShare.textsHeard) {
                    if (MeshShare.sendText(m, MeshShare.nodeOf[m.to])) relayed[m.id] = m.to
                }
                // To everybody, from somebody the mesh has not heard: the people on the mesh get it from here.
                if (m.to == Message.EVERYONE && meshReady && m.from !in MeshShare.nodeOf && m.id !in MeshShare.textsHeard) MeshShare.sendText(m, null)
            }
            if (mine.isNotEmpty()) ShareClient.post(base + "messages/delivered", MessageJson.delivered(mine, me))
            for (id in answer.delivered) store.update(id) { it.copy(status = MessageStatus.DELIVERED) }
            prefs.messageSeq = answer.seq
            prefs.messageSeqFor = base
            if (answer.delivered.isNotEmpty()) onChange()
        } finally {
            fetching = false
        }
    }

    /** A message heard over the mesh. For somebody who is not on the mesh, or for everybody, it goes on to the server too. */
    fun fromMesh(m: Message) {
        if (!::store.isInitialized) return
        if (m.to == prefs.uid || m.to == Message.EVERYONE) receive(m)
        val forOthers = m.to != prefs.uid && (m.to == Message.EVERYONE || m.to !in MeshShare.nodeOf)
        if (forOthers && serverBase != null) post(m)
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
        val title = if (m.to == Message.EVERYONE) "${m.name} to Everyone" else m.name
        manager.notify(NOTIFIED, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title).setContentText(m.text).setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private const val CHANNEL = "messages"
    private const val NOTIFIED = 2
}
