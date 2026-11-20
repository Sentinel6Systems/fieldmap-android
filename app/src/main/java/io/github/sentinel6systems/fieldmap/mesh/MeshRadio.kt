package io.github.sentinel6systems.fieldmap.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import io.github.sentinel6systems.fieldmap.share.Messaging
import io.github.sentinel6systems.fieldmap.ui.Prefs
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import kotlin.random.Random

/**
 * The paired Meshtastic radio: found and paired from Menu > Mesh Radio, then connected while
 * the app is in view, and set up for the server's mesh as soon as it has said what it has
 * (RadioSetup.plan). A radio restarts after new settings; it is then set up when it is back.
 */
@SuppressLint("MissingPermission")             // every way in checks allowed() first
object MeshRadio {
    enum class Status { NONE, OFFLINE, PAIRING, CONNECTING, SETTING_UP, NEEDS_REGION, NO_CHANNEL, LOCKED, READY, FAILED }

    /** A radio found by the search. */
    data class Found(val address: String, val name: String, val rssi: Int)

    var status = Status.NONE
        private set
    var state = RadioState()
        private set
    /** Why the last pairing or setup failed, in a sentence; empty otherwise. */
    var problem = ""
        private set
    /** Radios the search found so far. */
    val found = LinkedHashMap<String, Found>()
    var searching = false
        private set
    /** Called on the main thread whenever anything above changed. */
    var onChange: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private var link: RadioLink? = null
    private var users = 0
    private var configId = 0
    private var reading = RadioState()
    private var tries = 0                     // setups in a row that the radio did not keep

    fun init(c: Context) {
        if (::context.isInitialized) return
        context = c.applicationContext
        prefs = Prefs(context)
        MeshShare.init(context)
        status = if (prefs.radioAddress == null) Status.NONE else Status.OFFLINE
    }

    /** The Bluetooth permissions: Android 12 and newer ask for them apart from the location. */
    val permissions: Array<String> get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(): Boolean = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    val bluetoothOn: Boolean get() = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** The app came into view (or a service needs the radio): connect, if a radio is paired. */
    fun use() {
        users++
        if (users == 1) connect()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) disconnect()
    }

    /* ---------- finding and pairing ---------- */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Meshtastic radio"
            found[result.device.address] = Found(result.device.address, name, result.rssi)
            onChange()
        }

        override fun onScanFailed(errorCode: Int) {
            searching = false
            problem = "Search failed. Turn Bluetooth off and on, then try again."
            onChange()
        }
    }

    /** Looks for Meshtastic radios nearby for 20 seconds. */
    fun search() {
        if (!allowed() || searching) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        found.clear()
        problem = ""
        searching = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(RadioLink.SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        main.postDelayed({ stopSearch() }, SEARCH_MS)
        onChange()
    }

    fun stopSearch() {
        if (!searching) return
        searching = false
        try { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: Exception) { /* Bluetooth went off */ }
        onChange()
    }

    /** Takes this radio as the app's radio, and pairs and connects. */
    fun pair(radio: Found) {
        stopSearch()
        disconnect()
        prefs.radioAddress = radio.address
        prefs.radioName = radio.name
        prefs.radioPin = 0
        tries = 0
        state = RadioState()
        if (users > 0) connect()
        onChange()
    }

    /** Lets go of the radio. Android keeps it paired (Settings > Bluetooth) until removed there. */
    fun forget() {
        disconnect()
        prefs.radioAddress = null
        prefs.radioName = null
        prefs.radioPin = 0
        state = RadioState()
        status = Status.NONE
        problem = ""
        onChange()
    }

    /** The region the person chose for a radio that had none. */
    fun setRegion(region: Config.LoRaConfig.RegionCode) {
        prefs.radioRegion = region.name
        tries = 0
        setUp()
    }

    /** A trace route asked for (by node number): Waiting, or what came back; and when it was asked. */
    sealed class Tracing(val at: Long) {
        class Waiting(at: Long) : Tracing(at)
        class Done(at: Long, val trace: Trace) : Tracing(at)
        class NoAnswer(at: Long) : Tracing(at)
    }

    val traces = HashMap<Int, Tracing>()
    private val tracePackets = HashMap<Int, Int>()          // packet id -> node traced

    /** Seconds until another trace route may be asked for (Meshtastic's 30 s); 0 when it may. */
    fun traceWait(now: Long = System.currentTimeMillis()): Int =
        traces.values.maxOfOrNull { it.at }?.let { ((it + TRACE_GAP_MS - now) / 1000).toInt().coerceAtLeast(0) } ?: 0

    /** Asks for the way to radio `node` and back (one packet each way per hop). */
    fun traceRoute(node: Int): Boolean {
        val link = link ?: return false
        if (status != Status.READY || traceWait() > 0) return false
        val target = state.nodes[node] ?: return false
        val id = Random.nextInt(1, Int.MAX_VALUE)
        tracePackets[id] = node
        val at = System.currentTimeMillis()
        traces[node] = Tracing.Waiting(at)
        link.send(ToRadio(packet = MeshPacket(
            to = node, channel = target.channel, id = id, want_ack = true,
            decoded = Data(portnum = PortNum.TRACEROUTE_APP, payload = RouteDiscovery().encodeByteString(), want_response = true),
        )))
        main.postDelayed({
            if (traces[node] is Tracing.Waiting && traces[node]?.at == at) { traces[node] = Tracing.NoAnswer(at); onChange() }
        }, TRACE_TIMEOUT_MS)
        onChange()
        return true
    }

    /** The public channel: listen only, or let the other radios see this one. */
    fun setReadOnly(readOnly: Boolean) {
        prefs.meshReadOnly = readOnly
        tries = 0
        setUp()
    }

    /** The radios heard in the last two hours, the most recent first. */
    fun nearby(now: Long = System.currentTimeMillis() / 1000): List<MeshNode> =
        state.nodes.values.filter { now - it.heard < NEARBY_S }.sortedByDescending { it.heard }

    /** The server's channel changed (or came for the first time): set the radio up again. */
    fun channelChanged() {
        tries = 0
        if (status == Status.READY || status == Status.NO_CHANNEL) setUp()
    }

    /* ---------- the link ---------- */

    private fun connect() {
        val address = prefs.radioAddress ?: return
        if (link != null || !allowed() || !bluetoothOn) { if (link == null) status = Status.OFFLINE; onChange(); return }
        status = Status.CONNECTING
        problem = ""
        link = RadioLink(context, address, listener).also { it.open() }
        onChange()
    }

    private fun disconnect() {
        main.removeCallbacks(retry)
        link?.close()
        link = null
        if (prefs.radioAddress != null) status = Status.OFFLINE
    }

    private val retry = Runnable { if (users > 0 && link == null) connect() }

    private val listener = object : RadioLink.Listener {
        override fun pairing(failed: Boolean) {
            if (failed) {
                link?.close()
                link = null
                status = Status.FAILED
                problem = "Pairing failed. Check the PIN: it is shown on the radio's screen, or set in the radio. Then press Connect."
            } else status = Status.PAIRING
            onChange()
        }

        override fun linked() {
            status = Status.CONNECTING
            reading = RadioState()
            configId = Random.nextInt(1, Int.MAX_VALUE)
            link?.send(ToRadio(want_config_id = configId))
            onChange()
        }

        override fun received(message: FromRadio) {
            reading = reading.take(message)
            if (message.config_complete_id == configId) {
                state = reading
                // The radio's clock, for the times it notes when it hears other radios.
                link?.let { admin(it, AdminMessage(set_time_only = (System.currentTimeMillis() / 1000).toInt())) }
                setUp()
            } else if (state.nodeNum != 0) {
                // After the settings: what changes later (battery, its own node, the radios it hears) is kept up to date.
                state = state.take(message)
                message.packet?.let { p ->
                    state = state.heard(p, System.currentTimeMillis() / 1000)
                    // Fieldmap's own messages: only on the server's channel, which the radio could decrypt.
                    val d = p.decoded
                    if (status == Status.READY && d != null && d.portnum == PortNum.PRIVATE_APP && p.channel == RadioSetup.ourIndex(prefs.meshReadOnly)) {
                        MeshShare.received(p.from, d.payload.toByteArray())
                    }
                    // A text on the public channel, to everybody: Group (Public).
                    if (status == Status.READY && d != null && d.portnum == PortNum.TEXT_MESSAGE_APP && p.to == BROADCAST &&
                        p.channel == RadioSetup.publicIndex(prefs.meshReadOnly)) {
                        Messaging.publicHeard(p.from, state.nodes[p.from]?.name ?: RadioState.nodeName(p.from), p.id, d.payload.utf8(), p.rx_time ?: 0)
                    }
                    // The answer to a trace route.
                    if (d != null && d.portnum == PortNum.TRACEROUTE_APP && d.request_id != 0) {
                        tracePackets.remove(d.request_id)?.let { node ->
                            val route = try { RouteDiscovery.ADAPTER.decode(d.payload) } catch (e: Exception) { null }
                            val at = traces[node]?.at ?: System.currentTimeMillis()
                            if (route != null) traces[node] = Tracing.Done(at, Trace.of(route, node, state.nodeNum))
                        }
                    }
                    // Meshtastic's answer to a packet that wanted one: it arrived, or it could not.
                    if (d != null && d.portnum == PortNum.ROUTING_APP && d.request_id != 0) {
                        val routing = try { Routing.ADAPTER.decode(d.payload) } catch (e: Exception) { null }
                        MeshShare.acked(d.request_id, p.from, routing?.error_reason == null || routing.error_reason == Routing.Error.NONE)
                    }
                }
                onChange()
            }
        }

        override fun unlinked() {
            link?.close()
            link = null
            MeshShare.reset()
            if (status != Status.FAILED) status = Status.OFFLINE
            onChange()
            // Out of range or restarting after new settings: try again in a while.
            if (users > 0) main.postDelayed(retry, RETRY_MS)
        }
    }

    /** Sets the radio up for the server's mesh when it needs it; otherwise it is ready. */
    private fun setUp() {
        val link = link ?: return
        if (state.nodeNum == 0) return
        if (state.locked) { status = Status.LOCKED; onChange(); return }
        val channel = prefs.meshChannel ?: run { status = Status.NO_CHANNEL; onChange(); return }
        val region = prefs.radioRegion?.let { r -> Config.LoRaConfig.RegionCode.entries.firstOrNull { it.name == r } }
        when (val plan = RadioSetup.plan(state, channel, prefs.shareName, region, prefs.meshReadOnly) { RadioSetup.randomPin() }) {
            Setup.Ready -> {
                val was = status
                status = Status.READY
                tries = 0
                if (was != Status.READY) MeshShare.ready()
            }
            Setup.NeedsRegion -> status = Status.NEEDS_REGION
            is Setup.Change -> {
                if (++tries > MAX_TRIES) {
                    status = Status.FAILED
                    problem = "The radio did not keep the settings. Update its firmware, or reset it to factory settings, then press Connect."
                } else {
                    status = Status.SETTING_UP
                    plan.pin?.let { prefs.radioPin = it }
                    admin(link, AdminMessage(begin_edit_settings = true))
                    plan.messages.forEach { admin(link, it) }
                    // The radio restarts with the new settings, and the link goes; it is set up again when it is back.
                    admin(link, AdminMessage(commit_edit_settings = true))
                }
            }
        }
        onChange()
    }

    private fun admin(link: RadioLink, message: AdminMessage) = link.send(ToRadio(packet = MeshPacket(
        to = state.nodeNum, id = Random.nextInt(1, Int.MAX_VALUE), want_ack = true,
        decoded = Data(portnum = PortNum.ADMIN_APP, payload = AdminMessage.ADAPTER.encode(message).toByteString(), want_response = true),
    )))

    /**
     * Sends one of Fieldmap's own messages to everybody on the server's channel. False when the
     * radio is not ready for it (not connected, or not set up for the server's mesh).
     */
    fun sendApp(payload: ByteArray): Boolean = sendApp(payload, BROADCAST, wantAck = false) != null

    /**
     * The same to one radio (`to`, its node number) or to everybody (BROADCAST). With `wantAck`,
     * Meshtastic confirms that it arrived (MeshShare.acked). Returns the packet's id; null when
     * the radio is not ready.
     */
    fun sendApp(payload: ByteArray, to: Int, wantAck: Boolean): Int? {
        val link = link ?: return null
        if (status != Status.READY) return null
        val id = Random.nextInt(1, Int.MAX_VALUE)
        link.send(ToRadio(packet = MeshPacket(
            to = to, channel = RadioSetup.ourIndex(prefs.meshReadOnly), id = id, want_ack = wantAck,
            decoded = Data(portnum = PortNum.PRIVATE_APP, payload = payload.toByteString()),
        )))
        return id
    }

    /**
     * A text to everybody on the public channel (Meshtastic's own text messages). Only when the
     * public channel is not read only: a text there shows this radio to everybody. Returns false
     * when it cannot go now.
     */
    fun sendPublic(text: String): Boolean {
        val link = link ?: return false
        if (status != Status.READY || prefs.meshReadOnly) return false
        link.send(ToRadio(packet = MeshPacket(
            to = BROADCAST, channel = RadioSetup.publicIndex(false), id = Random.nextInt(1, Int.MAX_VALUE),
            decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = text.toByteArray().toByteString()),
        )))
        return true
    }

    /** Try connecting now (after a failure, or when Bluetooth came back on). */
    fun retryNow() {
        tries = 0
        disconnect()
        if (users > 0) connect()
    }

    private const val SEARCH_MS = 20_000L
    private const val RETRY_MS = 10_000L
    private const val MAX_TRIES = 2
    private const val NEARBY_S = 2 * 3600L
    const val BROADCAST = -1                     // 0xffffffff: every radio
    private const val TRACE_GAP_MS = 30_000L
    private const val TRACE_TIMEOUT_MS = 60_000L
}
