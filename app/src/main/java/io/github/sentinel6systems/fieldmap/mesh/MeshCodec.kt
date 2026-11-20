package io.github.sentinel6systems.fieldmap.mesh

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.Message
import io.github.sentinel6systems.fieldmap.data.MessageStatus
import io.github.sentinel6systems.fieldmap.mesh.proto.MeshMarker
import io.github.sentinel6systems.fieldmap.mesh.proto.MeshMessage
import io.github.sentinel6systems.fieldmap.mesh.proto.MeshPosition
import io.github.sentinel6systems.fieldmap.mesh.proto.MeshText
import io.github.sentinel6systems.fieldmap.share.Fix
import io.github.sentinel6systems.fieldmap.share.Peer
import io.github.sentinel6systems.fieldmap.share.ShareJson
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** A shared marker heard over the mesh: as it is now, or deleted (`marker` null) at `at` (UTC, ISO 8601). */
data class MeshMarkerChange(val id: String, val marker: Marker?, val at: String)

/**
 * Fieldmap's messages over the mesh (app/src/main/proto/fieldmap/mesh.proto) to and from what
 * the app works with. What does not make sense is dropped, as from the server (ShareJson).
 */
object MeshCodec {
    /** The app's id in 16 bytes when it is a UUID (as the app makes it), otherwise as text. */
    fun uidBytes(uid: String): ByteString = try {
        val u = UUID.fromString(uid)
        if (u.toString() != uid.lowercase()) throw IllegalArgumentException(uid)
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array().toByteString()
    } catch (e: IllegalArgumentException) { uid.toByteArray().toByteString() }

    fun uidText(bytes: ByteString): String? {
        val text = if (bytes.size == 16) bytes.asByteBuffer().let { UUID(it.long, it.long).toString() } else bytes.utf8()
        return text.takeIf { ShareJson.ID.matches(it) }
    }

    private fun degrees(v: Double) = (v * 1e7).roundToInt()

    /** This device's position. `moving` false: sent with speed 0, so the others know it stays where it is. */
    fun position(uid: String, name: String, fix: Fix, moving: Boolean): ByteArray = MeshMessage.ADAPTER.encode(MeshMessage(position = MeshPosition(
        uid = uidBytes(uid), name = ShareJson.name(name), lat = degrees(fix.lat), lon = degrees(fix.lon),
        alt = fix.alt?.roundToInt(), acc = fix.acc?.roundToInt()?.coerceAtLeast(0), course = fix.course?.roundToInt()?.mod(360),
        speed = if (moving) fix.speed?.let { (it * 100).roundToInt().coerceAtLeast(0) } else 0, time = (fix.time / 1000).toInt(),
    )))

    /** A shared marker as it is now. */
    fun marker(m: Marker): ByteArray = MeshMessage.ADAPTER.encode(MeshMessage(marker = MeshMarker(
        id = m.id, lat = degrees(m.lat), lon = degrees(m.lon), label = m.label, color = rgb(m.color),
        updated = (ShareJson.millis(m.updated) / 1000).toInt(), by_ = if (m.by.isEmpty()) ByteString.EMPTY else uidBytes(m.by),
    )))

    /** A shared marker deleted (or taken back) at `atMs`. */
    fun deleted(id: String, atMs: Long, by: String): ByteArray = MeshMessage.ADAPTER.encode(MeshMessage(marker = MeshMarker(
        id = id, updated = (atMs / 1000).toInt(), by_ = uidBytes(by), deleted = true,
    )))

    fun decode(bytes: ByteArray): MeshMessage? = try { MeshMessage.ADAPTER.decode(bytes) } catch (e: Exception) { null }

    /** Another device's position, `age` seconds old when it was heard. */
    fun peer(p: MeshPosition, nowMs: Long): Peer? {
        val uid = uidText(p.uid) ?: return null
        val lat = p.lat / 1e7
        val lon = p.lon / 1e7
        if (abs(lat) > 90 || abs(lon) > 180 || (p.lat == 0 && p.lon == 0)) return null
        // The sender's clock against this one: a little apart is normal, a lot is a wrong clock.
        val age = (nowMs / 1000 - (p.time.toLong() and 0xffffffffL)).takeIf { it in 0..3600 }?.toInt() ?: 0
        return Peer(uid, ShareJson.name(p.name).ifEmpty { "?" }, lat, lon, p.alt?.toDouble(), p.acc?.toDouble(), p.course?.toDouble(),
            p.speed?.let { it / 100.0 }, age, mesh = true)
    }

    fun change(m: MeshMarker): MeshMarkerChange? {
        if (!ShareJson.ID.matches(m.id)) return null
        val at = ShareJson.time((m.updated.toLong() and 0xffffffffL) * 1000)
        if (m.deleted) return MeshMarkerChange(m.id, null, at)
        val lat = m.lat / 1e7
        val lon = m.lon / 1e7
        if (abs(lat) > 90 || abs(lon) > 180) return null
        val by = uidText(m.by_).orEmpty()
        return MeshMarkerChange(m.id, Marker(m.id, lat, lon, m.label.filterNot { it.isISOControl() }.trim().take(Drawings.MAX_LABEL),
            "#%06x".format(m.color and 0xffffff), shared = true, updated = at, by = by), at)
    }

    /** What fits one Meshtastic packet. */
    const val MAX_PAYLOAD = 233

    /** A text message; without the sender's name when it would not fit, null when even that does not fit (many emoji). */
    fun text(m: Message): ByteArray? {
        fun encode(name: String) = MeshMessage.ADAPTER.encode(MeshMessage(text = MeshText(
            id = m.id, from = uidBytes(m.from), name = name, to = if (m.to == Message.EVERYONE) ByteString.EMPTY else uidBytes(m.to),
            text = m.text, time = (ShareJson.millis(m.time) / 1000).toInt(),
        )))
        return encode(m.name).takeIf { it.size <= MAX_PAYLOAD } ?: encode("").takeIf { it.size <= MAX_PAYLOAD }
    }

    /** A text message heard over the mesh; `name`: the sender's name when the message left it out. */
    fun message(t: MeshText, name: () -> String?): Message? {
        val from = uidText(t.from) ?: return null
        val to = if (t.to.size == 0) Message.EVERYONE else uidText(t.to) ?: return null
        val text = Message.clean(t.text)
        if (!ShareJson.ID.matches(t.id) || text.isEmpty()) return null
        val time = ShareJson.time((t.time.toLong() and 0xffffffffL) * 1000)
        return Message(t.id, from, ShareJson.name(t.name).ifEmpty { name() ?: "?" }, to, text, time, mine = false,
            status = MessageStatus.DELIVERED, via = "mesh")
    }

    fun rgb(hex: String): Int = hex.removePrefix("#").toIntOrNull(16)?.and(0xffffff) ?: 0
}
