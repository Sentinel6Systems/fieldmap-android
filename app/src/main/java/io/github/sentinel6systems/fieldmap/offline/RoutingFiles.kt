package io.github.sentinel6systems.fieldmap.offline

import io.github.sentinel6systems.fieldmap.server.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One routing file of the server: BRouter's data for a 5° square, its size and SHA-256. */
data class RoutingFile(val name: String, val west: Int, val south: Int, val east: Int, val north: Int, val size: Long, val sha256: String) {
    fun covers(box: Box): Boolean {
        // A box across the 180° line (west > east) is two boxes.
        val parts = if (box.west <= box.east) listOf(box.west to box.east) else listOf(box.west to 180.0, -180.0 to box.east)
        return box.south < north && box.north > south && parts.any { (w, e) -> w < east && e > west }
    }
}

/**
 * The routing files (BRouter's .rd5) on the device, for routes that follow roads and trails
 * (geo/Router.kt). The server gets them when its maps are built and lists them at
 * /routing/index.json; the device keeps the ones its offline areas touch, so routing works with
 * no connection. Made to match the areas each time the server is reached and an area is saved
 * or deleted: what is missing or changed is downloaded, what no area needs is removed.
 */
class RoutingFiles(filesDir: File) {
    val dir = File(filesDir, "routing")
    private val kept = File(dir, "index.json")
    /** The server's list as last read; null before the first. */
    var index: List<RoutingFile>? = read(kept)?.second
        private set
    /** What the last look at the server found. */
    enum class Check { NOT_YET, NO_ANSWER, NONE_ON_SERVER, NOT_COVERED, DONE, FAILED }
    var check = Check.NOT_YET
        private set

    /** The file being downloaded and how far (0-100). */
    var downloading: String? = null
        private set
    var percent = 0
        private set
    /** Called on the main thread when the files or a download's progress change. */
    var onChange: () -> Unit = {}

    /** The files on the device and what they take. */
    fun stored(): List<File> = dir.listFiles { f -> f.name.endsWith(".rd5") }.orEmpty().toList()
    fun storedBytes(): Long = stored().sumOf { it.length() }

    /** What a new area over `box` adds to download: the files it touches that are not on the device. */
    fun bytesFor(box: Box): Long = index.orEmpty().filter { it.covers(box) && !have(it) }.sumOf { it.size }

    private fun have(f: RoutingFile) = File(dir, f.name).length() == f.size && sums[f.name] == f.sha256

    /** The SHA-256 of each file on the device, as the server listed it when it came. */
    private val sums: MutableMap<String, String> = read(kept)?.first?.toMutableMap() ?: HashMap()

    /**
     * Brings the files in line with the areas, from the server at `home` (its address, as for the
     * maps). Returns true when the files changed (the routes are then worked out again), false when
     * nothing changed or the server has no routing files.
     */
    suspend fun sync(home: String, areas: List<Box>): Boolean = lock.withLock { withContext(Dispatchers.IO) { syncNow(home, areas) } }
    private val lock = Mutex()

    private fun syncNow(home: String, areas: List<Box>): Boolean {
        val list = when (val found = fetchIndex(home)) {
            null -> { check = Check.NO_ANSWER; tell(); return false }
            NONE -> { check = Check.NONE_ON_SERVER; tell(); return false }
            else -> found
        }
        index = list
        dir.mkdirs()
        val wanted = list.filter { f -> areas.any { f.covers(it) } }
        var changed = false
        // The server's routing data may be for another region than the maps of the areas.
        check = if (wanted.isEmpty() && areas.isNotEmpty()) Check.NOT_COVERED else Check.DONE
        for (f in wanted) {
            if (have(f)) continue
            if (!download(home, f)) { check = Check.FAILED; break }
            sums[f.name] = f.sha256
            changed = true
            save(list)
        }
        val names = wanted.map { it.name }.toSet()
        for (old in stored()) if (old.name !in names) { old.delete(); sums.remove(old.name); changed = true }
        save(list)
        tell()
        return changed
    }

    /** The server's list; NONE when it answers that it has none (404), null when it does not answer. */
    private fun fetchIndex(home: String): List<RoutingFile>? {
        var none = false
        for (base in bases(home)) {
            val text = try {
                Http.call(base + "index.json", TIMEOUT_MS).use { r -> if (r.code == 404) none = true; if (r.code == 200) r.body?.string() else null }
            } catch (e: Exception) { null } ?: continue
            return parse(text) ?: NONE
        }
        return if (none) NONE else null
    }

    private fun download(home: String, f: RoutingFile): Boolean {
        val part = File(dir, f.name + ".part")
        for (base in bases(home)) {
            try {
                Http.call(base + f.name, DOWNLOAD_TIMEOUT_MS).use { r ->
                    if (r.code != 200) return@use
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    downloading = f.name
                    percent = 0
                    tell()
                    r.body?.byteStream()?.use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                val now = if (f.size > 0) (done * 100 / f.size).toInt().coerceIn(0, 100) else 0
                                if (now != percent) { percent = now; tell() }
                            }
                        }
                    }
                    val sum = digest.digest().joinToString("") { "%02x".format(it) }
                    if (done == f.size && sum == f.sha256 && part.renameTo(File(dir, f.name))) return true.also { done() }
                }
            } catch (e: Exception) {}
            part.delete()
        }
        done()
        return false
    }

    private fun done() { downloading = null; percent = 0; tell() }

    private fun tell() = android.os.Handler(android.os.Looper.getMainLooper()).post { onChange() }

    private fun save(list: List<RoutingFile>) {
        val files = org.json.JSONArray()
        for (f in list) files.put(JSONObject().put("name", f.name).put("west", f.west).put("south", f.south).put("east", f.east)
            .put("north", f.north).put("size", f.size).put("sha256", f.sha256))
        val on = JSONObject()
        for ((name, sum) in sums) if (File(dir, name).isFile) on.put(name, sum)
        kept.writeText(JSONObject().put("version", 1).put("files", files).put("onDevice", on).toString())
    }

    companion object {
        private const val TIMEOUT_MS = 8000
        /** The server has no routing files (or not a list the app can read). */
        private val NONE = emptyList<RoutingFile>()
        private const val DOWNLOAD_TIMEOUT_MS = 600_000

        /** Where a server lists its routing files: at the origin, as live sharing (/mapshare/). */
        fun bases(home: String): List<String> = io.github.sentinel6systems.fieldmap.share.ShareClient.bases(home, "routing/")

        private val NAME = Regex("^[EW][0-9]{1,3}_[NS][0-9]{1,2}\\.rd5$")
        private val SHA = Regex("^[0-9a-f]{64}$")

        /** The server's /routing/index.json; files that do not make sense are left out. Null when it is not that file. */
        fun parse(text: String): List<RoutingFile>? = try {
            val files = JSONObject(text).getJSONArray("files")
            (0 until files.length()).mapNotNull { i ->
                val o = files.optJSONObject(i) ?: return@mapNotNull null
                val f = RoutingFile(o.optString("name"), o.optInt("west"), o.optInt("south"), o.optInt("east"), o.optInt("north"),
                    o.optLong("size"), o.optString("sha256").lowercase())
                f.takeIf { NAME.matches(it.name) && SHA.matches(it.sha256) && it.size > 0 && it.west < it.east && it.south < it.north }
            }
        } catch (e: Exception) { null }

        /** The kept copy: which files are on the device (name to SHA-256), and the server's list. */
        private fun read(file: File): Pair<Map<String, String>, List<RoutingFile>?>? = try {
            val o = JSONObject(file.readText())
            val on = o.optJSONObject("onDevice")
            (on?.keys()?.asSequence()?.associateWith { on.getString(it) } ?: emptyMap()) to parse(o.toString())
        } catch (e: Exception) { null }
    }
}
