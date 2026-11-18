package io.github.sentinel6systems.fieldmap.geo

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

// UTM and MGRS on WGS84 (Karney/Krüger series). The same maths as the print tool of
// fieldmap-server (print/src/utm.js), so that a grid reference read in the app is the one
// printed on the sheet.
object Utm {
    private const val A0 = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private val E = sqrt(F * (2 - F))
    private val N = F / (2 - F)
    private val N2 = N * N
    private val N3 = N2 * N
    private val N4 = N3 * N
    private val N5 = N4 * N
    private val N6 = N5 * N
    private val A = (A0 / (1 + N)) * (1 + N2 / 4 + N4 / 64 + N6 / 256)

    private val ALPHA = doubleArrayOf(
        0.0,
        N / 2 - (2.0 / 3) * N2 + (5.0 / 16) * N3 + (41.0 / 180) * N4 - (127.0 / 288) * N5 + (7891.0 / 37800) * N6,
        (13.0 / 48) * N2 - (3.0 / 5) * N3 + (557.0 / 1440) * N4 + (281.0 / 630) * N5 - (1983433.0 / 1935360) * N6,
        (61.0 / 240) * N3 - (103.0 / 140) * N4 + (15061.0 / 26880) * N5 + (167603.0 / 181440) * N6,
        (49561.0 / 161280) * N4 - (179.0 / 168) * N5 + (6601661.0 / 7257600) * N6,
        (34729.0 / 80640) * N5 - (3418889.0 / 1995840) * N6,
        (212378941.0 / 319334400) * N6,
    )

    private const val D2R = Math.PI / 180

    class Position(val zone: Int, val south: Boolean, val easting: Double, val northing: Double)

    /** UTM zone number for a position, with the Norway and Svalbard exceptions. */
    fun zoneFor(lat: Double, lon: Double): Int {
        var zone = floor((lon + 180) / 6).toInt() + 1
        if (zone == 61) zone = 60
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32
        if (lat >= 72 && lat < 84) {
            if (lon >= 0 && lon < 9) zone = 31
            else if (lon >= 9 && lon < 21) zone = 33
            else if (lon >= 21 && lon < 33) zone = 35
            else if (lon >= 33 && lon < 42) zone = 37
        }
        return zone
    }

    /** Latitude band letter (C..X, no I or O). */
    fun bandFor(lat: Double): Char {
        val i = floor((lat + 80) / 8).toInt()
        return "CDEFGHJKLMNPQRSTUVWX"[i.coerceIn(0, 19)]
    }

    fun toUtm(lat: Double, lon: Double): Position {
        val zone = zoneFor(lat, lon)
        val lon0 = (zone - 1) * 6 - 180 + 3
        val phi = lat * D2R
        val lambda = (lon - lon0) * D2R

        val tau = tan(phi)
        val sigma = sinh(E * atanh((E * tau) / sqrt(1 + tau * tau)))
        val tauP = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
        val xiP = atan2(tauP, cos(lambda))
        val etaP = asinh(sin(lambda) / sqrt(tauP * tauP + cos(lambda) * cos(lambda)))

        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            xi += ALPHA[j] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += ALPHA[j] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        val x = K0 * A * eta + 500000
        val y = K0 * A * xi + if (lat < 0) 10000000 else 0
        return Position(zone, lat < 0, x, y)
    }

    /** 100 km square letters for an easting/northing in a zone (MGRS, AA scheme). */
    fun squareId(zone: Int, easting: Double, northing: Double): String {
        val colLetters = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")[(zone - 1) % 3]
        val rowLetters = if (zone % 2 == 1) "ABCDEFGHJKLMNPQRSTUV" else "FGHJKLMNPQRSTUVABCDE"
        val col = floor(easting / 100000).toInt() - 1
        val row = floor(northing / 100000).toInt() % 20
        return "" + colLetters[col.coerceIn(0, 7)] + rowLetters[row]
    }

    /**
     * MGRS reference to `digits` (1..5) for a position, as "18S UJ 12345 67890".
     * Null outside the UTM latitudes (south of 80°S or north of 84°N), where MGRS uses
     * another grid.
     */
    fun toMgrs(lat: Double, lon: Double, digits: Int = 5): String? {
        if (lat < -80 || lat >= 84 || abs(lon) > 180) return null
        val u = toUtm(lat, lon)
        val div = 10.0.pow(5 - digits)
        val es = floor((u.easting % 100000) / div).toLong().toString().padStart(digits, '0')
        val ns = floor((u.northing % 100000) / div).toLong().toString().padStart(digits, '0')
        return "${u.zone}${bandFor(lat)} ${squareId(u.zone, u.easting, u.northing)} $es $ns"
    }
}
