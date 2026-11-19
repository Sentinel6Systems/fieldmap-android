package io.github.sentinel6systems.fieldmap.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** The ways the app writes a position. The person chooses one in Menu › Settings. */
enum class CoordFormat(val title: String) {
    MGRS("MGRS grid"),
    UTM("UTM"),
    DECIMAL("Decimal degrees"),
    DMS("Degrees, minutes, seconds"),
}

object Coords {
    /**
     * A position in the chosen format. `brief` is for lists: MGRS to 10 m and whole seconds.
     * Where MGRS and UTM have no grid (near the poles), decimal degrees.
     */
    fun text(lat: Double, lon: Double, format: CoordFormat, brief: Boolean = false): String = when (format) {
        CoordFormat.MGRS -> Utm.toMgrs(lat, lon, if (brief) 4 else 5) ?: decimal(lat, lon)
        CoordFormat.UTM -> utm(lat, lon) ?: decimal(lat, lon)
        CoordFormat.DECIMAL -> decimal(lat, lon)
        CoordFormat.DMS -> "${dms(lat, 'N', 'S', brief)} ${dms(lon, 'E', 'W', brief)}"
    }

    /** 14T 421184E 4983436N: zone and band, then whole metres cut down, as MGRS cuts its digits. */
    private fun utm(lat: Double, lon: Double): String? {
        if (lat < -80 || lat >= 84 || abs(lon) > 180) return null
        val u = Utm.toUtm(lat, lon)
        return "${u.zone}${Utm.bandFor(lat)} ${floor(u.easting).toLong()}E ${floor(u.northing).toLong()}N"
    }

    private fun decimal(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    /** 39°14'14.1"N: rounded once, so that 59.95" becomes the next minute and not 60.0". */
    private fun dms(value: Double, plus: Char, minus: Char, brief: Boolean): String {
        val steps = if (brief) 3600 else 36000
        val total = (abs(value) * steps).roundToLong()
        val degrees = total / steps
        val minutes = total % steps / (steps / 60)
        val rest = total % (steps / 60)
        val seconds = if (brief) String.format(Locale.US, "%02d", rest) else String.format(Locale.US, "%04.1f", rest / 10.0)
        return String.format(Locale.US, "%d°%02d'%s\"%c", degrees, minutes, seconds, if (value < 0) minus else plus)
    }
}
