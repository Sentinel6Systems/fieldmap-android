package io.github.sentinel6systems.fieldmap.offline

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** A box on the map, in degrees. */
data class Box(val west: Double, val south: Double, val east: Double, val north: Double)

/** One tile set a style draws from, as its TileJSON describes it. */
data class TileSource(
    val id: String,
    val raster: Boolean,
    val tileSize: Int,
    val minZoom: Int,
    val maxZoom: Int,
    /** Tile address with {z}, {x} and {y}. */
    val template: String,
    /** Where the source has tiles; null for everywhere. */
    val bounds: Box? = null,
)

/** The tiles of one zoom level that touch a box: columns x0..x1, rows y0..y1. */
data class TileRange(val z: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val count: Long get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
}

/** Which tiles an offline area needs, counted the way MapLibre counts them when it downloads. */
object Tiles {
    /**
     * Every saved area goes from the whole world down to a zoom the person chooses, at most
     * MAX_ZOOM: the deepest vector zoom. The box's styles draw their closest layers from it.
     */
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 14
    /** The choices of the deepest zoom of an area. */
    val DEPTHS = 11..MAX_ZOOM

    fun x(lon: Double, z: Int): Int = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun y(lat: Double, z: Int): Int {
        val r = lat.coerceIn(-85.0511, 85.0511) * PI / 180
        return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun range(box: Box, z: Int) = TileRange(z, x(box.west, z), y(box.north, z), x(box.east, z), y(box.south, z))

    /**
     * The zoom levels of a source that an area of MIN_ZOOM..MAX_ZOOM takes. Picture tiles of
     * 256 px are drawn one level deeper than the map zoom, so they go one level further.
     */
    fun zooms(source: TileSource, min: Int = MIN_ZOOM, max: Int = MAX_ZOOM): IntRange {
        val shift = if (source.raster) (ln(512.0 / source.tileSize) / ln(2.0)).roundToInt() else 0
        return maxOf(min + shift, source.minZoom)..minOf(max + shift, source.maxZoom)
    }

    /** The ranges of a source in an area: none outside the part of the world that the source covers. */
    fun ranges(source: TileSource, box: Box, max: Int = MAX_ZOOM): List<TileRange> {
        val b = source.bounds
        val part = if (b == null) box else Box(maxOf(box.west, b.west), maxOf(box.south, b.south), minOf(box.east, b.east), minOf(box.north, b.north))
        if (part.west >= part.east || part.south >= part.north) return emptyList()
        return zooms(source, max = max).map { range(part, it) }
    }

    fun count(sources: List<TileSource>, box: Box, max: Int = MAX_ZOOM): Long = sources.sumOf { s -> ranges(s, box, max).sumOf { it.count } }

    /**
     * The tiles of a range to measure: all of them when there are no more than `most`, else
     * the middles of a grid of cells laid over the range, so that no part of it counts for more.
     */
    fun samples(r: TileRange, most: Int): List<Pair<Int, Int>> {
        val w = r.x1 - r.x0 + 1
        val h = r.y1 - r.y0 + 1
        if (r.count <= most) return (r.y0..r.y1).flatMap { y -> (r.x0..r.x1).map { x -> x to y } }
        val side = kotlin.math.sqrt(most.toDouble()).toInt().coerceAtLeast(1)
        // A long thin range gets its full number along the long side.
        var nx = minOf(side, w)
        var ny = minOf(side, h)
        if (ny < side) nx = minOf(most / ny, w) else if (nx < side) ny = minOf(most / nx, h)
        return (0 until ny).flatMap { j -> (0 until nx).map { i -> r.x0 + ((i + 0.5) * w / nx).toInt() to r.y0 + ((j + 0.5) * h / ny).toInt() } }
    }

    fun url(source: TileSource, z: Int, x: Int, y: Int): String =
        source.template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}

/** Sizes in words: "640 KB", "86 MB", "1.2 GB". */
fun bytesText(bytes: Long): String = when {
    bytes < 1_000_000 -> "${maxOf(1, bytes / 1000)} KB"
    bytes < 1_000_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
}
