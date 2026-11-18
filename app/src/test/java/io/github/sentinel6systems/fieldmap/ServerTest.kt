package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.server.MapStyle
import io.github.sentinel6systems.fieldmap.server.ServerAddress
import io.github.sentinel6systems.fieldmap.server.TileServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTest {
    @Test
    fun `a name or number alone is tried in the three places a server can be`() {
        assertEquals(listOf("http://192.0.2.10/", "http://192.0.2.10/maps/", "http://192.0.2.10:8080/"), ServerAddress.candidates("192.0.2.10"))
        assertEquals(listOf("http://tak.example.com/", "http://tak.example.com/maps/", "http://tak.example.com:8080/"), ServerAddress.candidates("  tak.example.com "))
    }

    @Test
    fun `a port or a folder that was typed is kept`() {
        assertEquals(listOf("http://192.0.2.10:8080/", "http://192.0.2.10:8080/maps/"), ServerAddress.candidates("192.0.2.10:8080"))
        assertEquals(listOf("http://192.0.2.10/maps/"), ServerAddress.candidates("http://192.0.2.10/maps"))
        assertEquals(listOf("https://tak.example.com/", "https://tak.example.com/maps/"), ServerAddress.candidates("https://tak.example.com"))
    }

    @Test
    fun `a pasted page or tile address leads to the server's root`() {
        assertEquals(listOf("http://192.0.2.10:8080/", "http://192.0.2.10:8080/maps/"), ServerAddress.candidates("http://192.0.2.10:8080/styles/topo-light/{z}/{x}/{y}.jpg"))
        assertEquals("http://192.0.2.10/maps/", ServerAddress.candidates("http://192.0.2.10/maps/styles.json")[0])
        assertEquals("http://192.0.2.10/", ServerAddress.candidates("http://192.0.2.10/mapprinter/")[0])
        assertEquals("http://192.0.2.10/maps/", ServerAddress.candidates("192.0.2.10/maps/?style=osm#12/1/2")[0])
    }

    @Test
    fun `what cannot be an address gives nothing to try`() {
        assertEquals(emptyList<String>(), ServerAddress.candidates(""))
        assertEquals(emptyList<String>(), ServerAddress.candidates("my map box"))
        assertEquals(emptyList<String>(), ServerAddress.candidates("ftp://192.0.2.10"))
    }

    @Test
    fun `the short form drops what a person does not type`() {
        assertEquals("192.0.2.10:8080", ServerAddress.short("http://192.0.2.10:8080/"))
        assertEquals("192.0.2.10/maps", ServerAddress.short("http://192.0.2.10/maps/"))
        assertEquals("https://tak.example.com", ServerAddress.short("https://tak.example.com/"))
    }

    @Test
    fun `a list of styles is read in order of name, on the root that answered`() {
        // As TileServer GL answers /styles.json; its own "url" names the host it thinks it has.
        val text = """[{"version":8,"name":"Topo Light","id":"topo-light","url":"http://localhost:8080/styles/topo-light/style.json"},
            {"version":8,"name":"OpenStreetMap","id":"osm","url":"http://localhost:8080/styles/osm/style.json"},{"id":"plain"}]"""
        assertEquals(
            listOf(
                MapStyle("osm", "OpenStreetMap", "http://192.0.2.10/maps/styles/osm/style.json"),
                MapStyle("plain", "plain", "http://192.0.2.10/maps/styles/plain/style.json"),
                MapStyle("topo-light", "Topo Light", "http://192.0.2.10/maps/styles/topo-light/style.json"),
            ),
            TileServer.parseStyles(text, "http://192.0.2.10/maps/"),
        )
        assertEquals(emptyList<MapStyle>(), TileServer.parseStyles("[]", "http://192.0.2.10/"))
    }

    @Test
    fun `styles made for one print scale are left out`() {
        val text = """[{"id":"topo-usgs","name":"Topo USGS"},{"id":"topo-usgs-24k","name":"Topo USGS 1:24,000"},{"id":"topo-usgs-100k","name":"Topo USGS 1:100,000"}]"""
        assertEquals(listOf("topo-usgs"), TileServer.parseStyles(text, "http://192.0.2.10/")?.map { it.id })
    }

    @Test
    fun `an answer that is not a list of styles is not a tile server`() {
        assertNull(TileServer.parseStyles("<html>Welcome</html>", "http://192.0.2.10/"))
        assertNull(TileServer.parseStyles("""{"styles":[]}""", "http://192.0.2.10/"))
        assertNull(TileServer.parseStyles("""[1,2,3]""", "http://192.0.2.10/"))
        assertNull(TileServer.parseStyles("""[{"name":"No id"}]""", "http://192.0.2.10/"))
    }

    @Test
    fun `the remembered list reads back`() {
        val styles = listOf(MapStyle("osm", "OpenStreetMap", "http://192.0.2.10/styles/osm/style.json"))
        assertEquals(styles, TileServer.parseStyles(TileServer.stylesToJson(styles), "http://192.0.2.10/"))
    }
}
