package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.geo.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The grid references must be the ones the print tool of fieldmap-server gives. The expected
 * values were produced by its print/src/utm.js for round positions around the world: both
 * hemispheres, the Norway and Svalbard zones, a zone's edge and its middle.
 */
class UtmTest {
    private class Case(val lat: Double, val lon: Double, val mgrs: String, val mgrs4: String, val easting: Double, val northing: Double)

    private val cases = listOf(
        Case(0.0, 0.0, "31N AA 66021 00000", "31N AA 6602 0000", 166021.443, 0.000),
        Case(10.0, 10.0, "32P PS 09600 05578", "32P PS 0960 0557", 609600.773, 1105578.589),
        Case(45.0, -100.0, "14T MQ 21184 83436", "14T MQ 2118 8343", 421184.697, 4983436.768),
        Case(-33.5, 151.25, "56H LH 37440 91910", "56H LH 3744 9191", 337440.638, 6291910.313),
        Case(60.0, 5.0, "32V KM 76979 58157", "32V KM 7697 5815", 276979.926, 6658157.202),
        Case(60.0, 10.5, "32V NM 83661 52359", "32V NM 8366 5235", 583661.747, 6652359.682),
        Case(75.0, 15.0, "33X WD 00000 23606", "33X WD 0000 2360", 500000.000, 8323606.812),
        Case(-45.0, -70.0, "19G DL 21184 16563", "19G DL 2118 1656", 421184.697, 5016563.232),
        Case(51.5, -0.5, "30U XC 73519 08392", "30U XC 7351 0839", 673519.504, 5708392.653),
        Case(35.0, 139.0, "54S UD 17483 74870", "54S UD 1748 7487", 317483.906, 3874870.635),
        Case(-79.5, 179.9, "60C WS 58974 72753", "60C WS 5897 7275", 558974.643, 1172753.137),
        Case(83.9, -179.9, "1X DP 65609 17795", "1X DP 6560 1779", 465609.169, 9317795.753),
        Case(0.0001, 6.0, "32N JF 66021 00011", "32N JF 6602 0001", 166021.443, 11.068),
        Case(40.0, -74.999, "18T WK 00085 27757", "18T WK 0008 2775", 500085.360, 4427757.219),
        Case(-0.0001, -6.0, "30M SE 66021 99988", "30M SE 6602 9998", 166021.443, 9999988.932),
    )

    @Test
    fun `grid references match the print tool`() {
        for (c in cases) {
            assertEquals("${c.lat}, ${c.lon}", c.mgrs, Utm.toMgrs(c.lat, c.lon))
            assertEquals("${c.lat}, ${c.lon} to 10 m", c.mgrs4, Utm.toMgrs(c.lat, c.lon, 4))
        }
    }

    @Test
    fun `eastings and northings match the print tool to a millimetre`() {
        for (c in cases) {
            val u = Utm.toUtm(c.lat, c.lon)
            assertEquals("easting of ${c.lat}, ${c.lon}", c.easting, u.easting, 0.001)
            assertEquals("northing of ${c.lat}, ${c.lon}", c.northing, u.northing, 0.001)
        }
    }

    @Test
    fun `there is no reference outside the UTM latitudes`() {
        assertNull(Utm.toMgrs(84.5, 10.0))
        assertNull(Utm.toMgrs(-80.5, 10.0))
    }
}
