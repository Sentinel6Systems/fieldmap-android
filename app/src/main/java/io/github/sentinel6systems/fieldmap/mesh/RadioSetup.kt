package io.github.sentinel6systems.fieldmap.mesh

import io.github.sentinel6systems.fieldmap.share.MeshChannel
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleSettings
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import kotlin.math.floor

// A Meshtastic radio, as the app sets it up for the mesh of its server. Nothing here talks to
// Bluetooth or to Android, so all of it is covered by unit tests; mesh/RadioLink.kt carries the
// messages, mesh/MeshRadio.kt decides when.

/**
 * Another radio the paired one has heard: on the server's channel, or on the public channel (a
 * stranger's radio, which may pass our packets on). `heard`: when, in seconds since 1970;
 * `hops`: 0 = heard directly, null = not known. `lat`/`lon`: where it says it is, when it says
 * so; `precision`: the bits of that position it gives (Meshtastic's position precision; 0 or 32
 * = exact).
 */
data class MeshNode(
    val num: Int, val name: String, val heard: Long, val hops: Int?, val snr: Float, val channel: Int,
    val lat: Double? = null, val lon: Double? = null, val precision: Int = 0,
    /** What the radio says about itself, when it said it: short name, hardware model, battery percent (101 = external power). */
    val shortName: String = "", val model: String = "", val battery: Int? = null,
) {
    /** Takes what a User message says: names and model. */
    fun user(u: User?): MeshNode = if (u == null) this else copy(name = u.long_name.ifEmpty { name }, shortName = u.short_name.ifEmpty { shortName },
        model = u.hw_model.takeIf { it != org.meshtastic.proto.HardwareModel.UNSET }?.name ?: model)

    /** Meters around the position where the radio may be: 0 when it gives its exact position. */
    val radius: Double get() = if (precision in 1..31) 23_300.0 / Math.pow(2.0, (precision - 10).toDouble()) else 0.0

    /** The node with the position of a Position message, when that holds one. */
    fun at(p: Position?): MeshNode {
        val la = p?.latitude_i ?: return this
        val lo = p.longitude_i ?: return this
        if (la == 0 && lo == 0) return this
        val lat = la / 1e7
        val lon = lo / 1e7
        if (kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) return this
        return copy(lat = lat, lon = lon, precision = p.precision_bits)
    }
}

/** What the radio told the app about itself while it sent its settings (the want_config answer). */
data class RadioState(
    val nodeNum: Int = 0,
    val firmware: String = "",
    val model: String = "",
    val owner: User? = null,
    val lora: Config.LoRaConfig? = null,
    val bluetooth: Config.BluetoothConfig? = null,
    val channels: Map<Int, Channel> = emptyMap(),
    /** Percent; 101 = on external power; null when the radio did not say. */
    val battery: Int? = null,
    /** A hardened build that wants a passphrase first: the app cannot set it up. */
    val locked: Boolean = false,
    /** The other radios it has heard. */
    val nodes: Map<Int, MeshNode> = emptyMap(),
) {
    val region get() = lora?.region ?: Config.LoRaConfig.RegionCode.UNSET

    /** Takes one message of the radio's answer. */
    fun take(m: FromRadio): RadioState {
        var s = this
        m.my_info?.let { s = s.copy(nodeNum = it.my_node_num) }
        m.metadata?.let { s = s.copy(firmware = it.firmware_version, model = it.hw_model.name) }
        m.node_info?.let { n ->
            if (n.num == s.nodeNum) s = s.copy(owner = n.user ?: s.owner, battery = n.device_metrics?.battery_level ?: s.battery)
            else if (n.num != 0) s = s.copy(nodes = s.nodes + (n.num to MeshNode(n.num, n.user?.long_name.orEmpty().ifEmpty { nodeName(n.num) },
                n.last_heard.toLong() and 0xffffffffL, n.hops_away, n.snr, n.channel).at(n.position).user(n.user)
                .copy(battery = n.device_metrics?.battery_level)))
        }
        m.config?.lora?.let { s = s.copy(lora = it) }
        m.config?.bluetooth?.let { s = s.copy(bluetooth = it) }
        m.channel?.let { s = s.copy(channels = s.channels + (it.index to it)) }
        m.lockdown_status?.let { s = s.copy(locked = it.state == LockdownStatus.State.LOCKED || it.state == LockdownStatus.State.NEEDS_PROVISION) }
        return s
    }

    /** A packet heard from another radio, at `now` (seconds since 1970): it is there, and how near. */
    fun heard(p: MeshPacket, now: Long): RadioState {
        if (p.from == 0 || p.from == nodeNum) return this
        val before = nodes[p.from]
        val d = p.decoded
        val hops = if (p.hop_start > 0) (p.hop_start - p.hop_limit).coerceAtLeast(0) else before?.hops
        var node = (before ?: MeshNode(p.from, nodeName(p.from), now, hops, p.rx_snr, p.channel))
            .copy(heard = now, hops = hops, snr = if (hops == 0) p.rx_snr else before?.snr ?: p.rx_snr, channel = p.channel)
        if (d != null && d.portnum == PortNum.NODEINFO_APP) {
            node = node.user(try { User.ADAPTER.decode(d.payload) } catch (e: Exception) { null })
        }
        if (d != null && d.portnum == PortNum.POSITION_APP) {
            node = node.at(try { Position.ADAPTER.decode(d.payload) } catch (e: Exception) { null })
        }
        return copy(nodes = nodes + (p.from to node))
    }

    companion object {
        /** How a radio names another one it knows only by number: !1a2b3c4d. */
        fun nodeName(num: Int) = "!%08x".format(num)
    }
}

/** What the radio needs before it can carry the server's mesh. */
sealed class Setup {
    /** Nothing: it is set up. */
    data object Ready : Setup()
    /** Its region is not set, and the app does not choose one: the person does (the law of the place). */
    data object NeedsRegion : Setup()
    /** These settings, written between begin_edit_settings and commit_edit_settings. `pin`: a new Bluetooth PIN, when one is in them. */
    data class Change(val messages: List<AdminMessage>, val pin: Int?) : Setup()
}

object RadioSetup {
    /** The PIN that radios without a screen have from the factory: anybody near could pair with it. */
    const val FACTORY_PIN = 123456

    /**
     * The LoRa regions (MHz, from Meshtastic's firmware, src/mesh/RadioInterface.cpp) and
     * whether they use wide LoRa (2.4 GHz). Used only to find the public default frequency slot.
     */
    private val REGIONS = mapOf(
        "US" to (902.0 to 928.0), "EU_433" to (433.0 to 434.0), "EU_868" to (869.4 to 869.65), "CN" to (470.0 to 510.0),
        "JP" to (920.5 to 923.5), "ANZ" to (915.0 to 928.0), "ANZ_433" to (433.05 to 434.79), "RU" to (868.7 to 869.2),
        "KR" to (920.0 to 923.0), "TW" to (920.0 to 925.0), "IN" to (865.0 to 867.0), "NZ_865" to (864.0 to 868.0),
        "TH" to (920.0 to 925.0), "UA_433" to (433.0 to 434.7), "UA_868" to (868.0 to 868.6), "MY_433" to (433.0 to 435.0),
        "MY_919" to (919.0 to 924.0), "SG_923" to (917.0 to 925.0), "PH_433" to (433.0 to 434.7), "PH_868" to (868.0 to 869.4),
        "PH_915" to (915.0 to 918.0), "KZ_433" to (433.075 to 434.775), "KZ_863" to (863.0 to 868.0), "NP_865" to (865.0 to 868.0),
        "BR_902" to (902.0 to 907.5), "LORA_24" to (2400.0 to 2483.5),
    )

    /** The regions a person can choose, in the order shown. */
    val regions: List<Config.LoRaConfig.RegionCode> = Config.LoRaConfig.RegionCode.entries.filter { it.name in REGIONS }

    /** The presets' names as the firmware hashes them, and their bandwidth in kHz. */
    private val PRESETS = mapOf(
        Config.LoRaConfig.ModemPreset.LONG_FAST to ("LongFast" to 250.0), Config.LoRaConfig.ModemPreset.LONG_SLOW to ("LongSlow" to 125.0),
        Config.LoRaConfig.ModemPreset.LONG_MODERATE to ("LongMod" to 125.0), Config.LoRaConfig.ModemPreset.LONG_TURBO to ("LongTurbo" to 500.0),
        Config.LoRaConfig.ModemPreset.MEDIUM_FAST to ("MediumFast" to 250.0), Config.LoRaConfig.ModemPreset.MEDIUM_SLOW to ("MediumSlow" to 250.0),
        Config.LoRaConfig.ModemPreset.SHORT_FAST to ("ShortFast" to 250.0), Config.LoRaConfig.ModemPreset.SHORT_SLOW to ("ShortSlow" to 250.0),
        Config.LoRaConfig.ModemPreset.SHORT_TURBO to ("ShortTurbo" to 500.0),
    )

    fun preset(name: String): Config.LoRaConfig.ModemPreset =
        Config.LoRaConfig.ModemPreset.entries.firstOrNull { it.name == name && it in PRESETS } ?: Config.LoRaConfig.ModemPreset.LONG_FAST

    /** The firmware's hash of a name (djb2, 32 bits). */
    fun hash(text: String): Long {
        var h = 5381L
        for (b in text.toByteArray()) h = (h * 33 + (b.toInt() and 0xff)) and 0xffffffffL
        return h
    }

    /**
     * The frequency slot (1 and up) that radios with the factory channel use for this preset in
     * this region. Our channel has a name of its own, which would hash to another slot: set to
     * this one, the radios of other people on factory settings hear our packets and pass them
     * on (they cannot read them). 0 when the region is not known here: the firmware then hashes.
     */
    fun slot(region: Config.LoRaConfig.RegionCode, preset: Config.LoRaConfig.ModemPreset): Int {
        val (from, to) = REGIONS[region.name] ?: return 0
        val (name, khz) = PRESETS[preset] ?: return 0
        val bw = if (region.name == "LORA_24") khz * 3.25 else khz
        val count = floor((to - from) / (bw / 1000)).toLong()
        if (count <= 0) return 0
        return (hash(name) % count).toInt() + 1
    }

    /**
     * The radio's two channels. The server's channel (our name and key) carries Fieldmap; the
     * public channel (the factory one: no name, key 1) lets the app see the other radios around,
     * which may pass our packets on. Positions are off on both: the app sends its own.
     *
     * Read only (`readOnly`), the server's channel is the primary one: a radio sends its own
     * node information on its primary channel, so on the public channel it sends nothing of its
     * own (it still passes packets on, and answers a radio that asks who it is). Otherwise the
     * public channel is the primary one, and the other radios see this one.
     */
    fun channels(channel: MeshChannel, readOnly: Boolean): Map<Int, Channel> {
        val ours = ChannelSettings(name = channel.name, psk = java.util.Base64.getDecoder().decode(channel.psk).toByteString(),
            module_settings = ModuleSettings(position_precision = 0))
        val public = ChannelSettings(name = "", psk = PUBLIC_KEY, module_settings = ModuleSettings(position_precision = 0))
        val (first, second) = if (readOnly) ours to public else public to ours
        return mapOf(0 to Channel(index = 0, role = Channel.Role.PRIMARY, settings = first),
            1 to Channel(index = 1, role = Channel.Role.SECONDARY, settings = second))
    }

    /** The index of the server's channel on the radio. */
    fun ourIndex(readOnly: Boolean) = if (readOnly) 0 else 1

    /** The index of the public channel on the radio. */
    fun publicIndex(readOnly: Boolean) = 1 - ourIndex(readOnly)

    /** The public channel's key: Meshtastic's "default key" number 1. */
    private val PUBLIC_KEY = byteArrayOf(1).toByteString()

    private fun same(a: Channel?, b: Channel): Boolean {
        if (b.role == Channel.Role.DISABLED) return a == null || a.role == Channel.Role.DISABLED
        val x = a?.settings ?: return false
        val y = b.settings ?: return false
        return a.role == b.role && x.name == y.name && x.psk == y.psk && (x.module_settings?.position_precision ?: 0) == (y.module_settings?.position_precision ?: 0) &&
            !x.uplink_enabled && !x.downlink_enabled
    }

    /** True when the radio carries the server's channel as the app wants it. */
    fun hasChannel(state: RadioState, channel: MeshChannel, readOnly: Boolean = true): Boolean =
        channels(channel, readOnly).all { (i, c) -> same(state.channels[i], c) }

    /**
     * What the radio needs: the server's channel as its only channel, the server's preset and hop
     * limit on the public default slot of its region, its owner under the person's name, and no
     * PIN anybody could know. `region`: one the person chose in the app, when the radio has none.
     * `newPin` gives a PIN when one is needed.
     */
    fun plan(state: RadioState, channel: MeshChannel, name: String, region: Config.LoRaConfig.RegionCode? = null, readOnly: Boolean = true,
             newPin: () -> Int): Setup {
        val lora = state.lora ?: return Setup.NeedsRegion
        val where = region ?: lora.region
        if (where == Config.LoRaConfig.RegionCode.UNSET) return Setup.NeedsRegion
        val out = ArrayList<AdminMessage>()
        var pin: Int? = null

        val preset = preset(channel.preset)
        val wantLora = lora.copy(region = where, use_preset = true, modem_preset = preset, hop_limit = channel.hopLimit, tx_enabled = true,
            channel_num = slot(where, preset))
        if (wantLora != lora) out += AdminMessage(set_config = Config(lora = wantLora))

        val want = channels(channel, readOnly)
        for (index in 0..7) {
            val c = want[index] ?: Channel(index = index, role = Channel.Role.DISABLED)
            if (!same(state.channels[index], c) && (index in want || index in state.channels)) out += AdminMessage(set_channel = c)
        }

        // The radio answers anybody who asks who it is, also on the public channel: read only, it
        // has a name like a radio from the factory; otherwise the person's name.
        val owner = state.owner
        val (longName, short) = if (readOnly) "Meshtastic %04x".format(state.nodeNum and 0xffff) to "%04x".format(state.nodeNum and 0xffff)
            else name.trim().take(MAX_LONG_NAME).let { it to shortName(it) }
        if (longName.isNotEmpty() && owner != null && (owner.long_name != longName || owner.short_name != short)) {
            out += AdminMessage(set_owner = owner.copy(long_name = longName, short_name = short))
        }

        val bt = state.bluetooth
        if (bt != null && bt.enabled && (bt.mode == Config.BluetoothConfig.PairingMode.NO_PIN ||
                (bt.mode == Config.BluetoothConfig.PairingMode.FIXED_PIN && bt.fixed_pin == FACTORY_PIN))) {
            pin = newPin()
            out += AdminMessage(set_config = Config(bluetooth = bt.copy(mode = Config.BluetoothConfig.PairingMode.FIXED_PIN, fixed_pin = pin)))
        }
        return if (out.isEmpty()) Setup.Ready else Setup.Change(out, pin)
    }

    /** A new PIN: six digits, never the factory one. */
    fun randomPin(random: java.util.Random = java.security.SecureRandom()): Int {
        while (true) {
            val pin = 100000 + random.nextInt(900000)
            if (pin != FACTORY_PIN) return pin
        }
    }

    const val MAX_LONG_NAME = 24          // as the sharing name; Meshtastic takes up to 39 bytes

    /** The four letters a radio shows for its owner: the initials, or the start of the name. */
    fun shortName(name: String): String {
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = if (words.size > 1) words.joinToString("") { it.take(1) } else name
        return letters.filter { it.isLetterOrDigit() }.take(4).uppercase().ifEmpty { "FM" }
    }
}
