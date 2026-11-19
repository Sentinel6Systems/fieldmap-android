package io.github.sentinel6systems.fieldmap.server

import io.github.sentinel6systems.fieldmap.remote.Endpoint
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The way requests for the server travel: on its own network, or through the remote link. */
enum class Route { LAN, REMOTE }

/** Where the box's gateway may be from outside, the one that answered last, and the client that may talk to it. */
class RemoteEnd(val endpoints: List<Endpoint>, val client: OkHttpClient) {
    @Volatile
    var current = 0
        private set

    /** The port on this device that leads to the gateway through NetBird, while requests go that way. */
    @Volatile
    var netbird: Endpoint? = null
        private set

    /** Where requests for the gateway go now. Null: the box gave no address, and NetBird is not in use. */
    val endpoint: Endpoint? get() = netbird ?: endpoints.getOrNull(current.coerceIn(0, (endpoints.size - 1).coerceAtLeast(0)))

    /** From now on to the direct address number `i`. */
    fun direct(i: Int) { netbird = null; current = i }

    /** From now on through NetBird; null goes back to the direct address used before. */
    fun through(local: Endpoint?) { netbird = local }

    /** The direct addresses, the one that answered last first. */
    val order: List<Int> get() = if (endpoints.isEmpty()) emptyList() else listOf(current) + endpoints.indices.filter { it != current }
}

/**
 * Every request of the app, the map's included, goes through one client. The app always
 * asks for the maps under the address it knows the server by at home. Away from home, with
 * the remote link set up, the same requests are sent to the server's gateway instead, with
 * the phone's certificate; the answers are the same, so what is saved on the phone under
 * the home address stays valid whichever way a request went.
 */
object Http {
    /** Set on one request to send it a given way, whatever `route` says. Never leaves the app. */
    private const val FORCE = "X-Fieldmap-Route"

    /** The way requests go now. */
    @Volatile
    var route = Route.LAN

    /** The server's root address as the app knows it at home. */
    @Volatile
    var home: HttpUrl? = null

    /** The gateway, when this phone has been let in and the server can be reached from outside. */
    @Volatile
    var remote: RemoteEnd? = null

    /** Without the routing: for the gateway itself, and as the base of the others. */
    val plain: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /** The app's client; the map uses it too. */
    val client: OkHttpClient = plain.newBuilder().addInterceptor(::send).build()

    private val timed = ConcurrentHashMap<Int, OkHttpClient>()

    fun setHome(base: String?) {
        home = base?.toHttpUrlOrNull()
    }

    private fun send(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val forced = request.header(FORCE)
        if (forced != null) request = request.newBuilder().removeHeader(FORCE).build()
        val way = forced?.let(Route::valueOf) ?: route
        val home = home
        val remote = remote
        val url = request.url
        if (way != Route.REMOTE || home == null || remote == null || url.scheme != home.scheme || url.host != home.host || url.port != home.port) {
            return chain.proceed(request)
        }
        val to = remote.endpoint ?: throw IOException("The server has no address for remote connections.")
        // The same path at the gateway. The two headers tell it the home address, so that the
        // tile server writes that into its answers as it does at home.
        val authority = if (home.port == HttpUrl.defaultPort(home.scheme)) home.host else "${home.host}:${home.port}"
        val out = request.newBuilder()
            .url(url.newBuilder().scheme("https").host(to.host.trim('[', ']')).port(to.port).build())
            .header("X-Fieldmap-Host", authority)
            .header("X-Fieldmap-Proto", home.scheme)
            .build()
        val call = remote.client.newCall(out)
        if (chain.call().isCanceled()) call.cancel()
        return call.execute().newBuilder().request(request).build()
    }

    /** A request with its own time limit, optionally sent a given way. */
    fun call(url: String, timeoutMs: Int, way: Route? = null, build: Request.Builder.() -> Unit = {}): Response {
        val limited = timed.getOrPut(timeoutMs) {
            client.newBuilder().connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        }
        val request = Request.Builder().url(url).apply { if (way != null) header(FORCE, way.name) }.apply(build).build()
        return limited.newCall(request).execute()
    }
}
