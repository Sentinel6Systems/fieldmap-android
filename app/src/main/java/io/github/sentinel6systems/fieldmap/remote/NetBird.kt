package io.github.sentinel6systems.fieldmap.remote

import org.json.JSONException
import org.json.JSONObject

/**
 * NetBird as a second way to the box from outside: for a box behind an internet connection
 * where no port can be opened. The box's owner sets it up on the box. A paired device gets
 * what it needs from the box, and the person with the device does nothing.
 *
 * NetBird is only the road. The app talks to the same gateway of the box, with the same
 * certificate check, as on the direct way.
 *
 * This file has no Android in it: what the box says, what a change means, and the rules for
 * the order of the ways and for the start and stop of the NetBird client.
 */

/** What the box tells a paired device about its NetBird network. The setup key is a secret. */
data class NetBirdBlock(
    /** The address of the NetBird management server, with scheme and port. */
    val management: String,
    /** Lets a new member into the NetBird network. Never shown, never written to a log. */
    val setupKey: String,
    /** The box's address on the NetBird network. */
    val host: String,
    /** The port of the box's gateway. */
    val port: Int,
    /** Changes when anything in the block changes. */
    val rev: String = "",
) {
    // The key stays out of every text made from a block.
    override fun toString() = "NetBirdBlock(management=$management, host=$host, port=$port, rev=$rev)"
}

/** The block in the box's JSON, or null when there is none or it is not complete. */
fun netbirdFrom(o: JSONObject?): NetBirdBlock? {
    if (o == null) return null
    val management = o.optString("management").trim()
    val key = o.optString("setupKey").trim()
    val host = o.optString("host").trim()
    val port = o.optInt("port")
    if (!management.startsWith("https://") && !management.startsWith("http://")) return null
    if (key.isEmpty() || port !in 1..65535) return null
    if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' || it == '@' }) return null
    return NetBirdBlock(management, key, host, port, o.optString("rev"))
}

/** What an answer to "which NetBird network?" tells the app to do. */
enum class BlockAnswer {
    /** The box has NetBird: use this block. */
    SET,
    /** The box has no NetBird (any more): stop the client, forget the key and the membership. */
    NONE,
    /** The box removed this device. */
    REMOVED,
    /** No clear answer: keep what the app has. */
    KEEP,
}

/** Reads the answer of the gateway's `GET /mapremote/netbird`. `code` is null when nothing answered. */
fun blockAnswer(code: Int?, body: String?): Pair<BlockAnswer, NetBirdBlock?> = when (code) {
    200 -> try {
        val o = JSONObject(body.orEmpty())
        when {
            !o.has("netbird") -> BlockAnswer.KEEP to null
            o.isNull("netbird") -> BlockAnswer.NONE to null
            else -> netbirdFrom(o.optJSONObject("netbird"))?.let { BlockAnswer.SET to it } ?: (BlockAnswer.KEEP to null)
        }
    } catch (e: JSONException) { BlockAnswer.KEEP to null }
    404 -> BlockAnswer.NONE to null                 // a box with older software
    403 -> BlockAnswer.REMOVED to null
    else -> BlockAnswer.KEEP to null                // 401: remote access is off on the box; or no answer
}

/** What a new block means for a client that runs, or ran, with the old one. */
enum class BlockChange {
    SAME,
    /** Only the setup key changed: a device that is a member already stays one. Remember the new key. */
    STORE,
    /** The box's address or port changed: the client must start again. The membership stays. */
    RESTART,
    /** Another NetBird network: stop the client, forget the membership, join the new network. */
    REJOIN,
}

fun blockChange(old: NetBirdBlock?, new: NetBirdBlock): BlockChange = when {
    old == null || old.management != new.management -> BlockChange.REJOIN
    old.host != new.host || old.port != new.port -> BlockChange.RESTART
    old.setupKey != new.setupKey || old.rev != new.rev -> BlockChange.STORE
    else -> BlockChange.SAME
}

/** A way to the box. */
enum class Way { HOME, DIRECT, NETBIRD }

/**
 * The order in which the app looks for the box, and when the NetBird client may start.
 *
 * Home first, then the direct addresses, then NetBird. The NetBird client costs battery and
 * data, so it starts only when the two other ways did not answer and the device has a
 * connection to the internet. While NetBird is the way that works, the app still asks at home
 * every time (it is quick, and at home the client must stop), but tries the direct addresses
 * again only now and then: each of them takes seconds to fail.
 *
 * Times are milliseconds on a clock that only goes forward.
 */
class WayRules(private val directRetryMs: Long = 300_000L, private val firstWaitMs: Long = 30_000L, private val longestWaitMs: Long = 300_000L) {
    private var lastDirectTry = Long.MIN_VALUE / 2
    private var failures = 0
    private var notBefore = Long.MIN_VALUE / 2
    private var misses = 0

    /** The ways to try now, in order. `last` is the way that answered at the last look, if any. */
    @Synchronized
    fun plan(now: Long, last: Way?, hasDirect: Boolean, hasNetBird: Boolean, internet: Boolean, clientRuns: Boolean): List<Way> {
        val ways = ArrayList<Way>()
        ways += Way.HOME
        if (hasDirect && (last != Way.NETBIRD || !hasNetBird || now - lastDirectTry >= directRetryMs)) ways += Way.DIRECT
        if (hasNetBird && (clientRuns || (internet && now >= notBefore))) ways += Way.NETBIRD
        return ways
    }

    /** Call when the direct addresses were tried. */
    @Synchronized
    fun directTried(now: Long) { lastDirectTry = now }

    /** Call when the NetBird client did not start. The next start waits: 30 s, 1 min, 2 min, then 5 min. */
    @Synchronized
    fun startFailed(now: Long) {
        val wait = (firstWaitMs shl failures.coerceAtMost(10)).coerceAtMost(longestWaitMs)
        failures++
        notBefore = now + wait
    }

    /**
     * Call when the client runs but the box did not answer through it. True at the third time
     * in a row: stop the client. The next start then waits as after a start that failed.
     */
    @Synchronized
    fun noAnswer(now: Long): Boolean {
        if (++misses < 3) return false
        misses = 0
        startFailed(now)
        return true
    }

    /** Call when the box answered through NetBird. */
    @Synchronized
    fun answered() { misses = 0 }

    /** Call when the NetBird client started, and when the block changed. */
    @Synchronized
    fun reset() {
        misses = 0
        failures = 0
        notBefore = Long.MIN_VALUE / 2
    }

    companion object {
        /**
         * True when the NetBird client must not run (any more): the box answered at home or at
         * a direct address, the box has no NetBird, the device has no connection to the
         * internet, or nothing uses the connection (the app is not in view and the position is
         * not shared in the background).
         */
        fun mustStop(answered: Way?, hasNetBird: Boolean, internet: Boolean, inUse: Boolean): Boolean =
            !hasNetBird || !internet || !inUse || answered == Way.HOME || answered == Way.DIRECT
    }
}
