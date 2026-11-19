package io.github.sentinel6systems.fieldmap.remote

import android.content.Context
import android.os.SystemClock
import io.github.sentinel6systems.fieldmap.BuildConfig
import io.github.sentinel6systems.fieldmap.nblink.Link
import io.github.sentinel6systems.fieldmap.nblink.Nblink
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The NetBird client inside the app (the Go library in nblink/). It is no system VPN: Android
 * shows no VPN question and no key icon, and only this app's requests to the box use it.
 *
 * While it runs, a port on this device (127.0.0.1) leads to the box's gateway on the NetBird
 * network. The app uses that port as one more address of the gateway, with the same
 * certificate check as for every other address.
 *
 * It runs only while something needs it: `use` and `release` say who (and for a minute after). One thread does every
 * start and stop, one after the other, so that nothing here makes the screen wait.
 */
object NetBirdLink {
    /** How long one start may take. */
    private const val START_SECONDS = 20L
    /** How long the client runs on after the last user let go of it. */
    private const val LINGER_SECONDS = 60L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "netbird").apply { isDaemon = true } }
    private val thread = worker.asCoroutineDispatcher()

    // Only the worker thread touches these two.
    private var link: Link? = null
    private var runs: NetBirdBlock? = null

    private val users = HashSet<String>()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "netbird-stop").apply { isDaemon = true } }
    private var leaving: ScheduledFuture<*>? = null

    /** The order of the ways, and when the client may start again after it did not. Shared by the screen and the sharing service. */
    val rules = WayRules()

    /** The way that answered last, for whoever looks next. */
    @Volatile
    var last: Way? = null

    /** The port on this device that leads to the box, while the client runs. */
    @Volatile
    var local: Endpoint? = null
        private set

    val on get() = local != null

    /** False on a device for which the app has no NetBird library (an unusual processor). */
    @Volatile
    var possible = true
        private set

    /** The name of this device on the NetBird network: the owner sees it on the NetBird dashboard. */
    fun memberName(pairedName: String) = "Fieldmap $pairedName".trim()

    /** `who` needs the connection from now on (the screen, the sharing service). */
    fun use(who: String) {
        synchronized(users) {
            users += who
            leaving?.cancel(false)
            leaving = null
        }
    }

    /**
     * `who` needs it no longer. When nobody is left, the client stops: at once when the app is
     * closed for good, and else after a minute, so that a short look at another app does not
     * cost a new start (some seconds with no map).
     */
    fun release(who: String, atOnce: Boolean = false) {
        synchronized(users) {
            users -= who
            if (users.isNotEmpty()) return
            leaving?.cancel(false)
            leaving = if (atOnce) null else timer.schedule({ if (!inUse) close() }, LINGER_SECONDS, TimeUnit.SECONDS)
        }
        if (atOnce) close()
    }

    val inUse get() = synchronized(users) { users.isNotEmpty() }

    /**
     * The port that leads to the box, with the client started if it does not run yet.
     * Null when it did not start (no internet, a setup key that is not valid any more, ...).
     */
    suspend fun open(context: Context, block: NetBirdBlock, name: String): Endpoint? = withContext(thread) {
        if (!possible || !inUse) return@withContext null
        val now = runs
        if (link != null && now != null && blockChange(now, block).let { it == BlockChange.SAME || it == BlockChange.STORE }) return@withContext local
        stop()
        try {
            val file = identity(context).apply { parentFile?.mkdirs() }
            val started = Nblink.start(block.management, block.setupKey, name, file.path, START_SECONDS, BuildConfig.DEBUG && VERBOSE)
            link = started
            runs = block
            local = Endpoint("127.0.0.1", started.forward(block.host, block.port.toLong()).toInt())
            rules.reset()
        } catch (e: LinkageError) {
            possible = false
        } catch (e: Exception) {
            // The reason is not written anywhere: it can name the management server.
            stop()
            rules.startFailed(SystemClock.elapsedRealtime())
        }
        local
    }

    /** Stops the client. Returns at once; the stop itself happens on the worker thread. */
    fun close() {
        local = null
        worker.execute { stop() }
    }

    /** Stops the client and forgets this device's membership of the NetBird network. */
    fun forget(context: Context) {
        val app = context.applicationContext
        close()
        worker.execute {
            identity(app).parentFile?.deleteRecursively()
            rules.reset()
        }
    }

    private fun stop() {
        local = null
        runs = null
        link?.let { try { it.stop() } catch (e: Exception) { /* it is gone either way */ } }
        link = null
    }

    /** Where the client keeps this device's own NetBird key, so that it is the same member after a restart. */
    private fun identity(context: Context) = File(context.filesDir, "netbird/config.json")

    /** For a developer: the client's own log in the system log of a debug build. */
    private const val VERBOSE = false
}
