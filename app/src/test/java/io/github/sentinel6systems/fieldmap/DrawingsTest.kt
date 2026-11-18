package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DrawingsTest {
    private var n = 0
    private fun id() = "new${++n}"

    private val markers = listOf(
        Marker("a1", 45.0, -100.0, "Camp", "#d7263d"),
        Marker("a2", -33.5, 151.25, "", "#1f5fa8"),
    )

    @Test
    fun `what is saved loads back the same`() {
        val loaded = Drawings.fromGeoJson(Drawings.toGeoJson(markers), "#000000", ::id)
        assertEquals(markers, loaded.markers)
        assertEquals(0, loaded.skipped)
    }

    @Test
    fun `the file is what the print tool reads`() {
        // print/src/drawings.js takes a marker from a Point with fieldmap.kind "marker",
        // fieldmap.label and fieldmap.color, in a collection marked with its format name.
        val file = JSONObject(Drawings.toGeoJson(markers))
        assertEquals("FeatureCollection", file.getString("type"))
        assertEquals("fieldmap-drawings", file.getJSONObject("fieldmap").getString("format"))
        assertEquals(1, file.getJSONObject("fieldmap").getInt("version"))
        val first = file.getJSONArray("features").getJSONObject(0)
        assertEquals("Point", first.getJSONObject("geometry").getString("type"))
        assertEquals(-100.0, first.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 0.0)
        assertEquals(45.0, first.getJSONObject("geometry").getJSONArray("coordinates").getDouble(1), 0.0)
        val props = first.getJSONObject("properties")
        assertEquals("Camp", props.getString("name"))
        assertEquals("#d7263d", props.getString("marker-color"))
        assertEquals("marker", props.getJSONObject("fieldmap").getString("kind"))
        assertEquals("Camp", props.getJSONObject("fieldmap").getString("label"))
        assertEquals("#d7263d", props.getJSONObject("fieldmap").getString("color"))
    }

    @Test
    fun `a file of the print tool gives its markers and counts the rest`() {
        // As written by the print tool: a marker, an arrow and a polygon.
        val text = """{"type":"FeatureCollection","fieldmap":{"format":"fieldmap-drawings","version":1,"title":"Test"},"features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[10.5,60]},"properties":{"name":"Hut","stroke":"#2a9d3f","marker-color":"#2a9d3f","fieldmap":{"kind":"marker","label":"Hut","color":"#2a9d3f"}}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[10,60],[10.1,60.1]]},"properties":{"name":"","fieldmap":{"kind":"arrow","label":"","color":"#111111"}}},
          {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[10,60],[10.1,60],[10.1,60.1],[10,60]]]},"properties":{"fieldmap":{"kind":"polygon","label":"Zone","color":"#6f2dbd"}}}]}"""
        val loaded = Drawings.fromGeoJson(text, "#000000", ::id)
        assertEquals(listOf(Marker("new1", 60.0, 10.5, "Hut", "#2a9d3f")), loaded.markers)
        assertEquals(2, loaded.skipped)
    }

    @Test
    fun `points from other software load with their name and colour`() {
        val text = """{"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-70,-45]},"properties":{"title":"  Ford  ","marker-color":"#E6B800"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[190,10]},"properties":{}},
          {"type":"Feature","geometry":{"type":"MultiPoint","coordinates":[[1,2],[3,4]]},"properties":{"name":"Pair"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[0,95]},"properties":{}},
          {"type":"Feature","geometry":null,"properties":{}}]}"""
        val loaded = Drawings.fromGeoJson(text, "#111111", ::id)
        assertEquals(
            listOf(
                Marker("new1", -45.0, -70.0, "Ford", "#e6b800"),
                Marker("new2", 10.0, -170.0, "", "#111111"),
                Marker("new3", 2.0, 1.0, "Pair", "#111111"),
                Marker("new4", 4.0, 3.0, "Pair", "#111111"),
            ),
            loaded.markers,
        )
        assertEquals(2, loaded.skipped)
    }

    @Test
    fun `a single geometry and a single feature are read too`() {
        assertEquals(1, Drawings.fromGeoJson("""{"type":"Point","coordinates":[5,60]}""", "#111111", ::id).markers.size)
        assertEquals(1, Drawings.fromGeoJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[5,60]},"properties":null}""", "#111111", ::id).markers.size)
    }

    @Test
    fun `long names are cut to the print tool's length`() {
        val text = """{"type":"Feature","geometry":{"type":"Point","coordinates":[5,60]},"properties":{"name":"${"x".repeat(50)}"}}"""
        assertEquals(30, Drawings.fromGeoJson(text, "#111111", ::id).markers[0].label.length)
    }

    @Test
    fun `text that is not GeoJSON is refused in plain words`() {
        assertThrows(IllegalArgumentException::class.java) { Drawings.fromGeoJson("not a file", "#111111", ::id) }
        assertThrows(IllegalArgumentException::class.java) { Drawings.fromGeoJson("""{"hello":1}""", "#111111", ::id) }
    }

    @Test
    fun `new markers are named P1, P2 and never reuse a name`() {
        assertEquals("P1", Drawings.nextName(emptyList()))
        assertEquals("P3", Drawings.nextName(markers))
        val named = listOf(Marker("1", 0.0, 0.0, "P2", "#111111"), Marker("2", 0.0, 0.0, "P3", "#111111"))
        assertEquals("P4", Drawings.nextName(named))
    }
}
