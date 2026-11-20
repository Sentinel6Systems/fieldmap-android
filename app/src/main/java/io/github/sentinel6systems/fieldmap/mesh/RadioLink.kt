package io.github.sentinel6systems.fieldmap.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import java.util.ArrayDeque
import java.util.UUID

/**
 * The Bluetooth link to one Meshtastic radio (Meshtastic's client API over BLE): pair, then
 * write ToRadio messages and read FromRadio messages until the radio has none left, again
 * each time it says it has more (FromNum). Android runs one Bluetooth operation at a time, so
 * they wait in a queue here; everything happens on the main thread.
 */
@SuppressLint("MissingPermission")             // MeshRadio connects only after the permission was given
class RadioLink(private val context: Context, val address: String, private val listener: Listener) {
    interface Listener {
        /** Pairing with the radio has started (Android shows its PIN dialog) or failed. */
        fun pairing(failed: Boolean)
        /** The link is up: messages may be sent. */
        fun linked()
        fun received(message: FromRadio)
        /** The link is gone (out of range, radio restarted, closed). */
        fun unlinked()
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<Op>()
    private var busy: Op? = null
    private var reading = false
    private var closed = false
    private var bondWatch: BroadcastReceiver? = null

    private sealed class Op {
        class Write(val bytes: ByteArray) : Op()
        data object Read : Op()
        class Notify(val characteristic: BluetoothGattCharacteristic) : Op()
    }

    private val device: BluetoothDevice? get() =
        try { context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null }

    /** Pairs first when the radio is not paired with this device yet, then connects. */
    fun open() {
        val d = device ?: return listener.unlinked()
        if (d.bondState == BluetoothDevice.BOND_BONDED) return connect(d)
        watchBond(d)
        listener.pairing(failed = false)
        if (d.bondState != BluetoothDevice.BOND_BONDING && !d.createBond()) { stopWatchingBond(); listener.pairing(failed = true) }
    }

    fun close() {
        closed = true
        stopWatchingBond()
        main.removeCallbacksAndMessages(null)
        queue.clear()
        busy = null
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }

    fun send(message: ToRadio) = enqueue(Op.Write(ToRadio.ADAPTER.encode(message)))

    private fun watchBond(d: BluetoothDevice) {
        val watch = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val which = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (which?.address != address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> { stopWatchingBond(); if (!closed) connect(d) }
                    BluetoothDevice.BOND_NONE -> { stopWatchingBond(); if (!closed) listener.pairing(failed = true) }
                }
            }
        }
        bondWatch = watch
        ContextCompat.registerReceiver(context, watch, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun stopWatchingBond() {
        bondWatch?.let { try { context.unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* not registered */ } }
        bondWatch = null
    }

    private fun connect(d: BluetoothDevice) {
        if (closed) return
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun enqueue(op: Op) = main.post {
        if (closed) return@post
        queue.add(op)
        next()
    }

    private fun next() {
        if (busy != null || closed) return
        val g = gatt ?: return
        val op = queue.poll() ?: return
        busy = op
        val started = when (op) {
            is Op.Write -> toRadio?.let { write(g, it, op.bytes) } ?: false
            Op.Read -> fromRadio?.let { g.readCharacteristic(it) } ?: false
            is Op.Notify -> {
                g.setCharacteristicNotification(op.characteristic, true)
                op.characteristic.getDescriptor(CCCD)?.let { write(g, it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) } ?: false
            }
        }
        if (!started) { busy = null; if (op == Op.Read) reading = false; main.post { next() } }
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = bytes; g.writeCharacteristic(c) }

    private fun write(g: BluetoothGatt, d: BluetoothGattDescriptor, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, bytes) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { d.value = bytes; g.writeDescriptor(d) }

    /** Reads FromRadio until it comes back empty: one read waiting at a time. */
    private fun drain() {
        if (reading) return
        reading = true
        enqueue(Op.Read)
    }

    private fun done() {
        busy = null
        next()
    }

    private fun lost() {
        val was = gatt != null
        queue.clear()
        busy = null
        reading = false
        gatt?.close()
        gatt = null
        if (was && !closed) listener.unlinked()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) = main.post {
            if (g != gatt) return@post
            if (state == BluetoothProfile.STATE_CONNECTED) {
                // Room for a whole message at once (they are up to 512 bytes).
                if (!g.requestMtu(MTU)) g.discoverServices()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) lost()
        }.let {}

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = main.post { if (g == gatt) g.discoverServices() }.let {}

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post {
            if (g != gatt) return@post
            val service = g.getService(SERVICE)
            toRadio = service?.getCharacteristic(TO_RADIO)
            fromRadio = service?.getCharacteristic(FROM_RADIO)
            val fromNum = service?.getCharacteristic(FROM_NUM)
            if (toRadio == null || fromRadio == null || fromNum == null) { g.disconnect(); return@post }
            queue.add(Op.Notify(fromNum))
            next()
            listener.linked()
        }.let {}

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val bytes = c.value ?: ByteArray(0)
            read(g, bytes, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = read(g, value, status)

        private fun read(g: BluetoothGatt, bytes: ByteArray, status: Int) = main.post {
            if (g != gatt) return@post
            reading = false
            done()
            if (status != BluetoothGatt.GATT_SUCCESS || bytes.isEmpty()) return@post
            val message = try { FromRadio.ADAPTER.decode(bytes) } catch (e: Exception) { null }
            if (message != null) listener.received(message)
            drain()
        }.let {}

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = main.post {
            if (g != gatt) return@post
            done()
            // An answer may be waiting now.
            drain()
        }.let {}

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = main.post { if (g == gatt) done() }.let {}

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = main.post { if (g == gatt) drain() }.let {}

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = main.post { if (g == gatt) drain() }.let {}
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")     // s6-allow: Meshtastic's published BLE service
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")    // s6-allow: Meshtastic's published BLE characteristic
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")  // s6-allow: Meshtastic's published BLE characteristic
        val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")    // s6-allow: Meshtastic's published BLE characteristic
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")  // s6-allow: Bluetooth standard descriptor
        private const val MTU = 512
    }
}
