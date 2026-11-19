package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.remote.BlockAnswer
import io.github.sentinel6systems.fieldmap.remote.BlockChange
import io.github.sentinel6systems.fieldmap.remote.Endpoint
import io.github.sentinel6systems.fieldmap.remote.NetBirdBlock
import io.github.sentinel6systems.fieldmap.remote.Way
import io.github.sentinel6systems.fieldmap.remote.WayRules
import io.github.sentinel6systems.fieldmap.remote.blockAnswer
import io.github.sentinel6systems.fieldmap.remote.blockChange
import io.github.sentinel6systems.fieldmap.remote.identityFits
import io.github.sentinel6systems.fieldmap.remote.netbirdFrom
import io.github.sentinel6systems.fieldmap.server.RemoteEnd
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBirdTest {
    // As the box writes it (the contract of 2026-11-19); the key is a made-up one.
    private val key = "00000000-0000-0000-0000-000000000000"
    private val text = """{"management":"https://api.netbird.io:443","setupKey":"$key","host":"192.0.2.7","port":41234,"rev":"5f0c9a2b71d3"}"""
    private val block = NetBirdBlock("https://api.netbird.io:443", key, "192.0.2.7", 41234, "5f0c9a2b71d3")

    /* ---------- what the box says ---------- */

    @Test
    fun `the block of the box is read`() {
        assertEquals(block, netbirdFrom(JSONObject(text)))
        // An own server on a home network can be plain http; a draft box sent no "rev".
        assertEquals(NetBirdBlock("http://192.0.2.5:8081", key, "192.0.2.9", 8443),
            netbirdFrom(JSONObject("""{"management":"http://192.0.2.5:8081","setupKey":"$key","host":"192.0.2.9","port":8443}""")))
    }

    @Test
    fun `a block that is not complete is no block`() {
        assertNull(netbirdFrom(null))
        assertNull(netbirdFrom(JSONObject("{}")))
        for (missing in listOf("management", "setupKey", "host", "port")) {
            assertNull(missing, netbirdFrom(JSONObject(text).apply { remove(missing) }))
        }
        assertNull(netbirdFrom(JSONObject(text).put("port", 0)))
        assertNull(netbirdFrom(JSONObject(text).put("port", 70000)))
        assertNull(netbirdFrom(JSONObject(text).put("management", "api.netbird.io")))
        assertNull(netbirdFrom(JSONObject(text).put("host", "192.0.2.7/24")))
        assertNull(netbirdFrom(JSONObject(text).put("host", "a b")))
    }

    @Test
    fun `the setup key is in no text made from a block`() {
        assertFalse(block.toString().contains(key))
        assertFalse("$block".contains("0000"))
    }

    @Test
    fun `the answers of the gateway`() {
        assertEquals(BlockAnswer.SET to block, blockAnswer(200, """{"netbird":$text}"""))
        // NetBird is not set up, or is switched off on the box.
        assertEquals(BlockAnswer.NONE to null, blockAnswer(200, """{"netbird":null}"""))
        // A box with older software.
        assertEquals(BlockAnswer.NONE to null, blockAnswer(404, "not found"))
        // The device was removed on the box.
        assertEquals(BlockAnswer.REMOVED to null, blockAnswer(403, ""))
        // Remote access is off on the box, the box had an error, nothing answered, or the
        // answer cannot be read: the app keeps what it has.
        for (code in listOf(401, 500, 502, 204, null)) assertEquals("$code", BlockAnswer.KEEP to null, blockAnswer(code, """{"netbird":null}""".takeIf { code != 200 }))
        assertEquals(BlockAnswer.KEEP to null, blockAnswer(200, "<html>"))
        assertEquals(BlockAnswer.KEEP to null, blockAnswer(200, "{}"))
        assertEquals(BlockAnswer.KEEP to null, blockAnswer(200, """{"netbird":{"management":"https://api.netbird.io:443"}}"""))
        assertEquals(BlockAnswer.KEEP to null, blockAnswer(200, null))
    }

    @Test
    fun `what a new block means`() {
        assertEquals(BlockChange.SAME, blockChange(block, block.copy()))
        // A first block, or another NetBird network: join (again).
        assertEquals(BlockChange.REJOIN, blockChange(null, block))
        assertEquals(BlockChange.REJOIN, blockChange(block, block.copy(management = "https://netbird.example.com:443", rev = "b")))
        // Only a new setup key: a device that is a member already stays one.
        assertEquals(BlockChange.STORE, blockChange(block, block.copy(setupKey = "a-new-setup-key", rev = "b")))
        assertEquals(BlockChange.STORE, blockChange(block, block.copy(rev = "b")))
        // The box has another address or port on the same network.
        assertEquals(BlockChange.RESTART, blockChange(block, block.copy(host = "192.0.2.8", rev = "b")))
        assertEquals(BlockChange.RESTART, blockChange(block, block.copy(port = 40000, rev = "b")))
    }

    @Test
    fun `the device keeps its NetBird identity only for the same network`() {
        // NetBird came back on the box, on the same network: the same member, no new join.
        assertTrue(identityFits("https://api.netbird.io:443", block.management))
        // Another network, or an identity whose network is not known: join with the key.
        assertFalse(identityFits("https://netbird.example.com:443", block.management))
        assertFalse(identityFits(null, block.management))
        // After a switch off and on, the block is new to the app, and the client starts again.
        assertEquals(BlockChange.REJOIN, blockChange(null, block))
    }

    /* ---------- the order of the ways ---------- */

    private val min = 60_000L

    @Test
    fun `home first, then the direct addresses, then NetBird`() {
        val rules = WayRules()
        assertEquals(listOf(Way.HOME, Way.DIRECT, Way.NETBIRD), rules.plan(0, null, hasDirect = true, hasNetBird = true, internet = true, clientRuns = false))
        // A box whose owner switched off the port on the router: no direct address.
        assertEquals(listOf(Way.HOME, Way.NETBIRD), rules.plan(0, null, hasDirect = false, hasNetBird = true, internet = true, clientRuns = false))
        // A box with no NetBird: as before.
        assertEquals(listOf(Way.HOME, Way.DIRECT), rules.plan(0, null, hasDirect = true, hasNetBird = false, internet = true, clientRuns = false))
        assertEquals(listOf(Way.HOME), rules.plan(0, null, hasDirect = false, hasNetBird = false, internet = true, clientRuns = false))
    }

    @Test
    fun `the client does not start with no connection to the internet`() {
        val rules = WayRules()
        assertEquals(listOf(Way.HOME, Way.DIRECT), rules.plan(0, null, hasDirect = true, hasNetBird = true, internet = false, clientRuns = false))
        // The connection is back: at once, with no wait.
        assertEquals(listOf(Way.HOME, Way.DIRECT, Way.NETBIRD), rules.plan(1000, null, hasDirect = true, hasNetBird = true, internet = true, clientRuns = false))
    }

    @Test
    fun `while NetBird is the way, the direct addresses are tried again only now and then`() {
        val rules = WayRules()
        rules.plan(0, null, hasDirect = true, hasNetBird = true, internet = true, clientRuns = false)
        rules.directTried(0)
        // Home is asked every time: there the client must stop.
        assertEquals(listOf(Way.HOME, Way.NETBIRD), rules.plan(15_000, Way.NETBIRD, hasDirect = true, hasNetBird = true, internet = true, clientRuns = true))
        assertEquals(listOf(Way.HOME, Way.NETBIRD), rules.plan(4 * min, Way.NETBIRD, hasDirect = true, hasNetBird = true, internet = true, clientRuns = true))
        assertEquals(listOf(Way.HOME, Way.DIRECT, Way.NETBIRD), rules.plan(5 * min, Way.NETBIRD, hasDirect = true, hasNetBird = true, internet = true, clientRuns = true))
        // When a direct address is the way, or nothing answers, the direct addresses come every time.
        assertEquals(listOf(Way.HOME, Way.DIRECT, Way.NETBIRD), rules.plan(15_000, Way.DIRECT, hasDirect = true, hasNetBird = true, internet = true, clientRuns = false))
        assertEquals(listOf(Way.HOME, Way.DIRECT, Way.NETBIRD), rules.plan(15_000, null, hasDirect = true, hasNetBird = true, internet = true, clientRuns = false))
    }

    @Test
    fun `a start that failed waits longer each time, up to five minutes`() {
        val rules = WayRules()
        fun mayStart(at: Long) = Way.NETBIRD in rules.plan(at, null, hasDirect = false, hasNetBird = true, internet = true, clientRuns = false)
        var now = 0L
        for (wait in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            assertTrue(mayStart(now))
            rules.startFailed(now)
            assertFalse("$wait", mayStart(now + wait - 1))
            assertTrue("$wait", mayStart(now + wait))
            now += wait
        }
        // It started: the next failure waits the short time again.
        rules.reset()
        rules.startFailed(now)
        assertTrue(mayStart(now + 30_000))
    }

    @Test
    fun `a client that runs is used, also inside the wait`() {
        val rules = WayRules()
        rules.startFailed(0)
        assertEquals(listOf(Way.HOME, Way.NETBIRD), rules.plan(1000, null, hasDirect = false, hasNetBird = true, internet = true, clientRuns = true))
    }

    @Test
    fun `a client through which the box does not answer stops at the third time`() {
        val rules = WayRules()
        assertFalse(rules.noAnswer(0))
        assertFalse(rules.noAnswer(15_000))
        // An answer in between starts the count again.
        rules.answered()
        assertFalse(rules.noAnswer(30_000))
        assertFalse(rules.noAnswer(45_000))
        assertTrue(rules.noAnswer(60_000))
        // And the next start waits, as after a start that failed.
        assertFalse(Way.NETBIRD in rules.plan(60_000 + 29_999, null, hasDirect = false, hasNetBird = true, internet = true, clientRuns = false))
        assertTrue(Way.NETBIRD in rules.plan(60_000 + 30_000, null, hasDirect = false, hasNetBird = true, internet = true, clientRuns = false))
    }

    /* ---------- when the client must not run ---------- */

    @Test
    fun `the client stops at home, at a direct address, with no internet, and when nothing uses it`() {
        // NetBird is the way, or nothing answers yet: it runs on.
        assertFalse(WayRules.mustStop(Way.NETBIRD, hasNetBird = true, internet = true, inUse = true))
        assertFalse(WayRules.mustStop(null, hasNetBird = true, internet = true, inUse = true))
        assertTrue(WayRules.mustStop(Way.HOME, hasNetBird = true, internet = true, inUse = true))
        assertTrue(WayRules.mustStop(Way.DIRECT, hasNetBird = true, internet = true, inUse = true))
        assertTrue(WayRules.mustStop(null, hasNetBird = true, internet = false, inUse = true))
        // The app left the screen and the position is not shared.
        assertTrue(WayRules.mustStop(Way.NETBIRD, hasNetBird = true, internet = true, inUse = false))
        // The box switched NetBird off, or the device was unpaired.
        assertTrue(WayRules.mustStop(Way.NETBIRD, hasNetBird = false, internet = true, inUse = true))
    }

    /* ---------- where the requests for the gateway go ---------- */

    @Test
    fun `the gateway's address is a direct one, or the NetBird port on this device`() {
        val direct = listOf(Endpoint("203.0.113.30", 41000), Endpoint("[2001:db8:77::10]", 41000))
        val end = RemoteEnd(direct, OkHttpClient())
        assertEquals(direct[0], end.endpoint)
        end.direct(1)
        assertEquals(direct[1], end.endpoint)
        assertEquals(listOf(1, 0), end.order)
        // Through NetBird: the same gateway behind a port on this device.
        val local = Endpoint("127.0.0.1", 39999)
        end.through(local)
        assertEquals(local, end.endpoint)
        // And back to the direct address used before.
        end.through(null)
        assertEquals(direct[1], end.endpoint)
        end.through(local)
        end.direct(0)
        assertEquals(direct[0], end.endpoint)
    }

    @Test
    fun `a box with NetBird only has no direct address`() {
        val end = RemoteEnd(emptyList(), OkHttpClient())
        assertNull(end.endpoint)
        assertEquals(emptyList<Int>(), end.order)
        end.through(Endpoint("127.0.0.1", 39999))
        assertEquals(Endpoint("127.0.0.1", 39999), end.endpoint)
    }
}
