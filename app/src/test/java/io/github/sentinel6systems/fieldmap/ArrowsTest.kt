package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.geo.Arrows
import io.github.sentinel6systems.fieldmap.geo.Grid
import io.github.sentinel6systems.fieldmap.mesh.MeshCodec
import io.github.sentinel6systems.fieldmap.share.ShareJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arrows as the print tool makes them: the shape, the file, and how they travel. */
class ArrowsTest {
    private val arrow = Marker("lq3k9zarr", 48.0, -122.0, "A1", "#1f5fa8", endLat = 48.01, endLon = -122.0)

    @Test fun lengthBearingAndHeadAsThePrintToolWorksThemOut() {
        val (d, b) = Arrows.inverse(48.0, -122.0, 48.01, -122.0)
        assertEquals(1111.95, d, 0.1)
        assertEquals("due north", 0.0, b, 1e-6)
        assertEquals(90.0, Arrows.inverse(0.0, 0.0, 0.0, 0.01).second, 1e-6)
        val (lat, lon) = Arrows.destination(48.0, -122.0, 0.0, 1111.95)
        assertEquals(48.01, lat, 1e-6)
        assertEquals(-122.0, lon, 1e-9)
        // Head strokes: as long as asked, 28° either side of the way back, as on the map in the browser.
        val (h1, h2) = Arrows.head(39.8, -105.2, 39.81, -105.2, 100.0)
        for (h in listOf(h1, h2)) assertEquals(100.0, Arrows.inverse(39.81, -105.2, h.first, h.second).first, 0.01)
        assertEquals(152.0, Arrows.inverse(39.81, -105.2, h1.first, h1.second).second, 0.01)
        assertEquals(208.0, Arrows.inverse(39.81, -105.2, h2.first, h2.second).second, 0.01)
        // At most 40 % of a short arrow (11.1 m here).
        val short = Arrows.head(39.8, -105.2, 39.8001, -105.2, 100.0).first
        assertEquals(4.45, Arrows.inverse(39.8001, -105.2, short.first, short.second).first, 0.01)
    }

    @Test fun theHeadIsTheSameSizeOnTheScreenAtEveryZoom() {
        // 18 px: about 33 m at zoom 15 here, half that one zoom in.
        assertEquals(33.03, Arrows.headLength(39.8, 15.0), 0.01)
        assertEquals(Arrows.headLength(39.8, 15.0) / 2, Arrows.headLength(39.8, 16.0), 1e-9)
        assertEquals(18.0 * Grid.metersPerPixel(39.8, 12.5), Arrows.headLength(39.8, 12.5), 1e-9)
    }

    @Test fun theLabelAlongTheShaftStaysUpright() {
        assertEquals(0f, Arrows.labelAngle(90.0))
        assertEquals(0f, Arrows.labelAngle(270.0))
        assertEquals(-90f, Arrows.labelAngle(0.0))
        assertEquals(-45f, Arrows.labelAngle(45.0))
        assertEquals(45f, Arrows.labelAngle(135.0))
    }

    @Test fun anArrowIsWrittenAndReadAsThePrintToolDoes() {
        val f = Drawings.feature(arrow)
        assertEquals("LineString", f.getJSONObject("geometry").getString("type"))
        assertEquals("[[-122,48],[-122,48.01]]", f.getJSONObject("geometry").getJSONArray("coordinates").toString())
        assertEquals("arrow", f.getJSONObject("properties").getJSONObject("fieldmap").getString("kind"))
        val back = Drawings.fromGeoJson(Drawings.toGeoJson(listOf(arrow)), "#d7263d") { "new-id-0001" }
        assertEquals(listOf(arrow), back.markers)
        // The print tool's own file: no id, the colour in fieldmap.
        val printTool = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-122,48],[-122.1,48.1]]},
            "properties":{"name":"A3","stroke":"#2a9d3f","fieldmap":{"kind":"arrow","label":"A3","color":"#2a9d3f"}}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1],[2,2]]},"properties":{}}]}"""
        val loaded = Drawings.fromGeoJson(printTool, "#d7263d") { "new-id-0002" }
        // A longer line is a route (RoutesTest).
        assertEquals(Marker("new-id-0002", 48.0, -122.0, "A3", "#2a9d3f", endLat = 48.1, endLon = -122.1), loaded.markers[0])
        assertEquals(true, loaded.markers[1].isRoute)
        assertEquals("a line of three points is not an arrow", 0, loaded.skipped)
    }

    @Test fun anAppThatDoesNotKnowArrowsCannotTurnOneIntoAPoint() {
        // Over the mesh an arrow is its own kind of message: an older app finds no marker in it.
        val decoded = MeshCodec.decode(MeshCodec.marker(arrow.copy(shared = true, updated = "2027-01-15T08:00:00Z"))!!)!!
        assertEquals(null, decoded.marker)
        assertEquals(arrow.id, decoded.arrow?.id)
        // Its point copy, come back through the box, does not replace the arrow; a shared arrow goes to the box again.
        val mine = arrow.copy(shared = true, updated = "2027-01-15T08:00:00Z")
        val point = mine.copy(endLat = null, endLon = null, updated = "2027-01-15T08:05:00Z")
        val state = io.github.sentinel6systems.fieldmap.share.ShareState(5, true, emptyList(), listOf(point), emptyList())
        val merged = io.github.sentinel6systems.fieldmap.share.ShareMerge.merge(listOf(mine), state, io.github.sentinel6systems.fieldmap.share.Pending())
        assertEquals(listOf(mine), merged.markers)
        assertEquals(setOf(arrow.id), merged.pending.put)
    }

    @Test fun arrowsAreNamedApartFromMarkers() {
        val list = listOf(Marker("m1", 0.0, 0.0, "P1", "#d7263d"), arrow)
        assertEquals("P2", Drawings.nextName(list))
        assertEquals("A2", Drawings.nextName(list, arrow = true))
    }

    @Test fun aSharedArrowGoesThroughTheServerAndOverTheMesh() {
        val shared = arrow.copy(shared = true, updated = "2027-01-15T08:00:00Z", by = "test-phone-0001")
        val state = ShareJson.state("""{"rev":2,"full":true,"positions":[],"removed":[],"markers":[${ShareJson.marker(shared)}]}""")!!
        assertEquals(listOf(shared), state.markers)
        val bytes = MeshCodec.marker(shared)!!
        assertTrue("${bytes.size} bytes", bytes.size <= 100)
        assertEquals(shared, MeshCodec.change(MeshCodec.decode(bytes)!!.arrow!!)!!.marker)
        assertEquals("a marker stays a marker", null, MeshCodec.change(MeshCodec.decode(MeshCodec.marker(shared.copy(endLat = null, endLon = null))!!)!!.marker!!)!!.marker!!.endLat)
        JSONObject(ShareJson.marker(shared))       // valid JSON
    }
}
