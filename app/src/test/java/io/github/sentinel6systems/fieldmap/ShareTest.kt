package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.MarkerStore
import io.github.sentinel6systems.fieldmap.share.Ageing
import io.github.sentinel6systems.fieldmap.share.Fix
import io.github.sentinel6systems.fieldmap.share.Mover
import io.github.sentinel6systems.fieldmap.share.Pace
import io.github.sentinel6systems.fieldmap.share.Peer
import io.github.sentinel6systems.fieldmap.share.Pending
import io.github.sentinel6systems.fieldmap.share.ShareClient
import io.github.sentinel6systems.fieldmap.share.ShareInfo
import io.github.sentinel6systems.fieldmap.share.ShareJson
import io.github.sentinel6systems.fieldmap.share.ShareMerge
import io.github.sentinel6systems.fieldmap.share.ShareState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Live sharing as the box's contract (version 1) has it: what travels, and what the phone makes of it. */
class ShareTest {
    private val t1 = "2026-01-31T12:00:00Z"
    private val t2 = "2026-01-31T12:05:00Z"
    private val t3 = "2026-01-31T12:10:00Z"

    private fun own(id: String, label: String = id) = Marker(id, 45.0, -100.0, label, "#d7263d")
    private fun shared(id: String, label: String = id, updated: String = t1, by: String = "phone-aaaa") =
        Marker(id, 45.0, -100.0, label, "#1f5fa8", shared = true, updated = updated, by = by)

    private fun state(markers: List<Marker> = emptyList(), removed: List<String> = emptyList(), full: Boolean = false, rev: Long = 7) =
        ShareState(rev, full, emptyList(), markers, removed)

    /* ---------- what travels ---------- */

    @Test
    fun `the box's info is read, and its numbers kept within reason`() {
        assertEquals(ShareInfo(true, 5, 120), ShareJson.info("""{"version":1,"enabled":true,"interval":5,"stale":120}"""))
        assertEquals(ShareInfo(false, 5, 120), ShareJson.info("""{"enabled":false}"""))
        // A pace of zero would have the phone ask without pause; a position must outlive two posts.
        assertEquals(ShareInfo(true, 2, 4), ShareJson.info("""{"version":1,"enabled":true,"interval":0,"stale":1}"""))
        assertNull(ShareJson.info("<html>not found</html>"))
        assertNull(ShareJson.info("""{"service":"something else"}"""))
    }

    @Test
    fun `a position is sent with what the box needs, and without what the phone does not know`() {
        val whole = JSONObject(ShareJson.position("0a1b2c3d-uid", "Rover", Fix(45.123456789, -100.5, 312.44, 4.26, 271.04, 1.234, 1_769_860_800_999)))
        assertEquals("0a1b2c3d-uid", whole.getString("uid"))
        assertEquals("Rover", whole.getString("name"))
        assertEquals(45.1234568, whole.getDouble("lat"), 0.0)
        assertEquals(-100.5, whole.getDouble("lon"), 0.0)
        assertEquals(312.4, whole.getDouble("alt"), 0.0)
        assertEquals(4.3, whole.getDouble("acc"), 0.0)
        assertEquals(271.0, whole.getDouble("course"), 0.0)
        assertEquals(1.23, whole.getDouble("speed"), 0.0)
        assertEquals("2026-01-31T12:00:00Z", whole.getString("time"))

        val bare = JSONObject(ShareJson.position("0a1b2c3d-uid", "Rover", Fix(1.0, 2.0, null, null, null, null, 0)))
        assertEquals(setOf("uid", "name", "lat", "lon", "time"), bare.keySet())
        assertEquals("1970-01-01T00:00:00Z", bare.getString("time"))
        assertEquals("""{"uid":"0a1b2c3d-uid"}""", ShareJson.leave("0a1b2c3d-uid"))
    }

    @Test
    fun `times are read back, with or without parts of a second, and nonsense counts as oldest`() {
        assertEquals(1_769_860_800_000, ShareJson.millis(t1))
        assertEquals(1_769_860_800_250, ShareJson.millis("2026-01-31T12:00:00.250Z"))
        assertEquals(1_769_860_800_000, ShareJson.millis("2026-01-31T13:00:00+01:00"))
        assertEquals(0, ShareJson.millis("yesterday"))
        assertEquals(0, ShareJson.millis(""))
        assertEquals(t1, ShareJson.time(ShareJson.millis(t1)))
    }

    @Test
    fun `a typed name becomes one line of at most 24 characters`() {
        assertEquals("Rover", ShareJson.name("  Rover \n"))
        assertEquals("", ShareJson.name("   "))
        assertEquals(24, ShareJson.name("x".repeat(40)).length)
        assertTrue(ShareJson.ID.matches("0a1b2c3d-uid"))
        assertFalse(ShareJson.ID.matches("short"))
        assertFalse(ShareJson.ID.matches("with space 12345"))
    }

    @Test
    fun `a shared marker goes to the box as the app's own feature, with its id, who and when`() {
        val f = JSONObject(ShareJson.marker(shared("m-000001", "Ford", t2, "phone-bbbb")))
        assertEquals("Feature", f.getString("type"))
        assertEquals("m-000001", f.getString("id"))
        assertEquals("Point", f.getJSONObject("geometry").getString("type"))
        assertEquals(-100.0, f.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 0.0)
        val p = f.getJSONObject("properties")
        assertEquals("phone-bbbb", p.getString("by"))
        assertEquals(t2, p.getString("updated"))
        assertEquals("Ford", p.getJSONObject("fieldmap").getString("label"))
        assertEquals("#1f5fa8", p.getJSONObject("fieldmap").getString("color"))
        assertEquals("marker", p.getJSONObject("fieldmap").getString("kind"))
    }

    @Test
    fun `the box's state is read, and what cannot be a position or a marker is left out`() {
        val text = """{"rev":42,"full":false,
          "positions":[
            {"uid":"phone-aaaa","name":"Rover","lat":45.5,"lon":-100.25,"alt":300.5,"course":90,"speed":1.5,"time":"$t1","age":3},
            {"uid":"phone-bbbb","name":"${"y".repeat(40)}","lat":1,"lon":2},
            {"uid":"bad","name":"Too short an id","lat":1,"lon":2},
            {"uid":"phone-cccc","name":"Off the globe","lat":95,"lon":2},
            {"uid":"phone-dddd","name":"No place"}],
          "markers":[
            {"type":"Feature","id":"m-000001","geometry":{"type":"Point","coordinates":[10.5,60]},
             "properties":{"name":"Hut","marker-color":"#2a9d3f","by":"phone-aaaa","updated":"$t2","fieldmap":{"kind":"marker","label":"Hut","color":"#2a9d3f","id":"m-000001"}}},
            {"type":"Feature","id":"no","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}},
            {"type":"Feature","id":"m-000003","geometry":{"type":"LineString","coordinates":[[1,2],[3,4]]},"properties":{}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[3,4]},"properties":{"fieldmap":{"kind":"marker","label":"By its own id","color":"#111111","id":"m-000004"}}}],
          "removed":["m-000009", 5]}"""
        val s = ShareJson.state(text)!!
        assertEquals(42, s.rev)
        assertFalse(s.full)
        assertEquals(
            listOf(
                Peer("phone-aaaa", "Rover", 45.5, -100.25, alt = 300.5, course = 90.0, speed = 1.5, age = 3),
                Peer("phone-bbbb", "y".repeat(24), 1.0, 2.0),
            ),
            s.positions,
        )
        assertEquals(
            listOf(
                Marker("m-000001", 60.0, 10.5, "Hut", "#2a9d3f", shared = true, updated = t2, by = "phone-aaaa"),
                Marker("m-000004", 4.0, 3.0, "By its own id", "#111111", shared = true),
            ),
            s.markers,
        )
        assertEquals(listOf("m-000009"), s.removed)

        assertTrue(ShareJson.state("""{"rev":1,"full":true}""")!!.full)
        assertNull(ShareJson.state("not json"))
        assertNull(ShareJson.state("""{"error":"no"}"""))
    }

    @Test
    fun `what is sent to the box reads back as the same marker`() {
        val m = shared("m-000001", "Ford", t2, "phone-bbbb")
        val s = ShareJson.state("""{"rev":1,"full":true,"positions":[],"markers":[${ShareJson.marker(m)}],"removed":[]}""")!!
        assertEquals(listOf(m), s.markers)
    }

    @Test
    fun `sharing is asked beside the maps, as pairing is`() {
        assertEquals(listOf("http://192.168.1.10/mapshare/"), ShareClient.bases("http://192.168.1.10/"))
        // Behind Internet-in-a-Box the maps have a prefix; the service is at the root.
        assertEquals(listOf("http://192.168.1.10/mapshare/"), ShareClient.bases("http://192.168.1.10/maps/"))
        // On the tile server's own port: there first (the only one that travels through the gateway), then the web port.
        assertEquals(listOf("http://192.168.1.10:8080/mapshare/", "http://192.168.1.10/mapshare/"), ShareClient.bases("http://192.168.1.10:8080/"))
        assertEquals(emptyList<String>(), ShareClient.bases("nothing"))
    }

    /* ---------- the phone's markers and an answer of the box ---------- */

    @Test
    fun `since - changed markers come in, removed ones go, and this phone's own stay`() {
        val local = listOf(own("p1-private"), shared("m-000001", "Old name"), shared("m-000002"), shared("m-000003"))
        val r = ShareMerge.merge(local, state(listOf(shared("m-000001", "New name", t2), shared("m-000004", updated = t2)), listOf("m-000002", "m-unknown")), Pending())
        assertEquals(listOf(own("p1-private"), shared("m-000001", "New name", t2), shared("m-000003"), shared("m-000004", updated = t2)), r.markers)
        assertTrue(r.pending.empty)
    }

    @Test
    fun `since with nothing changed leaves everything as it is`() {
        val local = listOf(own("p1-private"), shared("m-000001"))
        assertEquals(local, ShareMerge.merge(local, state(), Pending()).markers)
    }

    @Test
    fun `full - the phone's copy of the shared markers is replaced`() {
        val local = listOf(own("p1-private"), shared("m-000001"), shared("m-000002"))
        val r = ShareMerge.merge(local, state(listOf(shared("m-000002", "Renamed", t2), shared("m-000005")), full = true), Pending())
        assertEquals(listOf(own("p1-private"), shared("m-000002", "Renamed", t2), shared("m-000005")), r.markers)
    }

    @Test
    fun `full - a marker shared here that the box has not heard of yet is kept and still to be sent`() {
        val local = listOf(shared("m-000001"), shared("m-new-here", updated = t2))
        val r = ShareMerge.merge(local, state(full = true), Pending(put = setOf("m-new-here")))
        assertEquals(listOf(shared("m-new-here", updated = t2)), r.markers)
        assertEquals(setOf("m-new-here"), r.pending.put)
    }

    @Test
    fun `last write wins - a change made here stays only if it is the later one`() {
        val later = ShareMerge.merge(listOf(shared("m-000001", "Mine", t3)), state(listOf(shared("m-000001", "Theirs", t2))), Pending(put = setOf("m-000001")))
        assertEquals(listOf(shared("m-000001", "Mine", t3)), later.markers)
        assertEquals(setOf("m-000001"), later.pending.put)

        val earlier = ShareMerge.merge(listOf(shared("m-000001", "Mine", t1)), state(listOf(shared("m-000001", "Theirs", t2))), Pending(put = setOf("m-000001")))
        assertEquals(listOf(shared("m-000001", "Theirs", t2)), earlier.markers)
        assertTrue(earlier.pending.empty)

        // The same second: the phone's own unsent change is not thrown away.
        val same = ShareMerge.merge(listOf(shared("m-000001", "Mine", t2)), state(listOf(shared("m-000001", "Theirs", t2))), Pending(put = setOf("m-000001")))
        assertEquals("Mine", same.markers[0].label)

        // Not waiting to be sent: the box's version is the one, whatever the times say.
        val settled = ShareMerge.merge(listOf(shared("m-000001", "Mine", t3)), state(listOf(shared("m-000001", "Theirs", t2))), Pending())
        assertEquals("Theirs", settled.markers[0].label)
    }

    @Test
    fun `a marker changed here while it was deleted there goes to the box again`() {
        val r = ShareMerge.merge(listOf(shared("m-000001", "Mine", t3)), state(removed = listOf("m-000001")), Pending(put = setOf("m-000001")))
        assertEquals(listOf(shared("m-000001", "Mine", t3)), r.markers)
        assertEquals(setOf("m-000001"), r.pending.put)
    }

    @Test
    fun `a delete made here stands until the box has done it, unless the marker was changed there later`() {
        // Still on the box, not changed since: stays away here, the delete is still to be sent.
        val stands = ShareMerge.merge(emptyList(), state(listOf(shared("m-000001", updated = t1))), Pending(deleted = mapOf("m-000001" to t2)))
        assertEquals(emptyList<Marker>(), stands.markers)
        assertEquals(mapOf("m-000001" to t2), stands.pending.deleted)

        // Changed on another phone after the delete: it comes back.
        val back = ShareMerge.merge(emptyList(), state(listOf(shared("m-000001", "Still needed", t3))), Pending(deleted = mapOf("m-000001" to t2)))
        assertEquals(listOf(shared("m-000001", "Still needed", t3)), back.markers)
        assertTrue(back.pending.empty)

        // Gone on the box already: nothing left to send.
        assertTrue(ShareMerge.merge(emptyList(), state(removed = listOf("m-000001")), Pending(deleted = mapOf("m-000001" to t2))).pending.empty)
        assertTrue(ShareMerge.merge(emptyList(), state(full = true), Pending(deleted = mapOf("m-000001" to t2))).pending.empty)
        // The box said nothing about it: the delete is still to be sent.
        assertEquals(mapOf("m-000001" to t2), ShareMerge.merge(emptyList(), state(), Pending(deleted = mapOf("m-000001" to t2))).pending.deleted)
    }

    @Test
    fun `a marker taken back to this phone alone stays here while the box is told`() {
        val mine = own("m-000001", "Mine again")
        val r = ShareMerge.merge(listOf(mine), state(listOf(shared("m-000001", "On the box", t1)), full = true), Pending(deleted = mapOf("m-000001" to t2)))
        assertEquals(listOf(mine), r.markers)
        assertEquals(mapOf("m-000001" to t2), r.pending.deleted)
    }

    @Test
    fun `an id removed and put again is there, and a marker under the id of one here is that marker`() {
        val again = ShareMerge.merge(listOf(shared("m-000001", "Before")), state(listOf(shared("m-000001", "After", t2)), removed = listOf("m-000001")), Pending())
        assertEquals(listOf(shared("m-000001", "After", t2)), again.markers)

        val same = ShareMerge.merge(listOf(own("m-000001", "From a file")), state(listOf(shared("m-000001", "Shared by another", t2))), Pending())
        assertEquals(listOf(shared("m-000001", "Shared by another", t2)), same.markers)
    }

    @Test
    fun `what waits to be sent never names a marker that is no longer shared here`() {
        val r = ShareMerge.merge(listOf(own("m-000001")), state(full = true), Pending(put = setOf("m-000001", "m-gone")))
        assertTrue(r.pending.put.isEmpty())
    }

    /* ---------- growing old ---------- */

    @Test
    fun `a position is live, then old, then gone`() {
        // The box's own numbers: a post every 5 s, dropped after 120 s.
        assertEquals(30, Ageing.freshFor(5, 120))
        assertEquals(Ageing.Look.FRESH, Ageing.look(0, 5, 120))
        assertEquals(Ageing.Look.FRESH, Ageing.look(29, 5, 120))
        assertEquals(Ageing.Look.OLD, Ageing.look(30, 5, 120))
        assertEquals(Ageing.Look.OLD, Ageing.look(119, 5, 120))
        assertEquals(Ageing.Look.GONE, Ageing.look(120, 5, 120))
        // A slow pace: live for six posts, but never for more than half the way to being dropped.
        assertEquals(180, Ageing.freshFor(30, 600))
        assertEquals(30, Ageing.freshFor(30, 60))
    }

    @Test
    fun `a phone that said it stays where it is is not old for sending seldom`() {
        assertEquals(90, Ageing.freshFor(5, 120, still = true))
        assertEquals(Ageing.Look.FRESH, Ageing.look(70, 5, 120, still = true))
        assertEquals(Ageing.Look.OLD, Ageing.look(70, 5, 120))
        assertEquals(Ageing.Look.OLD, Ageing.look(95, 5, 120, still = true))
        // Staying or not, the box's limit is the limit.
        assertEquals(Ageing.Look.GONE, Ageing.look(120, 5, 120, still = true))
        // What a phone says about itself: no speed is no claim, a crawl is not staying.
        assertTrue(Peer("phone-aaaa", "A", 1.0, 2.0, speed = 0.0).still)
        assertFalse(Peer("phone-aaaa", "A", 1.0, 2.0).still)
        assertFalse(Peer("phone-aaaa", "A", 1.0, 2.0, speed = 0.8).still)
    }

    @Test
    fun `an old position fades steadily until it is dropped`() {
        assertEquals(1f, Ageing.opacity(10, 5, 120), 0f)
        assertEquals(0.85f, Ageing.opacity(30, 5, 120), 0.001f)
        assertEquals(0.625f, Ageing.opacity(75, 5, 120), 0.001f)
        assertEquals(0f, Ageing.opacity(120, 5, 120), 0f)
        val steps = (30..119).map { Ageing.opacity(it, 5, 120) }
        assertEquals(steps.sortedDescending(), steps)
        assertTrue(steps.last() > 0.4f)
        assertEquals(1f, Ageing.opacity(80, 5, 120, still = true), 0f)
    }

    /* ---------- in the background ---------- */

    @Test
    fun `in the background a phone sends seldom, and seldomer at rest, yet always in time`() {
        val box = ShareInfo(true, 5, 120)
        assertEquals(20, Pace.moving(box))
        assertEquals(60, Pace.still(box))
        // On the move a position is still live on the others' maps when the next one is due,
        // even one poll late; at rest two are sent before the box would drop the phone.
        assertTrue(Pace.moving(box) + box.interval < Ageing.freshFor(box.interval, box.stale))
        assertTrue(Pace.still(box) + box.interval < Ageing.freshFor(box.interval, box.stale, still = true))
        assertTrue(2 * Pace.still(box) <= box.stale)
        // Other boxes, other numbers: the same holds.
        for (other in listOf(ShareInfo(true, 2, 30), ShareInfo(true, 10, 300), ShareInfo(true, 30, 600), ShareInfo(true, 2, 4), ShareInfo(true, 60, 120))) {
            assertTrue("$other", Pace.moving(other) >= 15)
            assertTrue("$other", Pace.still(other) >= Pace.moving(other))
        }
        assertEquals(15, Pace.moving(ShareInfo(true, 2, 30)))
        assertEquals(40, Pace.moving(ShareInfo(true, 10, 300)))
        assertEquals(150, Pace.still(ShareInfo(true, 10, 300)))
    }

    @Test
    fun `a phone counts as staying after three positions in one place, and as moving at once`() {
        val m = Mover()
        assertFalse(m.still)
        assertFalse(m.take(45.0, 7.0, 5.0, 0.0))                // the first position: where it rests
        assertFalse(m.take(45.00001, 7.0, 5.0, 0.0))            // a metre of drift
        assertFalse(m.take(45.0, 7.00001, 5.0, null))
        assertTrue(m.take(45.00002, 7.0, 5.0, 0.1))             // the third without movement
        assertTrue(m.still)
        assertFalse(m.take(45.0001, 7.0, 5.0, 0.0))             // 11 m: still within what counts as one place
        assertTrue(m.still)
        assertTrue(m.take(45.0005, 7.0, 5.0, 0.0))              // 55 m away: on the move
        assertFalse(m.still)
        // It must rest again, at the new place, to count as staying.
        assertFalse(m.take(45.0005, 7.0, 5.0, 0.0))
        assertFalse(m.take(45.0005, 7.0, 5.0, 0.0))
        assertTrue(m.take(45.0005, 7.0, 5.0, 0.0))
        assertTrue(m.still)
    }

    @Test
    fun `speed says moving only when the position is good enough to say so`() {
        val walking = Mover()
        repeat(6) { walking.take(45.0 + it * 0.00005, 7.0, 5.0, 1.4) }      // 5.5 m a step, at a walk
        assertFalse(walking.still)

        val indoors = Mover()
        // A rough position that wanders and claims a speed: it stays within its own doubt.
        indoors.take(45.0, 7.0, 80.0, 2.0)
        indoors.take(45.0005, 7.0, 80.0, 2.0)
        indoors.take(45.0, 7.0005, 80.0, 2.0)
        indoors.take(45.0003, 7.0, 80.0, 2.0)
        assertTrue(indoors.still)
    }

    @Test
    fun `the motion sensor ends the rest at once`() {
        val m = Mover()
        repeat(4) { m.take(45.0, 7.0, 5.0, 0.0) }
        assertTrue(m.still)
        assertTrue(m.nudge())
        assertFalse(m.still)
        assertFalse(m.nudge())                                  // nothing changed the second time
        // A false alarm: three positions at the same place later it rests again.
        m.take(45.0, 7.0, 5.0, 0.0)
        m.take(45.0, 7.0, 5.0, 0.0)
        assertTrue(m.take(45.0, 7.0, 5.0, 0.0))
    }

    @Test
    fun `distances are right to well within what matters here`() {
        assertEquals(111_195.0, Mover.metres(0.0, 0.0, 1.0, 0.0), 10.0)
        assertEquals(78_626.0, Mover.metres(45.0, 0.0, 45.0, 1.0), 10.0)
        assertEquals(0.0, Mover.metres(45.0, 7.0, 45.0, 7.0), 0.0)
        assertEquals(11.1, Mover.metres(45.0, 7.0, 45.0001, 7.0), 0.1)
    }

    @Test
    fun `how old, in words`() {
        assertEquals("now", Ageing.text(0))
        assertEquals("now", Ageing.text(4))
        assertEquals("12 s ago", Ageing.text(12))
        assertEquals("89 s ago", Ageing.text(89))
        assertEquals("1 min ago", Ageing.text(90))
        assertEquals("45 min ago", Ageing.text(2700))
        assertEquals("2 h ago", Ageing.text(7200))
    }

    /* ---------- on the phone and in files ---------- */

    @Test
    fun `the phone's own file remembers what is shared, a file for others does not say`() {
        val list = listOf(own("p1-private"), shared("m-000001", "Ford", t2, "phone-bbbb"))
        assertEquals(list, Drawings.fromGeoJson(Drawings.toGeoJson(list, sharing = true), "#000000") { "new" }.markers)
        val file = Drawings.toGeoJson(list)
        assertFalse(file.contains("phone-bbbb"))
        assertFalse(file.contains("shared"))
        assertEquals(listOf(own("p1-private"), own("m-000001", "Ford").copy(color = "#1f5fa8")), Drawings.fromGeoJson(file, "#000000") { "new" }.markers)
    }

    @Test
    fun `markers loaded from a file are this phone's own until they are shared`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fieldmap-share-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = MarkerStore(File(dir, "markers.geojson"))
            assertEquals(1, store.addAll(listOf(shared("m-000001", "Ford", t2, "phone-bbbb"))))
            assertEquals(listOf(own("m-000001", "Ford").copy(color = "#1f5fa8")), store.markers)
            // What a round with the box leaves is kept, and is there again after a restart.
            store.replaceAll(listOf(shared("m-000002", "Hut", t3)))
            assertEquals(listOf(shared("m-000002", "Hut", t3)), MarkerStore(File(dir, "markers.geojson")).markers)
        } finally {
            dir.deleteRecursively()
        }
    }
}
