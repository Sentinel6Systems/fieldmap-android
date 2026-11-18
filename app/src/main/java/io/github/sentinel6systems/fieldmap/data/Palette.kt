package io.github.sentinel6systems.fieldmap.data

/** The marker colours of the print tool (print/src/palette.js in fieldmap-server), same order. */
object Palette {
    val COLORS: List<Pair<String, String>> = listOf(
        "#d7263d" to "Red", "#f46036" to "Orange", "#e6b800" to "Yellow", "#2a9d3f" to "Green",
        "#1b998b" to "Teal", "#1f5fa8" to "Blue", "#6f2dbd" to "Purple", "#e83e8c" to "Pink",
        "#8b5a2b" to "Brown", "#111111" to "Black", "#6c757d" to "Grey", "#ffffff" to "White",
    )
    const val DEFAULT = "#d7263d"

    fun name(hex: String): String = COLORS.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.second ?: hex
}
