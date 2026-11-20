package io.github.sentinel6systems.fieldmap.ui

import android.content.Context
import androidx.core.content.edit
import io.github.sentinel6systems.fieldmap.data.Palette
import io.github.sentinel6systems.fieldmap.geo.CoordFormat
import io.github.sentinel6systems.fieldmap.offline.Tiles
import io.github.sentinel6systems.fieldmap.remote.Enrolment
import io.github.sentinel6systems.fieldmap.remote.NetBirdBlock
import io.github.sentinel6systems.fieldmap.remote.Secret
import io.github.sentinel6systems.fieldmap.remote.endpointsFrom
import io.github.sentinel6systems.fieldmap.remote.endpointsTo
import org.json.JSONArray
import org.json.JSONObject
import io.github.sentinel6systems.fieldmap.server.MapStyle
import io.github.sentinel6systems.fieldmap.server.TileServer
import io.github.sentinel6systems.fieldmap.share.MeshChannel
import io.github.sentinel6systems.fieldmap.share.Pending
import io.github.sentinel6systems.fieldmap.share.ShareJson
import java.util.UUID

/** What the app remembers between runs, all of it on the phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("fieldmap", Context.MODE_PRIVATE)

    /** The tile server's root address, ending in "/"; null until one is set. */
    var server: String?
        get() = p.getString("server", null)
        set(v) = p.edit { putString("server", v) }

    /** The server's styles as last seen, so that the list is there without the server. */
    var styles: List<MapStyle>
        get() = server?.let { TileServer.parseStyles(p.getString("styles", "[]").orEmpty(), it) }.orEmpty()
        set(v) = p.edit { putString("styles", TileServer.stylesToJson(v)) }

    var styleId: String?
        get() = p.getString("style", null)
        set(v) = p.edit { putString("style", v) }

    /** The colour of the next marker: the last one chosen. */
    var color: String
        get() = p.getString("color", Palette.DEFAULT) ?: Palette.DEFAULT
        set(v) = p.edit { putString("color", v) }

    /** The deepest zoom of the next map area: the last one chosen. */
    var areaZoom: Int
        get() = p.getInt("areaZoom", Tiles.MAX_ZOOM).coerceIn(Tiles.DEPTHS)
        set(v) = p.edit { putInt("areaZoom", v) }

    /** How the app writes positions: MGRS until the person chooses another format. */
    var coordFormat: CoordFormat
        get() = CoordFormat.entries.firstOrNull { it.name == p.getString("coordFormat", null) } ?: CoordFormat.MGRS
        set(v) = p.edit { putString("coordFormat", v.name) }

    /** Where the map was: latitude, longitude, zoom. */
    var camera: Triple<Double, Double, Double>?
        get() = if (p.contains("lat")) Triple(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), p.getFloat("zoom", 1f).toDouble()) else null
        set(v) = p.edit { if (v != null) { putFloat("lat", v.first.toFloat()); putFloat("lon", v.second.toFloat()); putFloat("zoom", v.third.toFloat()) } }

    /** What the box gave this phone when it paired it; null when not paired. */
    var enrolment: Enrolment?
        get() {
            val home = p.getString("remote.home", null) ?: return null
            val certificate = p.getString("remote.certificate", null) ?: return null
            val ca = p.getString("remote.ca", null) ?: return null
            val endpoints = try { endpointsFrom(JSONArray(p.getString("remote.endpoints", "[]"))) } catch (e: Exception) { emptyList() }
            return Enrolment(home, p.getString("remote.id", "").orEmpty(), certificate, ca, p.getString("remote.gateway", "").orEmpty(),
                endpoints, p.getString("remote.reach", "").orEmpty(), netbird())
        }
        set(v) = p.edit {
            listOf("home", "id", "certificate", "ca", "gateway", "endpoints", "reach", "host", "port",
                "nb.management", "nb.key", "nb.host", "nb.port", "nb.rev").forEach { remove("remote.$it") }
            if (v != null) {
                putString("remote.home", v.home); putString("remote.id", v.id); putString("remote.certificate", v.certificate)
                putString("remote.ca", v.ca); putString("remote.gateway", v.gatewayName)
                putString("remote.endpoints", endpointsTo(v.endpoints).toString()); putString("remote.reach", v.reach)
            }
            // The setup key is written only in a form that this app on this device can read.
            val sealed = v?.netbird?.let { block -> if (block.setupKey == opened?.second) opened?.first else Secret.seal(block.setupKey) }
            if (v?.netbird != null && sealed != null) {
                putString("remote.nb.management", v.netbird.management); putString("remote.nb.key", sealed)
                putString("remote.nb.host", v.netbird.host); putInt("remote.nb.port", v.netbird.port); putString("remote.nb.rev", v.netbird.rev)
                opened = sealed to v.netbird.setupKey
            } else {
                // No NetBird (any more), or not paired: nothing of the setup key stays.
                opened = null
                Secret.forget()
            }
        }

    /** The setup key as it is written down, and as it reads: opened once, not at every look. */
    private var opened: Pair<String, String>? = null

    private fun netbird(): NetBirdBlock? {
        val sealed = p.getString("remote.nb.key", null) ?: return null
        val key = opened?.takeIf { it.first == sealed }?.second ?: Secret.open(sealed)?.also { opened = sealed to it } ?: return null
        return NetBirdBlock(p.getString("remote.nb.management", "").orEmpty(), key, p.getString("remote.nb.host", "").orEmpty(),
            p.getInt("remote.nb.port", 0), p.getString("remote.nb.rev", "").orEmpty())
    }

    /**
     * The mesh radio channel of the server set now (null without one): taken from the server
     * while it answers, kept for when it cannot be reached. Written only in a form that this app
     * on this device can read; a channel of another server does not count.
     */
    var meshChannel: MeshChannel?
        get() {
            if (p.getString("mesh.server", null) != server) return null
            val sealed = p.getString("mesh.channel", null) ?: return null
            val text = meshOpened?.takeIf { it.first == sealed }?.second ?: Secret.open(sealed, Secret.MESH)?.also { meshOpened = sealed to it } ?: return null
            return ShareJson.mesh(text)
        }
        set(v) = p.edit {
            val sealed = v?.let { Secret.seal(ShareJson.meshToJson(it), Secret.MESH) }
            if (sealed != null) {
                putString("mesh.channel", sealed); putString("mesh.server", server)
            } else {
                remove("mesh.channel"); remove("mesh.server")
                Secret.forget(Secret.MESH)
            }
            meshOpened = null
        }

    /** The channel as it is written down, and as it reads: opened once, not at every look. */
    private var meshOpened: Pair<String, String>? = null

    /** The paired Meshtastic radio (its Bluetooth address and name); null for none. */
    var radioAddress: String?
        get() = p.getString("radio.address", null)
        set(v) = p.edit { if (v == null) remove("radio.address") else putString("radio.address", v) }

    var radioName: String?
        get() = p.getString("radio.name", null)
        set(v) = p.edit { if (v == null) remove("radio.name") else putString("radio.name", v) }

    /** The Bluetooth PIN the app gave the radio in place of the factory one; 0 for none. */
    var radioPin: Int
        get() = p.getInt("radio.pin", 0)
        set(v) = p.edit { putInt("radio.pin", v) }

    /** The public mesh channel: only listened to (the default), or the radio also shows itself there. */
    var meshReadOnly: Boolean
        get() = p.getBoolean("radio.readOnly", true)
        set(v) = p.edit { putBoolean("radio.readOnly", v) }

    /** Other Meshtastic radios on the map (Menu > Mesh Radio > Show on Map); on until switched off. */
    var meshNodesOnMap: Boolean
        get() = p.getBoolean("radio.nodesOnMap", true)
        set(v) = p.edit { putBoolean("radio.nodesOnMap", v) }

    /** What routes show (Menu > Settings > Routes); each on until switched off. */
    var routeDirection: Boolean
        get() = p.getBoolean("route.direction", true)
        set(v) = p.edit { putBoolean("route.direction", v) }
    var routeTotals: Boolean
        get() = p.getBoolean("route.totals", true)
        set(v) = p.edit { putBoolean("route.totals", v) }
    var routeLegs: Boolean
        get() = p.getBoolean("route.legs", true)
        set(v) = p.edit { putBoolean("route.legs", v) }
    var routeLegLabels: Boolean
        get() = p.getBoolean("route.legLabels", true)
        set(v) = p.edit { putBoolean("route.legLabels", v) }

    /** The last seq of the server's messages this device has, and the server (its /mapshare/ base) it is from. */
    var messageSeq: Long
        get() = p.getLong("messages.seq", 0)
        set(v) = p.edit { putLong("messages.seq", v) }

    var messageSeqFor: String?
        get() = p.getString("messages.for", null)
        set(v) = p.edit { putString("messages.for", v) }

    /** The radios of the other Fieldmap devices (node numbers), as heard over the mesh. */
    var fieldmapRadios: Set<Int>
        get() = p.getStringSet("radio.fieldmap", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit { putStringSet("radio.fieldmap", v.map { it.toString() }.toSet()) }

    /** The LoRa region the person chose for a radio that had none (a RegionCode name). */
    var radioRegion: String?
        get() = p.getString("radio.region", null)
        set(v) = p.edit { if (v == null) remove("radio.region") else putString("radio.region", v) }

    /** Tells this phone from the others on a box. Made once, at random; not a secret, and it says nothing about the phone. */
    val uid: String
        get() = p.getString("share.uid", null) ?: UUID.randomUUID().toString().also { p.edit { putString("share.uid", it) } }

    /** The name this phone's position is shared under. */
    var shareName: String
        get() = p.getString("share.name", "").orEmpty()
        set(v) = p.edit { putString("share.name", v) }

    /** Sharing this phone's position: off until the person switches it on. */
    var shareOn: Boolean
        get() = p.getBoolean("share.on", false)
        set(v) = p.edit { putBoolean("share.on", v) }

    /** Changes to shared markers that the box has not heard yet. */
    var pending: Pending
        get() {
            val deleted = try { JSONObject(p.getString("share.deleted", "{}").orEmpty()).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } } catch (e: Exception) { emptyMap() }
            return Pending(p.getStringSet("share.put", emptySet()).orEmpty().toSet(), deleted)
        }
        set(v) = p.edit {
            putStringSet("share.put", v.put)
            putString("share.deleted", JSONObject().apply { v.deleted.forEach { (id, at) -> put(id, at) } }.toString())
        }
}
