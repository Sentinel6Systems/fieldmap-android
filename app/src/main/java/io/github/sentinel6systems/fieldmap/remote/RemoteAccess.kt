package io.github.sentinel6systems.fieldmap.remote

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sentinel6systems.fieldmap.server.Http
import io.github.sentinel6systems.fieldmap.server.RemoteEnd
import io.github.sentinel6systems.fieldmap.server.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** One address the box's gateway may be reached at from outside. `host` is a name, an IPv4 address or an IPv6 address in brackets. */
data class Endpoint(val host: String, val port: Int)

/** What the box gave this phone when it paired it, and where its gateway was last seen from home. */
data class Enrolment(
    val home: String,
    val id: String,
    val certificate: String,
    val ca: String,
    val gatewayName: String,
    val endpoints: List<Endpoint>,
    /** What the box said last about being reached from outside, in its own words. */
    val reach: String = "",
    /** The box's NetBird network, when its owner set one up: a second way to the box from outside. */
    val netbird: NetBirdBlock? = null,
)

/** What the box says about remote access, asked on its own network. */
class RemoteInfo(
    val ready: Boolean, val enabled: Boolean, val pin: Boolean, val pairingOpen: Boolean, val endpoints: List<Endpoint>, val reach: String, val reachStatus: String,
    /** Changes when the box's NetBird block changes; "" when the box has none; null from a box with older software. */
    val netbirdRev: String? = null,
    /** The port of the gateway on the box itself; 0 when the box did not say. */
    val gatewayPort: Int = 0,
)

/** Endpoints to and from the box's JSON: [{"host": ..., "port": ...}]. */
fun endpointsFrom(list: org.json.JSONArray?): List<Endpoint> =
    (0 until (list?.length() ?: 0)).mapNotNull { i ->
        list?.optJSONObject(i)?.let { o -> o.optString("host").takeIf { it.isNotBlank() }?.let { Endpoint(it, o.optInt("port")) } }
    }.filter { it.port in 1..65535 }

fun endpointsTo(list: List<Endpoint>): org.json.JSONArray =
    org.json.JSONArray().apply { list.forEach { put(JSONObject().put("host", it.host).put("port", it.port)) } }

/**
 * Remote access to a fieldmap-server box (its tools/remote-server.py). On the box's own
 * network the phone asks to be let in: it sends the public half of a key that it made in
 * the phone's key store, and gets a certificate for it from the box. The private half
 * cannot be read out of the key store, not even by this app. Away from that network, the
 * app connects to the box's gateway with that certificate, and trusts only the box's own
 * certificate authority there.
 */
object RemoteAccess {
    private const val KEY = "fieldmap-device"
    private const val STORE = "AndroidKeyStore"
    class Refused(message: String, val wantsCode: Boolean = false) : Exception(message)

    /** The name this device pairs under: the box's owner sees it on the Devices page of the box. */
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Where a server answers about remote access: beside the maps, on the web port. */
    private fun addresses(base: String, page: String): List<String> {
        val url = base.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${url.scheme}://${url.host}"
        // A base on the tile server's own port (8080) has the web front on the usual one.
        return listOf(if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) origin else "$origin:${url.port}", origin).distinct().map { "$it/mapremote/$page" }
    }

    /** Asked on the server's own network only. Null: the server has no remote access. */
    suspend fun info(base: String): RemoteInfo? = withContext(Dispatchers.IO) {
        for (address in addresses(base, "info")) {
            try {
                Http.call(address, 4000, Route.LAN).use { r ->
                    val o = if (r.code == 200) JSONObject(r.body?.string().orEmpty()) else null
                    if (o?.optString("service") == "fieldmap-remote") {
                        val reach = o.optJSONObject("reach")
                        return@withContext RemoteInfo(
                            o.optBoolean("ready"), o.optBoolean("enabled", true), o.optBoolean("pin"), o.optJSONObject("pairing")?.optBoolean("open") == true,
                            endpointsFrom(o.optJSONArray("endpoints")), reach?.optString("text").orEmpty(), reach?.optString("status").orEmpty(),
                            o.optJSONObject("netbird")?.let { if (it.optBoolean("on")) it.optString("rev") else "" },
                            o.optJSONObject("forwarding")?.optInt("port") ?: 0,
                        )
                    }
                }
            } catch (e: Exception) { /* try the next place */ }
        }
        null
    }

    /** Asks the server to let this phone in. Throws Refused with the server's own words. */
    suspend fun enrol(base: String, name: String, code: String?): Enrolment = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name).put("publicKey", publicKeyPem())
        if (!code.isNullOrBlank()) body.put("pin", code.trim())
        var last: Refused? = null
        for (address in addresses(base, "enrol")) {
            try {
                Http.call(address, 15000, Route.LAN) { post(body.toString().toRequestBody("application/json".toMediaType())) }.use { r ->
                    val o = try { JSONObject(r.body?.string().orEmpty()) } catch (e: Exception) { JSONObject() }
                    if (r.code == 200 && o.has("certificate")) {
                        return@withContext Enrolment(base, o.optString("id"), o.getString("certificate"), o.getString("ca"),
                            o.optString("gatewayName"), endpointsFrom(o.optJSONArray("endpoints")), netbird = netbirdFrom(o.optJSONObject("netbird")))
                    }
                    if (r.code != 404) last = Refused(o.optString("error").ifEmpty { "The box did not accept the request (${r.code})." }, o.optBoolean("pin"))
                }
            } catch (e: Exception) { /* try the next place */ }
        }
        throw last ?: Refused("The server did not respond. Pair the device on the server's network.")
    }

    /**
     * Asks the gateway, with this device's certificate, about the box's NetBird network.
     * `host` and `port` are any address that reaches the gateway: at home the box itself, from
     * outside a direct address or the NetBird port on this device.
     */
    suspend fun netbird(end: RemoteEnd, host: String, port: Int): Pair<BlockAnswer, NetBirdBlock?> = withContext(Dispatchers.IO) {
        try {
            val url = HttpUrl.Builder().scheme("https").host(host.trim('[', ']')).port(port).addPathSegments("mapremote/netbird").build()
            end.client.newCall(Request.Builder().url(url).build()).execute().use { r -> blockAnswer(r.code, r.body?.string()) }
        } catch (e: Exception) {
            blockAnswer(null, null)
        }
    }

    /** The phone's key, made the first time it is asked for. */
    private fun privateKey(): PrivateKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(KEY, null) as? PrivateKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec) }.generateKeyPair().private
    }

    private fun publicKeyPem(): String {
        privateKey()
        val public = KeyStore.getInstance(STORE).apply { load(null) }.getCertificate(KEY).publicKey
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(public.encoded, Base64.DEFAULT).trim() + "\n-----END PUBLIC KEY-----\n"
    }

    private fun certificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    /** The gateway of an enrolment, or null when the box gave no way to reach it from outside (yet). */
    fun end(e: Enrolment): RemoteEnd? {
        if (e.endpoints.isEmpty() && e.netbird == null) return null
        return try {
            val own = certificate(e.certificate)
            val key = privateKey()
            val keys = object : X509ExtendedKeyManager() {
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = KEY
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = KEY
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(KEY)
                override fun getCertificateChain(alias: String?) = arrayOf(own)
                override fun getPrivateKey(alias: String?) = key
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            }
            // Only the box's own authority is trusted for the gateway: no public one.
            val authority = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("box", certificate(e.ca)) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(authority) }.trustManagers.first { it is X509TrustManager } as X509TrustManager
            val context = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), null) }
            val client: OkHttpClient = Http.plain.newBuilder()
                .sslSocketFactory(context.socketFactory, trust)
                // The gateway is reached at an address, not a name; what counts is that its
                // certificate comes from the box's own authority and is the gateway's.
                .hostnameVerifier { _, session ->
                    val peer = session.peerCertificates.firstOrNull() as? X509Certificate
                    peer?.subjectAlternativeNames?.any { it.size >= 2 && it[0] == 2 && it[1] == e.gatewayName } == true
                }
                .build()
            RemoteEnd(e.endpoints, client)
        } catch (e: Exception) {
            null
        }
    }
}
