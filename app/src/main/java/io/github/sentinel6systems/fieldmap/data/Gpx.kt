package io.github.sentinel6systems.fieldmap.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A recorded track as a GPX 1.1 file: the format other map and fitness apps read tracks in. */
object Gpx {
    fun track(m: Marker): String {
        val track = m.track ?: return ""
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = StringBuilder()
        out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        out.append("""<gpx version="1.1" creator="Fieldmap" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        out.append("<trk><name>").append(escape(m.label)).append("</name>\n")
        for (segment in track.segments) {
            out.append("<trkseg>\n")
            for (p in segment) {
                out.append(String.format(Locale.US, """<trkpt lat="%.7f" lon="%.7f">""", p.lat, p.lon))
                p.alt?.let { out.append(String.format(Locale.US, "<ele>%.1f</ele>", it)) }
                if (p.time > 0) out.append("<time>").append(time.format(Date(p.time))).append("</time>")
                out.append("</trkpt>\n")
            }
            out.append("</trkseg>\n")
        }
        out.append("</trk>\n</gpx>\n")
        return out.toString()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
