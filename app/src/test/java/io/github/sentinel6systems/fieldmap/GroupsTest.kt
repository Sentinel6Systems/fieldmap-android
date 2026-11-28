package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.GroupState
import io.github.sentinel6systems.fieldmap.data.Groups
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.Shape
import io.github.sentinel6systems.fieldmap.geo.Shapes
import io.github.sentinel6systems.fieldmap.ui.Pick
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Groups, as on the map in the browser: the list, files, rename and delete; shared shapes; the chooser. */
class GroupsTest {
    private var n = 0
    private fun id() = "new${++n}"

    private val gate = Marker("m1", 39.71, -105.21, "Gate", "#d7263d", group = "Hazards")
    private val camp = Marker("m2", 39.72, -105.22, "Camp", "#1f5fa8")
    private val arrow = Marker("a1", 39.71, -105.21, "A1", "#d7263d", endLat = 39.72, endLon = -105.22, group = "Water Points")
    private val pond = Shape("s1", "Hazards", center = 39.73 to -105.23, radius = 150.0, label = "Pond", color = "#2a9d3f")
    private val field = Shape("s2", "Hazards", listOf(39.6 to -105.3, 39.6 to -105.2, 39.7 to -105.2, 39.7 to -105.3), kind = "rect", label = "Field")

    @Test fun theListKeepsTheOrderGroupsWereFirstSeenIn() {
        val kept = listOf(GroupState("Water Points", hidden = true), GroupState("", closed = true))
        val known = Groups.known(kept, listOf("", "Hazards", "Water Points", "Hazards"))
        assertEquals(listOf("Water Points", "Hazards"), known.map { it.name })
        assertTrue("what a group shows is kept", known[0].hidden)
        assertEquals(kept + GroupState("Old"), Groups.read(Groups.write(kept + GroupState("Old"))))
    }

    @Test fun aGroupExportsAsAFileOfThatGroupAndLoadsBackTheSame() {
        val text = Drawings.toGeoJson(listOf(gate), shapes = listOf(pond, field), group = "Hazards")
        val file = JSONObject(text)
        assertEquals("Hazards", file.getJSONObject("fieldmap").getString("group"))
        val features = file.getJSONArray("features")
        // The shapes as the print tool's own file writes them: its circle again exactly, the rectangle's corners.
        val circle = features.getJSONObject(1).getJSONObject("properties").getJSONObject("fieldmap")
        assertEquals("circle", circle.getString("kind"))
        assertEquals(-105.23, circle.getJSONObject("center").getDouble("lng"), 0.0)
        assertEquals(150.0, circle.getDouble("radius"), 0.0)
        val rect = features.getJSONObject(2).getJSONObject("properties").getJSONObject("fieldmap")
        assertEquals(2, rect.getJSONArray("points").length())
        assertEquals(97, features.getJSONObject(1).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).length())
        // Loaded here: the marker in its group; the shapes are skipped (the app draws none of its own).
        val loaded = Drawings.fromGeoJson(text, "#000000", ::id)
        assertTrue(loaded.own)
        assertEquals(listOf(gate), Groups.imported(loaded, "Hazards-2026-11-27.geojson"))
        assertEquals(2, loaded.skipped)
    }

    @Test fun aFileFromOtherSoftwareIsAGroupNamedAfterTheFile() {
        assertEquals("Water Points", Groups.fromFileName("Water Points-2026-11-20.geojson"))
        assertEquals("trail heads", Groups.fromFileName("trail_heads_2026-01-02.json"))
        assertEquals("camps", Groups.fromFileName("camps.geojson"))
        val other = """{"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-105.2,39.7]},"properties":{"name":"Spring"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-105.1,39.8]},"properties":{"name":"Well","fieldmap":{"kind":"marker","group":"Own"}}}]}"""
        val loaded = Drawings.fromGeoJson(other, "#000000", ::id)
        assertFalse(loaded.own)
        assertEquals(listOf("Springs", "Own"), Groups.imported(loaded, "Springs-2026-11-27.geojson").map { it.group })
    }

    @Test fun renameGroupMovesItsItems() {
        val renamed = Groups.renamed(listOf(gate, camp, arrow), "Hazards", "Danger Areas")
        assertEquals(listOf("Danger Areas", "", "Water Points"), renamed.map { it.group })
        // A shared shape goes back to the server in the new group, changed by this phone, with its id.
        val feature = """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[]},"properties":{"name":"Pond","fieldmap":{"kind":"circle","group":"Hazards"},"by":"x","updated":"y"}}"""
        val moved = JSONObject(Groups.moveShape(feature, "s1", "Danger Areas", "phone-aaaa", "2027-01-15T08:00:00Z"))
        assertEquals("s1", moved.getString("id"))
        val p = moved.getJSONObject("properties")
        assertEquals("Danger Areas", p.getJSONObject("fieldmap").getString("group"))
        assertEquals("phone-aaaa" to "2027-01-15T08:00:00Z", p.getString("by") to p.getString("updated"))
        assertFalse("to Ungrouped: no group", JSONObject(Groups.moveShape(feature, "s1", "", "p", "t")).getJSONObject("properties").getJSONObject("fieldmap").has("group"))
    }

    @Test fun deleteGroupTakesItsItemsAndKeepsTheRest() {
        // MainActivity.deleteGroup keeps what is not in the group; the file of what is left has no trace of it.
        val left = listOf(gate, camp, arrow).filter { it.group != "Hazards" }
        assertEquals(listOf("m2", "a1"), left.map { it.id })
        assertEquals(listOf("Water Points"), Groups.known(listOf(GroupState("Hazards")).filter { it.name != "Hazards" }, left.map { it.group }).map { it.name })
    }

    @Test fun aSharedShapeMeasuresAsOnTheMapInTheBrowser() {
        assertEquals("radius 492 ft · 17.5 acres", Shapes.summary(pond, imperial = true))
        assertEquals("radius 150 m · 7.1 ha", Shapes.summary(pond, imperial = false))
        assertTrue(Shapes.summary(field, imperial = false), Regex("^8\\.\\d\\d km × 11\\.1\\d km · 9\\d\\.\\d\\d km²$").matches(Shapes.summary(field, imperial = false)))
        assertEquals(96, Shapes.ring(pond).size)
        assertEquals("Rectangle", Shapes.name(field))
    }

    @Test fun itemsOnTopOfEachOtherAreOfferedOnceEach() {
        val hits = listOf(Pick.MARKER to "m1", Pick.ARROW to "a1", Pick.ARROW to "a1", Pick.ROUTE to "fm-drawn", Pick.SHAPE to "s1")
        assertEquals(listOf(Pick.MARKER to "m1", Pick.ARROW to "a1", Pick.SHAPE to "s1"), Pick.unique(hits, ignore = setOf("fm-drawn")))
        assertEquals("Gate · Marker · Hazards", Pick.text("Gate", "Marker", "Hazards"))
        assertEquals("Arrow · Arrow", Pick.text("", "Arrow", ""))
    }
}
