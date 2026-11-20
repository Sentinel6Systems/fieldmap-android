package io.github.sentinel6systems.fieldmap.share

import io.github.sentinel6systems.fieldmap.server.Http
import io.github.sentinel6systems.fieldmap.server.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** How a request to the box's sharing ended: its status and text, or no status when nothing answered. */
class Answer(val code: Int?, val body: String = "")

/**
 * What the app's screen and its background service both go by: where the box answers about
 * sharing, at what pace, and when this phone's position last got through (ms on the phone's
 * running clock, 0 for not yet or not any more).
 */
object ShareLine {
    @Volatile var base: String? = null
    @Volatile var info = ShareInfo(true, 5, 120)
    @Volatile var sentAt = 0L
}

/**
 * The requests of live sharing. They go through the app's one client (server/Http.kt) under
 * the box's home address, and so reach the box at home and through its gateway alike.
 */
object ShareClient {
    private val JSON = "application/json".toMediaType()
    private const val TIMEOUT_MS = 8000

    /**
     * Where a box answers about sharing: beside the maps on the web port, as its remote access
     * does (/mapremote/). A base on the tile server's own port has the web front on the usual one.
     * The first is under the home address itself, the only one that also works from outside.
     * `path`: another service at the origin (the routing files: "routing/").
     */
    fun bases(home: String, path: String = "mapshare/"): List<String> {
        val url = home.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${url.scheme}://${url.host}"
        return listOf(if (url.port == HttpUrl.defaultPort(url.scheme)) origin else "$origin:${url.port}", origin).distinct().map { "$it/$path" }
    }

    suspend fun get(url: String): Answer = ask(url) {}
    /** `way`: sent that way whatever is in use, to find out whether it answers there. */
    suspend fun post(url: String, json: String, way: Route? = null): Answer = ask(url, way) { post(json.toRequestBody(JSON)) }
    suspend fun put(url: String, json: String): Answer = ask(url) { put(json.toRequestBody(JSON)) }
    suspend fun delete(url: String): Answer = ask(url) { delete() }

    private suspend fun ask(url: String, way: Route? = null, build: Request.Builder.() -> Unit): Answer = withContext(Dispatchers.IO) { now(url, way, build) }

    private fun now(url: String, way: Route?, build: Request.Builder.() -> Unit): Answer =
        try { Http.call(url, TIMEOUT_MS, way, build).use { r -> Answer(r.code, r.body?.string().orEmpty()) } } catch (e: Exception) { Answer(null) }

    /** Sent and waited for on the calling thread: for the last word before the app is gone. */
    fun postNow(url: String, json: String): Answer = now(url, null) { post(json.toRequestBody(JSON)) }

    /** Sent without waiting for the answer: on the way out there is nobody left to hear it. */
    fun send(url: String, json: String) {
        val request = try { Request.Builder().url(url).post(json.toRequestBody(JSON)).build() } catch (e: IllegalArgumentException) { return }
        Http.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}
