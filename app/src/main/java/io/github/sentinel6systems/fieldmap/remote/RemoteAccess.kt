package io.github.sentinel6systems.fieldmap.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sentinel6systems.fieldmap.server.Http
import io.github.sentinel6systems.fieldmap.server.RemoteEnd
import io.github.sentinel6systems.fieldmap.server.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

/** What the server gave this phone when it let it in. `host` is null while the server cannot be reached from outside. */
data class Enrolment(val home: String, val id: String, val certificate: String, val ca: String, val host: String?, val port: Int)

/** What the server says about remote access, asked on its own network. */
class RemoteInfo(val ready: Boolean, val pin: Boolean, val host: String?, val port: Int)

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
    /** The name in the certificate of every box's gateway. */
    private const val GATEWAY_NAME = "fieldmap-box"

    class Refused(message: String, val wantsCode: Boolean = false) : Exception(message)

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
                        val remote = o.optJSONObject("remote")
                        return@withContext RemoteInfo(o.optBoolean("ready"), o.optBoolean("pin"), remote?.optString("host")?.ifEmpty { null }, remote?.optInt("port") ?: 0)
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
                        val remote = o.optJSONObject("remote")
                        return@withContext Enrolment(base, o.optString("id"), o.getString("certificate"), o.getString("ca"), remote?.optString("host")?.ifEmpty { null }, remote?.optInt("port") ?: 0)
                    }
                    if (r.code != 404) last = Refused(o.optString("error").ifEmpty { "The server refused (${r.code})." }, o.optBoolean("pin"))
                }
            } catch (e: Exception) { /* try the next place */ }
        }
        throw last ?: Refused("The server did not answer. This is set up on the server's own network.")
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

    /** The gateway of an enrolment, or null when the server cannot be reached from outside (yet). */
    fun end(e: Enrolment): RemoteEnd? {
        val host = e.host ?: return null
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
                // The gateway is reached under whatever name the relay gives it; what counts is
                // that its certificate comes from the box's authority and is the gateway's.
                .hostnameVerifier { _, session ->
                    val peer = session.peerCertificates.firstOrNull() as? X509Certificate
                    peer?.subjectAlternativeNames?.any { it.size >= 2 && it[0] == 2 && it[1] == GATEWAY_NAME } == true
                }
                .build()
            RemoteEnd(host, e.port, client)
        } catch (e: Exception) {
            null
        }
    }
}
