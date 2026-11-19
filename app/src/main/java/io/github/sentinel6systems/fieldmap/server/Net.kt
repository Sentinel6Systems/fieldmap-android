package io.github.sentinel6systems.fieldmap.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * Which network the app talks on. A map box often has its own Wi-Fi with no internet behind
 * it. A phone on such a Wi-Fi keeps sending its traffic over mobile data, where the box
 * cannot be found. So when the server does not answer the usual way and the phone is on a
 * local network it is not using, the app ties itself to that network, and lets go again when
 * the server stops answering there.
 */
object Net {
    private var manager: ConnectivityManager? = null
    private val local = ConcurrentHashMap<Network, LinkProperties>()
    /** The network the app is tied to; null is the phone's own choice. */
    @Volatile
    private var tied: Network? = null

    /** Starts watching the Wi-Fi and cable networks the phone is on. Call once. */
    fun start(context: Context) {
        if (manager != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        manager = cm
        val wanted = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        cm.registerNetworkCallback(wanted, object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { local[network] = properties }
            override fun onLost(network: Network) {
                local.remove(network)
                if (network == tied) tie(null)
            }
        })
    }

    /** A private local network the phone is on, with the phone's own address on it. */
    fun localNetwork(): Pair<Network, Inet4Address>? {
        for ((network, properties) in local) {
            val own = properties.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as? Inet4Address
            if (own != null) return network to own
        }
        return null
    }

    private fun tie(network: Network?) {
        tied = network
        // Every new connection of the app, MapLibre's included, goes out on this network.
        try { manager?.bindProcessToNetwork(network) } catch (e: Exception) { tied = null }
    }

    /**
     * Asks on the network in use and, if nothing answers, on the other one (the local network
     * the phone is on, or back to the phone's own choice). Stays where the answer came from.
     */
    suspend fun <T : Any> reach(ask: suspend () -> T?): T? {
        ask()?.let { return it }
        val other = if (tied == null) localNetwork()?.first ?: return null else null
        tie(other)
        ask()?.let { return it }
        tie(null)
        return null
    }

    /** True when the device has a connection that leads to the internet (mobile data, or a Wi-Fi with internet). */
    fun internet(): Boolean {
        val cm = manager ?: return true
        return try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) { true }
    }

    /** Back to the phone's own choice of network: the remote link goes over the internet. */
    fun untie() { if (tied != null) tie(null) }

    /** Runs a search on the local network itself; stays tied to it only if `search` found something. */
    suspend fun <T> onLocal(network: Network, search: suspend () -> List<T>): List<T> {
        val before = tied
        tie(network)
        val found = search()
        if (found.isEmpty()) tie(before)
        return found
    }
}
