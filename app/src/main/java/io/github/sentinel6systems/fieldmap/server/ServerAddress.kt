package io.github.sentinel6systems.fieldmap.server

import java.net.URI

/** From what a person types to the addresses where a tile server may answer. */
object ServerAddress {
    // Parts of an address that belong to a page or a file of the server, not to its root.
    private val PAGES = listOf("/styles/", "/data/", "/fonts/", "/mapprinter", "/mapadmin", "/styles.json", "/index.html")

    /**
     * The roots to try, most likely first, each ending in "/". A typed name or number alone
     * gives the three places fieldmap-server can be: the web port, /maps/ behind another web
     * server, and TileServer GL's own port. Empty when the text cannot be an address.
     */
    fun candidates(typed: String): List<String> {
        var text = typed.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return emptyList()
        if (!text.contains("://")) text = "http://$text"
        // A pasted tile address has {z}/{x}/{y} in it, which is not a valid address: cut first.
        for (page in PAGES) text.indexOf(page, text.indexOf("://") + 3).let { if (it >= 0) text = text.substring(0, it) }
        val uri = try { URI(text) } catch (e: Exception) { return emptyList() }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) return emptyList()
        val origin = "$scheme://$host" + if (uri.port != -1) ":${uri.port}" else ""
        var path = uri.rawPath.orEmpty()
        if (!path.endsWith("/")) path += "/"
        val out = LinkedHashSet<String>()
        out += origin + path
        if (path == "/") {
            out += "$origin/maps/"
            if (uri.port == -1 && scheme == "http") out += "http://$host:8080/"
        }
        return out.toList()
    }

    /** A short form for showing: without "http://" and the last "/". */
    fun short(base: String): String = base.removePrefix("http://").trimEnd('/')
}
