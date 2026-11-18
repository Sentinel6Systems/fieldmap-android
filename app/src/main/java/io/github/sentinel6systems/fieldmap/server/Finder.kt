package io.github.sentinel6systems.fieldmap.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Looks for tile servers on the local network the phone is on: asks each of the (up to 254)
 * neighbouring addresses on the web port and on TileServer GL's own port for a list of styles.
 * Only private networks are searched, and only over Wi-Fi or a cable.
 */
object Finder {
    /** found: the servers that answered. searched: false when the phone is not on a local network. */
    class Result(val found: List<Found>, val searched: Boolean)

    suspend fun find(): Result = withContext(Dispatchers.IO) {
        val (network, own) = Net.localNetwork() ?: return@withContext Result(emptyList(), false)
        val net = own.address
        val gate = Semaphore(64)
        val found = Net.onLocal(network) {
            coroutineScope {
                (1..254).map { last ->
                    async {
                        val host = "${net[0].toInt() and 255}.${net[1].toInt() and 255}.${net[2].toInt() and 255}.$last"
                        gate.withPermit { probe(host) }
                    }
                }.awaitAll().filterNotNull()
            }
        }
        Result(found, true)
    }

    /** The tile server on one host, if any: the web port first (it has every page behind it). */
    private suspend fun probe(host: String): Found? {
        if (open(host, 80)) {
            for (base in listOf("http://$host/", "http://$host/maps/")) TileServer.styles(base, 1500)?.let { return Found(base, it) }
        }
        if (open(host, 8080)) TileServer.styles("http://$host:8080/", 1500)?.let { return Found("http://$host:8080/", it) }
        return null
    }

    private fun open(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 350); true }
    } catch (e: Exception) { false }
}
