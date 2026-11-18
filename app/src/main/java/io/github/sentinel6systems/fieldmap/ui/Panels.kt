package io.github.sentinel6systems.fieldmap.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import io.github.sentinel6systems.fieldmap.BuildConfig
import io.github.sentinel6systems.fieldmap.R
import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Palette
import io.github.sentinel6systems.fieldmap.geo.Utm
import io.github.sentinel6systems.fieldmap.offline.Areas
import io.github.sentinel6systems.fieldmap.remote.RemoteAccess
import io.github.sentinel6systems.fieldmap.remote.RemoteInfo
import io.github.sentinel6systems.fieldmap.offline.Estimate
import io.github.sentinel6systems.fieldmap.offline.SavedArea
import io.github.sentinel6systems.fieldmap.offline.StyleSources
import io.github.sentinel6systems.fieldmap.offline.Tiles
import io.github.sentinel6systems.fieldmap.offline.bytesText
import io.github.sentinel6systems.fieldmap.server.Finder
import io.github.sentinel6systems.fieldmap.server.Found
import io.github.sentinel6systems.fieldmap.server.ServerAddress
import io.github.sentinel6systems.fieldmap.server.TileServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** What the panel shows. `parent` is where its Back arrow leads. */
enum class Screen(val title: String, val parent: Screen?) {
    NONE("", null),
    MENU("Menu", null),
    STYLES("Map style", MENU),
    AREAS("Saved areas", MENU),
    SAVE("Save a new area", AREAS),
    MARKERS("Markers", MENU),
    MARKER("Marker", null),
    SERVER("Server", MENU),
}

/**
 * The panel over the map: one at a time, opened from the Menu button. Each screen is a short
 * list of large rows and buttons, built here from the pieces in Ui.kt.
 */
class Panels(private val a: MainActivity) {
    var screen = Screen.NONE
        private set
    private val body get() = a.b.panelBody
    private var markerFromList = false

    // The Save screen's moving parts.
    private var estimateJob: Job? = null
    private var estimateText: TextView? = null
    private var downloadButton: View? = null
    private var areaName: EditText? = null
    private val sources = StyleSources()

    // The Server screen's moving parts.
    private var serverText: TextView? = null
    private var serverFound: LinearLayout? = null
    private var serverRemote: LinearLayout? = null
    private var serverBusy = false

    fun back() = show(if (screen == Screen.MARKER && markerFromList) Screen.MARKERS else screen.parent ?: Screen.NONE)

    fun showMarker(fromList: Boolean) {
        markerFromList = fromList
        show(Screen.MARKER)
    }

    fun show(next: Screen) {
        hideKeyboard()
        if (screen == Screen.SAVE) estimateJob?.cancel()
        if (screen == Screen.MARKER && next != Screen.MARKER) a.select(null)
        screen = next
        val open = next != Screen.NONE
        a.b.panel.visibility = if (open) View.VISIBLE else View.GONE
        // With a panel open only the zoom buttons stay on the map.
        for (v in listOf(a.b.position, a.b.menu, a.b.foot)) v.visibility = if (open) View.GONE else View.VISIBLE
        a.b.frame.visibility = if (next == Screen.SAVE) View.VISIBLE else View.GONE
        a.b.panelTitle.text = next.title
        val hasBack = next.parent != null || (next == Screen.MARKER && markerFromList)
        a.b.panelBack.visibility = if (hasBack) View.VISIBLE else View.GONE
        a.b.panelTitle.setPadding(if (hasBack) 0 else a.dp(16), 0, 0, 0)
        a.b.panelScroll.scrollTo(0, 0)
        build()
        a.showCrosshair()
        a.layoutMap()
    }

    /** Something outside changed (a download moved on, the server came or went): draw the lists again. */
    fun refresh() {
        when (screen) {
            Screen.MENU, Screen.STYLES, Screen.AREAS, Screen.MARKERS -> build()
            Screen.SAVE -> estimate()
            Screen.SERVER -> if (!serverBusy) { serverText?.text = serverState(); if (a.remoteNow || !a.reachable) remoteSection(null) }
            else -> {}
        }
    }

    /** The map came to rest: on the Save screen the frame now holds another area. */
    fun cameraIdle() { if (screen == Screen.SAVE) estimate() }

    private fun build() {
        body.removeAllViews()
        when (screen) {
            Screen.MENU -> menu()
            Screen.STYLES -> styles()
            Screen.AREAS -> areas()
            Screen.SAVE -> save()
            Screen.MARKERS -> markers()
            Screen.MARKER -> marker()
            Screen.SERVER -> server()
            Screen.NONE -> {}
        }
    }

    private fun add(v: View) = body.addView(v)
    private fun number(n: Long) = NumberFormat.getIntegerInstance().format(n)

    /* ---------- menu ---------- */

    private fun menu() {
        val saved = a.areas.list
        add(a.row("Map style", a.shownStyleName() ?: "None") { show(Screen.STYLES) })
        add(a.row("Saved areas", if (saved.isEmpty()) "None yet" else "${saved.size} on this phone, ${bytesText(a.areas.storedBytes())} used") { show(Screen.AREAS) })
        add(a.row("Markers", if (a.markers.markers.isEmpty()) "None yet" else "${a.markers.markers.size}") { show(Screen.MARKERS) })
        add(a.row("Server", a.prefs.server?.let { ServerAddress.short(it) + if (a.reachable) " · connected" else " · out of reach" } ?: "Not set") { show(Screen.SERVER) })
        add(a.mono("Fieldmap ${BuildConfig.VERSION_NAME}", 12f).apply { layoutParams = lp(top = a.dp(14)) })
    }

    /* ---------- map style ---------- */

    private fun styles() {
        val all = a.prefs.styles
        if (all.isEmpty()) {
            add(a.body("Connect to a server first: it has the map styles.", muted = true, top = 12))
            add(a.button("Server") { show(Screen.SERVER) }.apply { layoutParams = lp(top = a.dp(12)) })
            return
        }
        val current = a.currentStyle()
        for (style in all) {
            val saved = a.areas.list.count { it.styleUrl == style.url }
            val detail = if (saved > 0) "$saved saved area${if (saved == 1) "" else "s"}" else if (!a.reachable) "Not saved on this phone" else null
            add(a.row(style.name, detail, icon = if (style.id == current?.id) R.drawable.ic_check else null) { a.chooseStyle(style) }
                .apply { if (style.id != current?.id && saved == 0 && !a.reachable) alpha = 0.55f })
        }
    }

    /* ---------- saved areas ---------- */

    private fun areas() {
        val canSave = a.reachable && a.currentStyle() != null
        add(a.button("Save a new area", Kind.PRIMARY) { a.startFraming() }.apply { layoutParams = lp(top = a.dp(12)); enable(canSave) })
        if (!canSave) add(a.body("Saving needs the server: join its network first.", muted = true, top = 8))
        if (a.areas.list.isEmpty()) {
            add(a.body("No saved areas yet. A saved area has everything the map needs, so it works with no network at all.", muted = true, top = 14))
            return
        }
        add(a.label("On this phone"))
        for (area in a.areas.list) add(areaCard(area))
        add(a.label("Refresh"))
        add(a.body("For after the maps on the server were rebuilt: downloads every saved area again.", muted = true))
        add(a.button("Update all saved areas") { confirmUpdate() }.apply { layoutParams = lp(top = a.dp(10)); enable(a.reachable && a.areas.downloading == null) })
    }

    private fun areaCard(area: SavedArea): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp()
        val detail = when {
            area.downloading -> "Downloading ${area.percent}% · ${bytesText(area.bytes)}" + if (area.waiting) " · waiting for the server" else ""
            !area.complete -> "Not finished · ${bytesText(area.bytes)}"
            else -> "${area.styleName} · ${bytesText(area.bytes)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(area.created))}"
        }
        addView(a.row(area.name, detail) { a.showArea(area) })
        if (area.downloading) addView(LinearLayout(a).apply {
            setBackgroundColor(a.color(R.color.line))
            addView(View(a).apply { setBackgroundColor(a.color(R.color.sig)) }, LinearLayout.LayoutParams(0, a.dp(6), area.percent.toFloat()))
            addView(View(a), LinearLayout.LayoutParams(0, a.dp(6), 100f - area.percent))
        }, lp())
        addView(a.button("Delete", Kind.DANGER) { confirmDelete(area) }.apply { layoutParams = lp(top = a.dp(8)).apply { bottomMargin = a.dp(14) } })
    }

    private fun confirmUpdate() {
        AlertDialog.Builder(a)
            .setTitle("Update all saved areas?")
            .setMessage("Every saved area is downloaded again from the server. Stay on its network, with the app open, until it has finished.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Update") { _, _ -> a.areas.updateAll() }
            .show()
    }

    private fun confirmDelete(area: SavedArea) {
        AlertDialog.Builder(a)
            .setTitle("Delete \"${area.name}\"?")
            .setMessage("The maps of this area are removed from the phone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ -> a.areas.delete(area) }
            .show()
    }

    /* ---------- save a new area ---------- */

    private fun save() {
        add(a.body("Move and zoom the map until the area you want is inside the frame.", top = 12))
        add(a.label("Name"))
        val used = a.areas.list.map { it.name }.toSet()
        val name = generateSequence(a.areas.list.size + 1) { it + 1 }.map { "Area $it" }.first { it !in used }
        areaName = a.field("Name", name, max = Drawings.MAX_LABEL) { hideKeyboard() }.also(::add)
        estimateText = a.mono("", 15f, muted = false).apply { layoutParams = lp(top = a.dp(14)); minLines = 2 }.also(::add)
        val download = a.button("Download", Kind.PRIMARY) { download() }
        downloadButton = download
        add(a.buttons(a.button("Cancel") { show(Screen.AREAS) }, download, top = 14))
        download.enable(false)
        estimate()
    }

    /** Counts the tiles in the frame, then measures a sample of them on the server for the size. */
    private fun estimate() {
        estimateJob?.cancel()
        val text = estimateText ?: return
        val button = downloadButton ?: return
        fun say(message: String, colour: Int = R.color.ink, ok: Boolean = false) {
            text.text = message
            text.setTextColor(a.color(colour))
            button.enable(ok)
        }
        val style = a.currentStyle()
        if (style == null || !a.reachable) return say("The server is out of reach. Join its network to save an area.", R.color.warn)
        estimateJob = a.scope.launch {
            delay(350)
            val list = sources.of(style.url) ?: return@launch say("The server did not answer.", R.color.warn)
            val box = a.frameBox() ?: return@launch say("This area is too wide. Zoom in.", R.color.warn)
            val tiles = Tiles.count(list, box)
            if (tiles > Areas.MAX_TILES) return@launch say("Too large to save in one piece (${number(tiles)} map tiles). Zoom in and save it as several areas.", R.color.warn)
            say("${number(tiles)} map tiles. Working out the size…")
            val bytes = Estimate.bytes(list, box)
            val free = a.filesDir.usableSpace
            val line = "About ${bytesText(bytes)} · ${number(tiles)} map tiles\n${bytesText(free)} free on this phone"
            when {
                bytes > free - Areas.KEEP_FREE_BYTES -> say("$line\nNot enough room. Choose a smaller area.", R.color.warn)
                bytes > Areas.LARGE_BYTES -> say("$line\nThis is a large download.", R.color.amber, ok = true)
                else -> say(line, ok = true)
            }
        }
    }

    private fun download() {
        val style = a.currentStyle() ?: return
        val box = a.frameBox() ?: return
        val name = areaName?.text?.toString()?.trim().orEmpty().ifEmpty { "Area" }
        a.saveArea(name, style, box)
    }

    /* ---------- markers ---------- */

    private fun markers() {
        val list = a.markers.markers
        if (list.isEmpty()) add(a.body("No markers yet. To mark a spot, press and hold it on the map for two seconds. Hold the blue dot to mark where you are.", muted = true, top = 12))
        for (m in list.asReversed()) {
            val where = Utm.toMgrs(m.lat, m.lon, 4) ?: String.format(Locale.US, "%.5f, %.5f", m.lat, m.lon)
            add(a.row(m.label.ifEmpty { "(no name)" }, where, dot = Color.parseColor(m.color)) { a.openMarker(m.id, fromList = true) })
        }
        add(a.label("Markers as a file"))
        add(a.body("The file opens in the print tool of the server (Load drawings) and in other map software.", muted = true))
        add(a.buttons(
            a.button("Save to a file") { a.saveMarkersFile() }.apply { enable(list.isNotEmpty()) },
            a.button("Load from a file") { a.loadMarkersFile() },
        ))
    }

    private fun marker() {
        val m = a.selected?.let { a.markers.get(it) } ?: return
        add(a.label("Name", top = 10))
        val name = a.field("Name", m.label, max = Drawings.MAX_LABEL) { hideKeyboard() }
        name.doAfterTextChanged { text -> a.markers.get(m.id)?.let { a.updateMarker(it.copy(label = text?.toString().orEmpty().trim())) } }
        add(name)

        add(a.label("Colour"))
        val swatches = ArrayList<View>()
        fun mark(chosen: String) = swatches.forEachIndexed { i, v -> v.isSelected = Palette.COLORS[i].first == chosen }
        for (half in Palette.COLORS.chunked(6)) add(LinearLayout(a).apply {
            layoutParams = lp(top = if (swatches.isEmpty()) 0 else a.dp(8))
            for ((hex, colourName) in half) addView(FrameLayout(a).apply {
                setBackgroundResource(R.drawable.bg_control)
                contentDescription = colourName
                isClickable = true
                addView(DotView(a, Color.parseColor(hex)), FrameLayout.LayoutParams(a.dp(30), a.dp(30), Gravity.CENTER))
                setOnClickListener { a.markers.get(m.id)?.let { a.updateMarker(it.copy(color = hex)) }; mark(hex) }
                swatches += this
            }, LinearLayout.LayoutParams(0, a.dp(52), 1f).apply { if (childCount > 0) marginStart = a.dp(6) })
        })
        mark(m.color)

        add(a.label("Position"))
        val grid = a.mono("", 18f, muted = false)
        val latlon = a.mono("")
        fun place() {
            val now = a.markers.get(m.id) ?: return
            grid.text = Utm.toMgrs(now.lat, now.lon) ?: "Outside the MGRS grid"
            latlon.text = String.format(Locale.US, "%.5f, %.5f", now.lat, now.lon) + (a.fromHere(now.lat, now.lon)?.let { "\n$it" } ?: "")
        }
        place()
        add(grid)
        add(latlon)
        add(a.buttons(
            a.button("Delete", Kind.DANGER) { a.deleteMarker(m.id); back() },
            a.button("Move to crosshair") {
                val c = a.map?.cameraPosition?.target ?: return@button
                a.markers.get(m.id)?.let { a.updateMarker(it.copy(lat = c.latitude, lon = c.longitude)) }
                place()
            }.apply { enable(a.follow == Follow.FREE) },
            top = 16,
        ))
    }

    /* ---------- server ---------- */

    private fun serverState(): String = when {
        a.prefs.server == null -> "Fieldmap shows the maps of a fieldmap-server box. On its network, connect once and save the areas you need. They then work anywhere, with no network."
        a.remoteNow -> "Connected through the remote link. ${a.prefs.styles.size} map style${if (a.prefs.styles.size == 1) "" else "s"}."
        a.reachable -> "Connected. ${a.prefs.styles.size} map style${if (a.prefs.styles.size == 1) "" else "s"}."
        else -> "Out of reach now. Saved areas still work."
    }

    private fun server() {
        serverBusy = false
        serverText = a.body(serverState(), muted = true, top = 12).also(::add)
        add(a.label("Address"))
        val address = a.field("For example 192.168.1.10", a.prefs.server?.let(ServerAddress::short).orEmpty(), address = true) {}
        val go = { connect(address.text.toString()) }
        address.setOnEditorActionListener { _, _, _ -> go(); true }
        add(address)
        add(a.buttons(a.button("Find on this network") { find() }, a.button("Connect", Kind.PRIMARY) { go() }))
        serverFound = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(top = a.dp(8)) }.also(::add)
        serverRemote = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp() }.also(::add)
        remoteSection(null)
        // On the server's own network, ask it whether it offers remote access.
        val base = a.prefs.server
        if (base != null && a.reachable && !a.remoteNow) a.scope.launch {
            val info = RemoteAccess.info(base)
            if (screen == Screen.SERVER && base == a.prefs.server) remoteSection(info)
        }
    }

    /**
     * The remote link: one button, offered on the server's own network when the server has
     * it; afterwards what state it is in. `info` is what the server said, null when it was
     * not asked (away from its network) or has no remote access.
     */
    private fun remoteSection(info: RemoteInfo?) {
        val box = serverRemote ?: return
        box.removeAllViews()
        val mine = a.enrolment()
        if (mine == null && info?.ready != true) return
        box.addView(a.label("Away from this network"))
        if (mine != null) {
            box.addView(a.body(when {
                a.remoteNow -> "This phone is using the remote link now."
                (info?.host ?: mine.host) == null -> "This phone has been let in, but the server cannot be reached from outside yet. Its owner switches that on, on the server."
                else -> "Remote access is set up. Away from this network the app reaches the server by itself."
            }, muted = true))
            box.addView(a.button("Remove from this phone", Kind.DANGER) { a.removeRemote(); remoteSection(info) }.apply { layoutParams = lp(top = a.dp(10)) })
            return
        }
        box.addView(a.body("Let this phone use the server's maps from anywhere. It can only be set up here, on the server's own network.", muted = true))
        val code = if (info?.pin == true) a.field("The server's code", "", max = 64) { hideKeyboard() }.apply { layoutParams = lp(top = a.dp(10)) }.also(box::addView) else null
        val said = a.body("", top = 8).apply { setTextColor(a.color(R.color.warn)); visibility = View.GONE }
        val button = a.button("Set up remote access") {}
        button.setOnClickListener {
            hideKeyboard()
            button.enable(false)
            a.setUpRemote(code?.text?.toString()) { wrong ->
                if (screen != Screen.SERVER) return@setUpRemote
                if (wrong == null) remoteSection(info) else { button.enable(true); said.text = wrong; said.visibility = View.VISIBLE }
            }
        }
        box.addView(button.apply { layoutParams = lp(top = a.dp(10)) })
        box.addView(said)
    }

    private fun busy(message: String) {
        serverBusy = true
        serverFound?.removeAllViews()
        serverText?.apply { text = message; setTextColor(a.color(R.color.muted)) }
    }

    private fun failed(message: String) {
        serverBusy = false
        serverText?.apply { text = message; setTextColor(a.color(R.color.warn)) }
    }

    private fun connect(typed: String) {
        if (serverBusy) return
        hideKeyboard()
        if (ServerAddress.candidates(typed).isEmpty()) return failed("Type the server's address: a name or a number such as 192.168.1.10.")
        busy("Asking the server…")
        a.scope.launch {
            val found = TileServer.connect(typed)
            if (screen != Screen.SERVER) return@launch
            if (found == null) failed("No tile server answered there. Check the address, and that this phone is on the same network.")
            else { serverBusy = false; a.useServer(found) }
        }
    }

    private fun find() {
        if (serverBusy) return
        hideKeyboard()
        busy("Searching this network…")
        a.scope.launch {
            val result = Finder.find()
            if (screen != Screen.SERVER) return@launch
            when {
                !result.searched -> failed("This phone is not on a Wi-Fi network. Join the server's network, or type its address.")
                result.found.isEmpty() -> failed("No tile server found on this network. Type its address instead.")
                result.found.size == 1 -> { serverBusy = false; a.useServer(result.found[0]) }
                else -> {
                    serverBusy = false
                    serverText?.text = "Found ${result.found.size}. Choose one."
                    for (found in result.found) serverFound?.addView(foundRow(found))
                }
            }
        }
    }

    private fun foundRow(found: Found): View =
        a.row(ServerAddress.short(found.base), "${found.styles.size} map style${if (found.styles.size == 1) "" else "s"}") { a.useServer(found) }

    private fun hideKeyboard() {
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(a.b.root.windowToken, 0)
        a.currentFocus?.clearFocus()
    }
}
