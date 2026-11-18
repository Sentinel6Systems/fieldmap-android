package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.offline.Box
import io.github.sentinel6systems.fieldmap.offline.TileRange
import io.github.sentinel6systems.fieldmap.offline.TileSource
import io.github.sentinel6systems.fieldmap.offline.Tiles
import io.github.sentinel6systems.fieldmap.offline.bytesText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    private val base = TileSource("openmaptiles", raster = false, tileSize = 512, minZoom = 0, maxZoom = 14, template = "http://192.0.2.10/data/basemap/{z}/{x}/{y}.pbf")
    private val contours = TileSource("terrain", raster = false, tileSize = 512, minZoom = 8, maxZoom = 14, template = "")
    private val shade = TileSource("hillshade", raster = true, tileSize = 512, minZoom = 6, maxZoom = 12, template = "")

    @Test
    fun `tile numbers follow the usual scheme`() {
        assertEquals(0, Tiles.x(-180.0, 3))
        assertEquals(7, Tiles.x(180.0, 3))
        assertEquals(4, Tiles.x(0.0, 3))
        assertEquals(4, Tiles.y(0.0, 3))
        assertEquals(0, Tiles.y(89.0, 3))
        assertEquals(7, Tiles.y(-89.0, 3))
        // One degree east and north of 10°, 10° at zoom 10: columns 540..543, rows 480..483.
        assertEquals(TileRange(10, 540, 480, 543, 483), Tiles.range(Box(10.0, 10.0, 11.0, 11.0), 10))
    }

    @Test
    fun `each source gives its own zoom levels inside the area's`() {
        assertEquals(0..14, Tiles.zooms(base))
        assertEquals(8..14, Tiles.zooms(contours))
        assertEquals(6..12, Tiles.zooms(shade))
        // Picture tiles of 256 px are drawn one level deeper.
        assertEquals(1..15, Tiles.zooms(shade.copy(tileSize = 256, minZoom = 0, maxZoom = 18)))
    }

    @Test
    fun `a small area needs one tile at each low zoom and more at each deeper one`() {
        val box = Box(10.0, 10.0, 10.2, 10.2)
        val ranges = Tiles.ranges(base, box)
        assertEquals(15, ranges.size)
        assertTrue(ranges.take(8).all { it.count == 1L })
        assertTrue(ranges.zipWithNext().all { (a, b) -> b.count >= a.count })
        assertEquals(ranges.sumOf { it.count } + Tiles.ranges(shade, box).sumOf { it.count }, Tiles.count(listOf(base, shade), box))
    }

    @Test
    fun `four times the area is about four times the tiles`() {
        val small = Tiles.count(listOf(base), Box(10.0, 10.0, 10.5, 10.5))
        val large = Tiles.count(listOf(base), Box(10.0, 10.0, 11.0, 11.0))
        assertTrue("$small -> $large", large in small * 3..small * 5)
    }

    @Test
    fun `a small range is measured whole`() {
        val r = TileRange(10, 540, 480, 542, 481)
        assertEquals(listOf(540 to 480, 541 to 480, 542 to 480, 540 to 481, 541 to 481, 542 to 481), Tiles.samples(r, 16))
        assertEquals(listOf(540 to 480), Tiles.samples(TileRange(10, 540, 480, 540, 480), 16))
    }

    @Test
    fun `a large range is measured on an even grid`() {
        val r = TileRange(14, 8000, 5000, 8039, 5019)          // 40 by 20 tiles
        val picks = Tiles.samples(r, 16)
        assertEquals(16, picks.size)
        assertEquals(16, picks.toSet().size)
        assertTrue(picks.all { (x, y) -> x in r.x0..r.x1 && y in r.y0..r.y1 })
        assertEquals(listOf(8005, 8015, 8025, 8035), picks.map { it.first }.distinct())
        assertEquals(listOf(5002, 5007, 5012, 5017), picks.map { it.second }.distinct())
        // A long thin range still gets its full number along the long side.
        assertEquals(16, Tiles.samples(TileRange(14, 8000, 5000, 8099, 5001), 16).size)
    }

    @Test
    fun `tile addresses are filled in`() {
        assertEquals("http://192.0.2.10/data/basemap/10/540/480.pbf", Tiles.url(base, 10, 540, 480))
    }

    @Test
    fun `sizes read in words`() {
        assertEquals("1 KB", bytesText(200))
        assertEquals("640 KB", bytesText(640_000))
        assertEquals("86 MB", bytesText(86_400_000))
        assertEquals("1.2 GB", bytesText(1_200_000_000))
    }
}
