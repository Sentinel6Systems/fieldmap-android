package io.github.sentinel6systems.fieldmap.share

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sentinel6systems.fieldmap.R
import io.github.sentinel6systems.fieldmap.data.Recording
import io.github.sentinel6systems.fieldmap.geo.TrackKeeper
import io.github.sentinel6systems.fieldmap.geo.TrackPoint
import io.github.sentinel6systems.fieldmap.mesh.MeshRadio
import io.github.sentinel6systems.fieldmap.mesh.MeshShare
import io.github.sentinel6systems.fieldmap.remote.NetBirdLink
import io.github.sentinel6systems.fieldmap.remote.RemoteAccess
import io.github.sentinel6systems.fieldmap.remote.Way
import io.github.sentinel6systems.fieldmap.server.Http
import io.github.sentinel6systems.fieldmap.server.Net
import io.github.sentinel6systems.fieldmap.server.Route
import io.github.sentinel6systems.fieldmap.ui.MainActivity
import io.github.sentinel6systems.fieldmap.ui.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/** A position of the phone as it is shared. */
fun Location.toFix(): Fix = Fix(
    latitude, longitude,
    if (hasAltitude()) altitude.takeIf { it.isFinite() } else null,
    if (hasAccuracy()) accuracy.toDouble().takeIf { it.isFinite() } else null,
    if (hasBearing()) bearing.toDouble().takeIf { it.isFinite() } else null,
    if (hasSpeed()) speed.toDouble().takeIf { it.isFinite() } else null,
    // By the phone's running clock, which no one can set wrong.
    System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000,
)

/**
 * Keeps this phone's position shared while the app is not on the screen: left for another
 * app, or with the screen off. It runs from the moment the person switches sharing on until
 * they switch it off or close the app (its Close button, or swiped away), and says so in a
 * notification the whole time.
 *
 * While the app is on the screen the app itself sends the position and this service only
 * waits. In the background it does as little as it can, because every position costs the
 * satellite receiver and the radio a wake-up:
 * - it asks for a position only every so often (Pace), which lets Android switch the
 *   receiver off in between: a few times a minute on the move, once a minute at rest;
 * - at rest the phone's own motion sensor, which costs next to nothing, says when it is
 *   carried off again;
 * - it only sends: nobody is looking at the map, so it does not ask who else is there;
 * - it holds the phone awake only for the seconds one message takes.
 */
class ShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var locations: LocationManager
    private var awake: PowerManager.WakeLock? = null
    private val mover = Mover()
    private var every = 0                       // seconds between the positions asked for; 0: not asking
    private var lastGps = 0L
    private var lastSent = 0L
    private var quietUntil = 0L
    private var sending = false
    private var leaving = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        awake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldmap:share").apply { setReferenceCounted(false) }
        Net.start(this)
        // While the position is shared, the service can need the box's NetBird network.
        NetBirdLink.use(SHARING)
        // ...and the mesh radio, which carries the position where there is no network.
        MeshRadio.init(this)
        MeshRadio.use()
        Messaging.init(this)
        // A track being recorded: positions come from here, on the screen or not.
        Recording.init(this)
        Recording.onState = {
            try { ServiceCompat.startForeground(this, NOTIFICATION, notification(), kinds()) } catch (e: Exception) { /* as it was */ }
            record()
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            // "Stop Sharing" on the notification: as the button in the app. The rest goes on.
            prefs.shareOn = false
            MeshShare.reset()
            leave()
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), kinds())
        } catch (e: Exception) {
            // Started from where Android does not allow it: the app does all of it while it is open.
            stopSelf()
            return START_NOT_STICKY
        }
        watch()
        record()
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
        // Not started again by Android after it had to end it: a position may only be taken
        // from the background by something the person started while the app was in view.
        return START_NOT_STICKY
    }

    /**
     * What the service does in the foreground, as Android wants it said: the location while
     * sharing is on, the mesh radio while one is paired, and messages (Android 14 and newer).
     */
    @SuppressLint("InlinedApi")                 // the kinds are plain numbers, and older Androids take no notice of them
    private fun kinds(): Int {
        var kinds = 0
        if ((prefs.shareOn || Recording.state != Recording.State.OFF) && mayLocate()) kinds = kinds or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (prefs.radioAddress != null && MeshRadio.allowed()) kinds = kinds or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 34) kinds = kinds or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        return kinds
    }

    private fun mayLocate() = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    /**
     * New messages are asked for every so often while the app is not on the screen (with each
     * position too, while sharing is on), kept, and a notification says one is waiting.
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!appShown) {
                // Only new messages are taken in (kept, and a notification): nothing is sent from here.
                ShareLine.base?.takeIf { ShareLine.info.messages }?.let { base ->
                    awake?.acquire(AWAKE_MS)
                    scope.launch {
                        try { Messaging.fetch(base) } finally { awake?.let { if (it.isHeld) it.release() } }
                    }
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Swiped away from the recent apps: Fieldmap goes on in the background (location sharing,
     * messages, the mesh radio). Only Exit in the app's Menu ends it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        if (instance === this) instance = null
        // Sharing ended. If the app is also off the screen, nothing needs the NetBird client.
        NetBirdLink.release(SHARING, atOnce = true)
        MeshRadio.release()
        listen(0)
        recordEvery = -1
        try { locations.removeUpdates(recorder) } catch (e: Exception) { /* was not listening */ }
        Recording.onState = {}
        scope.cancel()
        awake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Takes this phone off the others' maps, waits until that is said, and ends. */
    private fun leaveAndStop() {
        if (leaving) return
        leaving = true
        listen(0)
        main.removeCallbacks(poll)
        val to = ShareLine.base
        val sent = ShareLine.sentAt != 0L
        ShareLine.sentAt = 0
        if (to == null || !sent) return stopSelf()
        val body = ShareJson.leave(prefs.uid)
        thread { try { ShareClient.postNow(to + "leave", body) } finally { stopSelf() } }
    }

    /** Sharing switched off from the notification: this phone goes from the others' maps; the service goes on. */
    private fun leave() {
        listen(0)
        val to = ShareLine.base ?: return
        if (ShareLine.sentAt == 0L) return
        ShareLine.sentAt = 0
        val body = ShareJson.leave(prefs.uid)
        thread { ShareClient.postNow(to + "leave", body) }
    }

    /** The app came into view or left it: wait, or take over. */
    private fun watch() {
        // The position only while sharing is on: otherwise the satellite receiver stays off.
        if (appShown || !prefs.shareOn) { listen(0); mover.nudge() } else pace()
    }

    private fun pace() = listen(if (mover.still) Pace.still(ShareLine.info) else Pace.moving(ShareLine.info))

    /* ---------- positions ---------- */

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = taken(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    /**
     * Asks for a position every `seconds`; 0 stops asking. Straight from the satellite receiver,
     * as on the map (geo/GpsEngine.kt); asked for seldom, Android switches it off in between.
     */
    @SuppressLint("MissingPermission")          // the service only starts once the permission is there
    private fun listen(seconds: Int) {
        if (seconds == every) return
        every = seconds
        try { locations.removeUpdates(listener) } catch (e: Exception) { /* was not listening */ }
        if (seconds == 0) return disarm()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locations.allProviders.contains(provider)) locations.requestLocationUpdates(provider, seconds * 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: Exception) { /* no permission any more, or no such source: the other one may do */ }
        }
        if (mover.still) arm() else disarm()
    }

    private fun taken(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (every == 0 || appShown || !prefs.shareOn) return
        // The network's rough position only while the receiver has nothing (indoors).
        if (location.provider == LocationManager.GPS_PROVIDER) lastGps = now
        else if (lastGps != 0L && now - lastGps < 2500L * every) return
        // Android may hand out positions faster than asked for: the pace is kept here.
        if (lastSent != 0L && now - lastSent < 600L * every) return
        val fix = location.toFix()
        MeshShare.offer(fix)
        if (mover.take(fix.lat, fix.lon, fix.acc, fix.speed)) pace()
        // At rest the position says so: the others then know not to expect the next one soon.
        send(if (mover.still) fix.copy(course = null, speed = 0.0) else fix)
    }

    /* ---------- recording a track ---------- */

    private var recordEvery = 0                 // seconds between positions for the recording; 0: not asking

    /** While a track is recorded (not paused), positions straight from the satellite receiver, apart from sharing's. */
    @SuppressLint("MissingPermission")          // started only once the permission is there
    private fun record() {
        val seconds = if (Recording.state == Recording.State.ON && mayLocate()) TrackKeeper.sample(Recording.interval) else 0
        if (seconds == recordEvery) return
        recordEvery = seconds
        try { locations.removeUpdates(recorder) } catch (e: Exception) { /* was not listening */ }
        if (seconds == 0) return
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, seconds * 1000L, 0f, recorder, Looper.getMainLooper())
        } catch (e: Exception) { /* no receiver or no permission: nothing is recorded */ }
    }

    private val recorder = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = location.toFix()
            Recording.take(TrackPoint(fix.lat, fix.lon, fix.alt, fix.time, fix.acc), fix.speed)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    /* ---------- the motion sensor ---------- */

    private val sensors by lazy { getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    private val motion: Sensor? by lazy { sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private var armed = false
    private val moved = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // It fires once: the phone is being carried off. Back to the pace of the move.
            armed = false
            if (mover.nudge() && every != 0) pace()
        }
    }

    private fun arm() {
        val sensor = motion ?: return
        if (!armed) armed = try { sensors?.requestTriggerSensor(moved, sensor) == true } catch (e: Exception) { false }
    }

    private fun disarm() {
        val sensor = motion ?: return
        if (armed) try { sensors?.cancelTriggerSensor(moved, sensor) } catch (e: Exception) { /* gone already */ }
        armed = false
    }

    /* ---------- sending ---------- */

    private fun send(fix: Fix) {
        val now = SystemClock.elapsedRealtime()
        if (sending || now < quietUntil) return
        sending = true
        lastSent = now
        // The phone may go back to sleep the moment the position has been handed over: it is
        // kept awake for the message, and no longer.
        awake?.acquire(AWAKE_MS)
        scope.launch {
            try { post(fix) } finally {
                sending = false
                awake?.let { if (it.isHeld) it.release() }
            }
        }
    }

    private suspend fun post(fix: Fix) {
        val home = prefs.server ?: return
        val places = ShareClient.bases(home)
        if (places.isEmpty()) return
        val body = ShareJson.position(prefs.uid, prefs.shareName, fix)
        val known = ShareLine.base
        var r = if (known != null) ShareClient.post(known + "position", body).takeIf { it.code != null } else null
        // Nothing answered the way that worked last: the phone may have come home, or left.
        if (r == null && (known == null || Http.route == Route.REMOTE)) r = atHome(places, body)
        if (r == null) r = outside(places.first(), body)
        val wait = when (r?.code) {
            null -> { Messaging.serverBase = null; 0L }     // out of reach: the next position tries again
            200, 204 -> {
                ShareLine.sentAt = SystemClock.elapsedRealtime()
                // In the background, new messages are asked for with each position: a notification says when one came.
                ShareLine.base?.takeIf { ShareLine.info.messages }?.let { base -> Messaging.fetch(base) }
                0L
            }
            503 -> OFF_MS                                   // sharing switched off on the box: no faster than once a minute
            401, 403 -> REFUSED_MS                          // the gateway does not let this phone in: the app says why when it is opened
            404 -> { ShareLine.base = null; OFF_MS }
            else -> OFF_MS / 2
        }
        if (wait > 0) quietUntil = SystemClock.elapsedRealtime() + wait
    }

    /** On the box's own network, at each place it may answer about sharing. */
    private suspend fun atHome(places: List<String>, body: String): Answer? {
        for (place in places) {
            val r = Net.reach { ShareClient.post(place + "position", body, Route.LAN).takeIf { it.code != null && it.code != 404 } } ?: continue
            if (r.code == 200 || r.code == 204) {
                Http.route = Route.LAN
                ShareLine.base = place
                // At home: the NetBird client stops.
                NetBirdLink.last = Way.HOME
                Http.remote?.through(null)
                NetBirdLink.close()
            }
            return r
        }
        return null
    }

    /**
     * Through the box's gateway: at each direct address it gave, the one that answered last
     * first, and then through the box's NetBird network, when its owner set one up.
     */
    private suspend fun outside(place: String, body: String): Answer? {
        val end = Http.remote ?: return null
        val block = prefs.enrolment?.takeIf { it.home == prefs.server }?.netbird?.takeIf { NetBirdLink.possible }
        Net.untie()
        val now = SystemClock.elapsedRealtime()
        val plan = NetBirdLink.rules.plan(now, NetBirdLink.last, end.endpoints.isNotEmpty(), block != null, Net.internet(), NetBirdLink.on)
        if (Way.DIRECT in plan) {
            NetBirdLink.rules.directTried(now)
            for (i in end.order) {
                end.direct(i)
                val r = ShareClient.post(place + "position", body, Route.REMOTE)
                if (r.code == null) continue
                if (r.code == 200 || r.code == 204) { Http.route = Route.REMOTE; ShareLine.base = place; NetBirdLink.last = Way.DIRECT }
                // A direct address answers: the NetBird client is not needed.
                NetBirdLink.close()
                return r
            }
        }
        if (Way.NETBIRD in plan && block != null) {
            val local = NetBirdLink.open(this, block, NetBirdLink.memberName(RemoteAccess.deviceName))
            if (local != null && Http.remote === end) {
                end.through(local)
                val r = ShareClient.post(place + "position", body, Route.REMOTE)
                if (r.code != null) {
                    NetBirdLink.rules.answered()
                    if (r.code == 200 || r.code == 204) { Http.route = Route.REMOTE; ShareLine.base = place; NetBirdLink.last = Way.NETBIRD }
                    return r
                }
                if (NetBirdLink.rules.noAnswer(now)) { end.through(null); NetBirdLink.close() }
            }
        }
        // Without a connection to the internet the client only uses the battery.
        if (!Net.internet()) { end.through(null); NetBirdLink.close() }
        return null
    }

    /* ---------- the notification ---------- */

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Fieldmap Running", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Fieldmap runs in the background: location sharing, track recording, messages, the mesh radio."
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ShareService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val sharing = prefs.shareOn
        val recording = Recording.state != Recording.State.OFF
        val radio = prefs.radioAddress != null
        val what = listOfNotNull("location sharing".takeIf { sharing }, "track recording".takeIf { recording }, "new messages").joinToString(", ")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (sharing || recording) R.drawable.ic_locate else R.drawable.ic_message)
            .setContentTitle(when {
                recording && Recording.state == Recording.State.PAUSED -> "Track Recording Paused"
                recording -> "Recording Track"
                sharing -> "Location Sharing Active"
                else -> "Fieldmap Running"
            })
            .setContentText("Keeps $what in the background. Exit Fieldmap in its Menu to stop.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("When you leave Fieldmap or swipe it away, it keeps $what" +
                (if (radio) " (also over the mesh radio)" else "") + ". To stop it, open Fieldmap and press Exit in the Menu."))
            .setContentIntent(open)
            .apply { if (sharing) addAction(0, "Stop Sharing", stop) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "sharing"
        private const val NOTIFICATION = 1
        private const val STOP = "io.github.sentinel6systems.fieldmap.STOP_SHARING"
        /** How long the phone is kept awake for one message at most. */
        private const val AWAKE_MS = 20_000L
        private const val OFF_MS = 60_000L
        private const val REFUSED_MS = 300_000L
        /** How often messages are asked for in the background. */
        private const val POLL_MS = 60_000L
        /** This service, as a user of the NetBird client. */
        private const val SHARING = "sharing"

        @Volatile
        private var instance: ShareService? = null

        /** The app is on the screen: it sends the position itself, and the service waits. */
        @Volatile
        var appShown = false
            private set

        val running get() = instance != null

        /** Call while the app is in view: only then may Android let a position be taken later, in the background. */
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java)) } catch (e: Exception) { /* not allowed now: sharing goes on while the app is open */ }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }

        /**
         * The app is being closed for good. The service says so to the box and only then ends:
         * until it has, Android keeps the app alive for it. False when it was not running.
         */
        fun close(): Boolean = instance?.also { it.leaveAndStop() } != null

        fun shown(shown: Boolean) {
            appShown = shown
            instance?.watch()
        }
    }
}
