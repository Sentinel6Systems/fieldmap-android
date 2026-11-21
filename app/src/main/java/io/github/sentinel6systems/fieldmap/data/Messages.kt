package io.github.sentinel6systems.fieldmap.data

import io.github.sentinel6systems.fieldmap.share.ShareJson
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

// Short text messages between the devices of one server: to one device, or to everybody ("*").
// They go through the server when it can be reached, and over the mesh radio when not (see
// share/Messaging.kt). This file is what a message is, how it is kept on the device, how it
// travels to the server, and which way it takes; nothing here talks to the network.

enum class MessageStatus { WAITING, SENT, DELIVERED }

/**
 * One message. `to`: the uid it is for, or "*" for everybody. `time`: when it was written (UTC,
 * ISO 8601). `mine`: written on this device. `via`: "server" or "mesh", how it went out or came in.
 */
data class Message(
    val id: String, val from: String, val name: String, val to: String, val text: String, val time: String,
    val mine: Boolean, val status: MessageStatus = MessageStatus.WAITING, val via: String = "", val read: Boolean = false,
    /** For one of ours to a group: the devices that said they have it. */
    val deliveredTo: Set<String> = emptySet(),
) {
    /** The conversation it belongs to: the other person's uid, or "*" for everybody. */
    val partner get() = if (to == EVERYONE || to == PUBLIC) to else if (mine) to else from

    companion object {
        /** Everybody of this server: Group (Private), on the server and the server's mesh channel. */
        const val EVERYONE = "*"
        /** Everybody on Meshtastic's public channel: Group (Public), over the mesh radio only. */
        const val PUBLIC = "#public"
        /** As many characters as fit one mesh radio packet with who sent it. */
        const val MAX = 140

        fun newId(): String = "m" + java.lang.Long.toString(System.currentTimeMillis(), 36) + Random.nextLong(1L shl 40).toString(36)

        /** A typed text as it is sent: one line, no more than fits. */
        fun clean(typed: String): String = typed.replace(Regex("\\s+"), " ").filterNot { it.isISOControl() }.trim().take(MAX)
    }
}

/** The messages on this device, kept in one file in the app's own storage; the newest 1000. */
class MessageStore(private val file: File) {
    var all: List<Message> = load()
        private set

    private fun load(): List<Message> = try {
        if (!file.isFile) emptyList() else JSONArray(file.readText()).let { a -> (0 until a.length()).mapNotNull { MessageJson.fromStore(a.optJSONObject(it)) } }
    } catch (e: Exception) {
        file.renameTo(File(file.parentFile, file.name + ".unreadable"))
        emptyList()
    }

    /** Adds a message; false when it is here already (it came both ways). */
    fun add(m: Message): Boolean {
        if (all.any { it.id == m.id }) return false
        all = (all + m).takeLast(KEEP)
        save()
        return true
    }

    fun get(id: String): Message? = all.firstOrNull { it.id == id }

    fun update(id: String, change: (Message) -> Message) {
        val now = all.map { if (it.id == id) change(it) else it }
        if (now != all) { all = now; save() }
    }

    fun markRead(partner: String) {
        if (all.none { it.partner == partner && !it.read }) return
        all = all.map { if (it.partner == partner) it.copy(read = true) else it }
        save()
    }

    fun conversation(partner: String): List<Message> = all.filter { it.partner == partner }

    fun unread(partner: String? = null): Int = all.count { !it.mine && !it.read && (partner == null || it.partner == partner) }

    /** Everybody this device has messages with, the latest conversation first. */
    fun partners(): List<String> = all.reversed().map { it.partner }.distinct()

    /** The name a person had in their last message here. */
    fun nameOf(uid: String): String? = all.lastOrNull { !it.mine && it.from == uid }?.name

    fun waiting(): List<Message> = all.filter { it.mine && it.status == MessageStatus.WAITING }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { all.forEach { put(MessageJson.toStore(it)) } }.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object { const val KEEP = 1000 }
}

/**
 * What the server answers to GET /mapshare/messages. `group`: for messages these devices sent to
 * everybody, the uids that have them.
 */
class ServerMessages(val seq: Long, val messages: List<Message>, val delivered: List<String>, val group: Map<String, Set<String>> = emptyMap())

object MessageJson {
    fun toServer(m: Message): String = JSONObject().put("id", m.id).put("from", m.from).put("name", m.name).put("to", m.to)
        .put("text", m.text).put("time", m.time).toString()

    fun delivered(ids: Collection<String>, by: String): String = JSONObject().put("ids", JSONArray(ids.toList())).put("by", by).toString()

    /** The server's answer; null when the text is not that. What is not a message is left out. */
    fun fromServer(text: String): ServerMessages? {
        val o = try { JSONObject(text) } catch (e: JSONException) { return null }
        if (!o.has("seq")) return null
        val list = o.optJSONArray("messages")
        val messages = (0 until (list?.length() ?: 0)).mapNotNull { i -> list?.optJSONObject(i)?.let { incoming(it, "server") } }
        val ids = o.optJSONArray("delivered")
        val g = o.optJSONObject("groupDelivered")
        val group = g?.keys()?.asSequence()?.associateWith { id ->
            g.optJSONArray(id)?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf(ShareJson.ID::matches) }.toSet() }.orEmpty()
        }.orEmpty()
        return ServerMessages(o.optLong("seq"), messages, (0 until (ids?.length() ?: 0)).mapNotNull { ids?.opt(it) as? String }, group)
    }

    /** A message that came in, checked as the server checks it. */
    fun incoming(o: JSONObject, via: String): Message? {
        val id = o.opt("id") as? String ?: return null
        val from = o.opt("from") as? String ?: return null
        val to = o.opt("to") as? String ?: return null
        val text = Message.clean(o.opt("text") as? String ?: return null)
        if (!ShareJson.ID.matches(id) || !ShareJson.ID.matches(from) || (to != Message.EVERYONE && !ShareJson.ID.matches(to)) || text.isEmpty()) return null
        val time = (o.opt("time") as? String)?.takeIf { ShareJson.millis(it) > 0 } ?: ShareJson.time(System.currentTimeMillis())
        return Message(id, from, ShareJson.name(o.optString("name")).ifEmpty { "?" }, to, text, time, mine = false, status = MessageStatus.DELIVERED, via = via)
    }

    fun toStore(m: Message): JSONObject = JSONObject().put("id", m.id).put("from", m.from).put("name", m.name).put("to", m.to).put("text", m.text)
        .put("time", m.time).put("mine", m.mine).put("status", m.status.name).put("via", m.via).put("read", m.read)
        .put("deliveredTo", JSONArray(m.deliveredTo.toList()))

    fun fromStore(o: JSONObject?): Message? {
        o ?: return null
        val status = MessageStatus.entries.firstOrNull { it.name == o.optString("status") } ?: MessageStatus.WAITING
        val to = o.optJSONArray("deliveredTo")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }.orEmpty()
        return Message(o.optString("id").ifEmpty { return null }, o.optString("from"), o.optString("name"), o.optString("to"), o.optString("text"),
            o.optString("time"), o.optBoolean("mine"), status, o.optString("via"), o.optBoolean("read"), to)
    }
}

/**
 * Which way a message goes. The server when this device reaches it and the person was seen
 * there; straight over the mesh when the person's radio is known; otherwise through the server
 * when it can be reached (it keeps the message until the person comes, or a device that has
 * both passes it on over the mesh); otherwise to everybody on the mesh (a device that has both
 * passes it on to the server). With none of that, it waits. A message to everybody goes every
 * way there is.
 */
object MessageRoute {
    enum class Way { SERVER, MESH_DIRECT, MESH_BROADCAST }

    /**
     * Which of the devices that have both the server and the mesh passes this message on first.
     * Every device works it out the same way, from the message's id and their uids, and a
     * different one is first for different messages; the others wait (RELAY_BACKUP_MS) and pass it
     * on only when it has not gone out by then.
     */
    fun relay(id: String, relays: Collection<String>): String? =
        relays.maxByOrNull { uid -> java.util.zip.CRC32().apply { update("$id/$uid".toByteArray()) }.value }

    const val RELAY_BACKUP_MS = 60_000L

    /**
     * A message of ours that went over the mesh only and is not confirmed: it also goes through the
     * server once that can be reached, which keeps it for the device it is for (a week), so that it
     * arrives when that device connects. Each message has an id: whoever has it already keeps it once.
     */
    fun retryThroughServer(m: Message, nowMs: Long): Boolean = m.mine && m.to != Message.PUBLIC && m.status == MessageStatus.SENT &&
        "server" !in m.via.split("+") && nowMs - ShareJson.millis(m.time) < 7 * 86_400_000L

    fun choose(to: String, serverUp: Boolean, onServer: Boolean, meshReady: Boolean, meshNode: Int?): List<Way> = when {
        to == Message.EVERYONE -> listOfNotNull(Way.SERVER.takeIf { serverUp }, Way.MESH_BROADCAST.takeIf { meshReady })
        serverUp && onServer -> listOf(Way.SERVER)
        meshReady && meshNode != null -> listOf(Way.MESH_DIRECT)
        serverUp -> listOf(Way.SERVER)
        meshReady -> listOf(Way.MESH_BROADCAST)
        else -> emptyList()
    }
}
