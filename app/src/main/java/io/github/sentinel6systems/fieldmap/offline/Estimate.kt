package io.github.sentinel6systems.fieldmap.offline

import io.github.sentinel6systems.fieldmap.server.TileServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How large a download will be, before it starts. */
object Estimate {
    /** Tiles measured for each zoom level of each source. */
    private const val SAMPLES = 25

    /**
     * Measures tiles of every zoom level of every source (all of them where there are few,
     * else a grid of them over the area) and multiplies by the number of tiles. Town centres
     * and empty country differ a lot, so this is "about", not exact.
     */
    suspend fun bytes(sources: List<TileSource>, box: Box): Long = coroutineScope {
        val gate = Semaphore(6)
        sources.flatMap { source ->
            Tiles.ranges(source, box).map { range ->
                async {
                    val sizes = Tiles.samples(range, SAMPLES).map { (x, y) -> gate.withPermit { TileServer.size(Tiles.url(source, range.z, x, y)) } }
                    (sizes.average() * range.count).toLong()
                }
            }
        }.awaitAll().sum() + Areas.FIXED_BYTES
    }
}

/** The sources of each style, read from the server once. */
class StyleSources {
    private val known = HashMap<String, List<TileSource>>()

    suspend fun of(styleUrl: String): List<TileSource>? =
        known[styleUrl] ?: TileServer.info(styleUrl)?.sources?.also { known[styleUrl] = it }
}
