package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.geo.CoordFormat
import io.github.sentinel6systems.fieldmap.geo.Coords
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordsTest {
    @Test
    fun `each format writes the same position`() {
        assertEquals("14T MQ 21184 83436", Coords.text(45.0, -100.0, CoordFormat.MGRS))
        assertEquals("14T MQ 2118 8343", Coords.text(45.0, -100.0, CoordFormat.MGRS, brief = true))
        // The same metres as the MGRS digits above, and the southern northing with its 10 000 km.
        assertEquals("14T 421184E 4983436N", Coords.text(45.0, -100.0, CoordFormat.UTM))
        assertEquals("56H 337440E 6291910N", Coords.text(-33.5, 151.25, CoordFormat.UTM))
        assertEquals("45.12345, -100.54321", Coords.text(45.12345, -100.54321, CoordFormat.DECIMAL))
        assertEquals("45°07'24.4\"N 100°32'35.6\"W", Coords.text(45.12345, -100.54321, CoordFormat.DMS))
        assertEquals("45°07'24\"N 100°32'36\"W", Coords.text(45.12345, -100.54321, CoordFormat.DMS, brief = true))
        assertEquals("33°30'00.0\"S 151°15'00.0\"E", Coords.text(-33.5, 151.25, CoordFormat.DMS))
    }

    @Test
    fun `seconds that round up carry into the minute and the degree`() {
        // 9.99999° is 9°59'59.964": one decimal of a second gives 10°00'00.0".
        assertEquals("10°00'00.0\"N 0°00'00.0\"E", Coords.text(9.99999, 0.0, CoordFormat.DMS))
        assertEquals("1°01'00\"N 0°00'00\"E", Coords.text(1.0 + 59.6 / 3600, 0.0, CoordFormat.DMS, brief = true))
    }

    @Test
    fun `near the poles MGRS and UTM give decimal degrees`() {
        assertEquals("85.00000, 10.00000", Coords.text(85.0, 10.0, CoordFormat.MGRS))
        assertEquals("-81.00000, 10.00000", Coords.text(-81.0, 10.0, CoordFormat.UTM))
    }
}
