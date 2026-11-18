package io.github.sentinel6systems.fieldmap.ui

import android.content.Context
import androidx.core.content.edit
import io.github.sentinel6systems.fieldmap.data.Palette
import io.github.sentinel6systems.fieldmap.remote.Enrolment
import io.github.sentinel6systems.fieldmap.server.MapStyle
import io.github.sentinel6systems.fieldmap.server.TileServer

/** What the app remembers between runs, all of it on the phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("fieldmap", Context.MODE_PRIVATE)

    /** The tile server's root address, ending in "/"; null until one is set. */
    var server: String?
        get() = p.getString("server", null)
        set(v) = p.edit { putString("server", v) }

    /** The server's styles as last seen, so that the list is there without the server. */
    var styles: List<MapStyle>
        get() = server?.let { TileServer.parseStyles(p.getString("styles", "[]").orEmpty(), it) }.orEmpty()
        set(v) = p.edit { putString("styles", TileServer.stylesToJson(v)) }

    var styleId: String?
        get() = p.getString("style", null)
        set(v) = p.edit { putString("style", v) }

    /** The colour of the next marker: the last one chosen. */
    var color: String
        get() = p.getString("color", Palette.DEFAULT) ?: Palette.DEFAULT
        set(v) = p.edit { putString("color", v) }

    /** Where the map was: latitude, longitude, zoom. */
    var camera: Triple<Double, Double, Double>?
        get() = if (p.contains("lat")) Triple(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), p.getFloat("zoom", 1f).toDouble()) else null
        set(v) = p.edit { if (v != null) { putFloat("lat", v.first.toFloat()); putFloat("lon", v.second.toFloat()); putFloat("zoom", v.third.toFloat()) } }

    /** What the server gave this phone for the remote link; null when not set up. */
    var enrolment: Enrolment?
        get() {
            val home = p.getString("remote.home", null) ?: return null
            val certificate = p.getString("remote.certificate", null) ?: return null
            val ca = p.getString("remote.ca", null) ?: return null
            return Enrolment(home, p.getString("remote.id", "").orEmpty(), certificate, ca, p.getString("remote.host", null), p.getInt("remote.port", 0))
        }
        set(v) = p.edit {
            if (v == null) listOf("home", "id", "certificate", "ca", "host", "port").forEach { remove("remote.$it") }
            else {
                putString("remote.home", v.home); putString("remote.id", v.id); putString("remote.certificate", v.certificate)
                putString("remote.ca", v.ca); putString("remote.host", v.host); putInt("remote.port", v.port)
            }
        }
}
