package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.mesh.MeshCodec
import io.github.sentinel6systems.fieldmap.mesh.proto.MeshMessage
import io.github.sentinel6systems.fieldmap.mesh.proto.MeshPosition
import io.github.sentinel6systems.fieldmap.share.Fix
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fieldmap's own messages over the mesh: what goes out, what comes back, and that they fit. */
class MeshCodecTest {
    private val uid = "00000000-0000-0000-0000-000000000000"
    private val now = 1_800_000_000_000L
    private val fix = Fix(48.1234567, -122.7654321, 112.4, 4.6, 271.0, 1.37, now - 3_000)

    @Test fun aPositionGoesOutAndComesBackAsAPersonOnTheMesh() {
        val bytes = MeshCodec.position(uid, "Blue Team Lead", fix, moving = true)
        assertTrue("one Meshtastic packet holds 233 bytes; ${bytes.size}", bytes.size <= 70)
        val p = MeshCodec.peer(MeshCodec.decode(bytes)!!.position!!, now)!!
        assertEquals(uid, p.uid)
        assertEquals("Blue Team Lead", p.name)
        assertEquals(48.1234567, p.lat, 1e-7)
        assertEquals(-122.7654321, p.lon, 1e-7)
        assertEquals(112.0, p.alt!!, 0.0)
        assertEquals(271.0, p.course!!, 0.0)
        assertEquals(1.37, p.speed!!, 0.001)
        assertEquals("heard 3 s after the fix", 3, p.age)
        assertTrue(p.mesh)
    }

    @Test fun aDeviceThatStaysWhereItIsSaysSo() {
        val p = MeshCodec.peer(MeshCodec.decode(MeshCodec.position(uid, "A", fix, moving = false))!!.position!!, now)!!
        assertEquals(0.0, p.speed!!, 0.0)
        assertTrue(p.still)
    }

    @Test fun aUidThatIsNotAUuidTravelsAsText() {
        assertEquals(16, MeshCodec.uidBytes(uid).size)
        assertEquals("test-phone-0001", MeshCodec.uidText(MeshCodec.uidBytes("test-phone-0001")))
        assertNull(MeshCodec.uidText("no!".toByteArray().toByteString()))
    }

    @Test fun aWrongClockOrAWrongPlaceIsNotBelieved() {
        val ahead = MeshCodec.position(uid, "A", fix.copy(time = now + 600_000), moving = true)
        assertEquals("a fix from the future: age 0", 0, MeshCodec.peer(MeshCodec.decode(ahead)!!.position!!, now)!!.age)
        val bad = MeshPosition(uid = MeshCodec.uidBytes(uid), name = "A", lat = 950_000_000, lon = 0, time = 1)
        assertNull(MeshCodec.peer(bad, now))
        assertNull("0,0: no position at all", MeshCodec.peer(bad.copy(lat = 0), now))
        assertNull(MeshCodec.decode(byteArrayOf(0x7f, 0x7f, 0x7f)))
    }

    @Test fun aMarkerAndItsDeletionGoOutAndComeBack() {
        val m = Marker("lq3k9zabc", 48.5, -122.25, "Water point 2", "#1f5fa8", shared = true, updated = "2027-01-15T08:00:00Z", by = uid)
        val bytes = MeshCodec.marker(m)
        assertTrue("${bytes.size} bytes", bytes.size <= 90)
        val back = MeshCodec.change(MeshCodec.decode(bytes)!!.marker!!)!!
        assertEquals(m, back.marker)
        assertEquals("2027-01-15T08:00:00Z", back.at)
        val gone = MeshCodec.change(MeshCodec.decode(MeshCodec.deleted("lq3k9zabc", 1_800_000_123_456L, uid))!!.marker!!)!!
        assertNull(gone.marker)
        assertEquals("lq3k9zabc", gone.id)
        assertEquals("2027-01-15T08:02:03Z", gone.at)
        assertNull("an id the server would not take", MeshCodec.change(MeshMessage.ADAPTER.decode(bytes).marker!!.copy(id = "x")))
    }

    @Test fun theLongestMessagesStillFitOnePacket() {
        val m = Marker("a".repeat(64), -89.9999999, 179.9999999, "L".repeat(30), "#ffffff", true, "2027-01-15T08:00:00Z", "b".repeat(64))
        assertTrue(MeshCodec.marker(m).size <= 233)
        assertTrue(MeshCodec.position("c".repeat(64), "N".repeat(24), fix, true).size <= 233)
        assertNotNull(MeshCodec.decode(MeshCodec.marker(m))?.marker)
    }
}
