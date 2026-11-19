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
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.Palette
import io.github.sentinel6systems.fieldmap.geo.CoordFormat
import io.github.sentinel6systems.fieldmap.geo.Coords
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
import io.github.sentinel6systems.fieldmap.share.Ageing
import io.github.sentinel6systems.fieldmap.share.Peer
import io.github.sentinel6systems.fieldmap.share.ShareJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** What the panel shows. `parent` is where its Back arrow leads. */
enum class Screen(val title: String, val parent: Screen?) {
    NONE("", null),
    MENU("Menu", null),
    STYLES("Map style", MENU),
    AREAS("Map areas", MENU),
    SAVE("Save a map area", AREAS),
    MARKERS("Markers", MENU),
    MARKER("Marker", null),
    MOVE("Move the marker", MARKER),
    SHARING("Sharing", MENU),
    PEER("Shared position", null),
    SERVER("Box", MENU),
    SETTINGS("Settings", MENU),
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
    private var markerShare: View? = null

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

    // The Sharing screen's moving parts, and those of another phone's panel.
    private var shareAbout: TextView? = null
    private var shareMine: TextView? = null
    private var sharePeers: LinearLayout? = null
    private var sharePeerRows = ""
    private var peerFromList = false
    private var peerShown: Peer? = null
    private var peerWhen: TextView? = null
    private var peerGrid: TextView? = null
    private var peerWhere: TextView? = null

    fun back() = show(when {
        screen == Screen.MARKER && markerFromList -> Screen.MARKERS
        screen == Screen.PEER && peerFromList -> Screen.SHARING
        else -> screen.parent ?: Screen.NONE
    })

    fun showMarker(fromList: Boolean) {
        markerFromList = fromList
        show(Screen.MARKER)
    }

    fun showPeer(fromList: Boolean) {
        peerFromList = fromList
        show(Screen.PEER)
    }

    /** Builds a screen again, if it is the one showing. */
    fun rebuild(which: Screen) { if (screen == which) { hideKeyboard(); build() } }

    fun show(next: Screen) {
        hideKeyboard()
        if (screen == Screen.SAVE) estimateJob?.cancel()
        // Move belongs to the marker's panel: the marker stays chosen between the two.
        if (screen == Screen.MARKER && next != Screen.MARKER && next != Screen.MOVE) a.select(null)
        if (screen == Screen.MOVE && next != Screen.MARKER) a.select(null)
        if (screen == Screen.PEER && next != Screen.PEER) a.selectPeer(null)
        screen = next
        val open = next != Screen.NONE
        a.b.panel.visibility = if (open) View.VISIBLE else View.GONE
        // With a panel open only the zoom buttons stay on the map.
        for (v in listOf(a.b.position, a.b.menu, a.b.foot)) v.visibility = if (open) View.GONE else View.VISIBLE
        a.b.frame.visibility = if (next == Screen.SAVE) View.VISIBLE else View.GONE
        a.b.panelTitle.text = next.title
        val hasBack = next.parent != null || (next == Screen.MARKER && markerFromList) || (next == Screen.PEER && peerFromList)
        a.b.panelBack.visibility = if (hasBack) View.VISIBLE else View.GONE
        a.b.panelTitle.setPadding(if (hasBack) 0 else a.dp(16), 0, 0, 0)
        a.b.panelScroll.scrollTo(0, 0)
        build()
        a.layoutMap()
    }

    /** Something outside changed (a download moved on, the server came or went): draw the lists again. */
    fun refresh() {
        when (screen) {
            Screen.MENU, Screen.STYLES, Screen.AREAS, Screen.MARKERS -> build()
            Screen.SAVE -> estimate()
            Screen.SHARING -> sharingFill()
            // Only the words: the name may be being typed.
            Screen.MARKER -> a.selected?.let { a.markers.get(it) }?.let { (markerShare?.tag as? TextView)?.text = shareDetail(it) }
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
            Screen.MOVE -> add(a.body("Tap the new place of the marker on the map. You can move and zoom the map first.", top = 12))
            Screen.SHARING -> sharing()
            Screen.PEER -> peer()
            Screen.SERVER -> server()
            Screen.SETTINGS -> settings()
            Screen.NONE -> {}
        }
    }

    private fun add(v: View) = body.addView(v)
    private fun number(n: Long) = NumberFormat.getIntegerInstance().format(n)

    /* ---------- menu ---------- */

    private fun menu() {
        val saved = a.areas.list
        add(a.row("Map style", a.shownStyleName() ?: "None") { show(Screen.STYLES) })
        add(a.row("Map areas", if (saved.isEmpty()) "None" else "${saved.size} on this device · ${bytesText(a.areas.storedBytes())}") { show(Screen.AREAS) })
        add(a.row("Markers", if (a.markers.markers.isEmpty()) "None" else "${a.markers.markers.size}") { show(Screen.MARKERS) })
        add(a.row("Sharing", a.sharing.summary()) { show(Screen.SHARING) })
        add(a.row("Box", a.prefs.server?.let { ServerAddress.short(it) + " · " + boxState() } ?: "Not set") { show(Screen.SERVER) })
        add(a.row("Settings", "Coordinates: ${a.prefs.coordFormat.title}") { show(Screen.SETTINGS) })
        // The one way to end a shared position for good, besides swiping the app away.
        add(a.button("Close Fieldmap") { a.quit() }.apply { layoutParams = lp(top = a.dp(16)) })
        if (a.sharing.on) add(a.body("Close Fieldmap also stops the sharing of your position. If you only leave the app, sharing continues.", muted = true, top = 8))
        add(a.mono("Fieldmap ${BuildConfig.VERSION_NAME}", 12f).apply { layoutParams = lp(top = a.dp(14)) })
    }

    /* ---------- map style ---------- */

    private fun styles() {
        val all = a.prefs.styles
        if (all.isEmpty()) {
            add(a.body("Connect to the box first. The box has the map styles.", muted = true, top = 12))
            add(a.button("Box") { show(Screen.SERVER) }.apply { layoutParams = lp(top = a.dp(12)) })
            return
        }
        val current = a.currentStyle()
        for (style in all) {
            val saved = a.areas.list.count { it.styleUrl == style.url }
            val detail = if (saved > 0) "$saved map area${if (saved == 1) "" else "s"}" else if (!a.reachable) "Not on this device" else null
            add(a.row(style.name, detail, icon = if (style.id == current?.id) R.drawable.ic_check else null) { a.chooseStyle(style) }
                .apply { if (style.id != current?.id && saved == 0 && !a.reachable) alpha = 0.55f })
        }
    }

    /* ---------- settings ---------- */

    /** The format of all positions in the app, each choice shown with your position (or the map's centre) in it. */
    private fun settings() {
        add(a.label("Coordinates"))
        val here = a.fix()?.let { it.lat to it.lon } ?: a.map?.cameraPosition?.target?.let { it.latitude to it.longitude }
        val chosen = a.prefs.coordFormat
        for (f in CoordFormat.entries) {
            add(a.row(f.title, here?.let { Coords.text(it.first, it.second, f) }, icon = if (f == chosen) R.drawable.ic_check else null) {
                a.prefs.coordFormat = f
                a.coordsChanged()
                build()
            })
        }
        add(a.body("The app shows all positions in this format. This includes the top of the screen, the markers and the shared positions.", muted = true, top = 12))
    }

    /* ---------- saved areas ---------- */

    private fun areas() {
        val canSave = a.reachable && a.currentStyle() != null
        add(a.button("Save a map area", Kind.PRIMARY) { a.startFraming() }.apply { layoutParams = lp(top = a.dp(12)); enable(canSave) })
        if (!canSave) add(a.body("To save a map area, connect to the box.", muted = true, top = 8))
        if (a.areas.list.isEmpty()) {
            add(a.body("This device has no map areas. A map area keeps a part of the map on this device. You can then use it without a connection to the box.", muted = true, top = 14))
            return
        }
        add(a.label("On this device"))
        for (area in a.areas.list) add(areaCard(area))
        add(a.label("Update"))
        add(a.body("When the box has new maps, update the map areas. The app then downloads all map areas again.", muted = true))
        add(a.button("Update all map areas") { confirmUpdate() }.apply { layoutParams = lp(top = a.dp(10)); enable(a.reachable && a.areas.downloading == null) })
    }

    private fun areaCard(area: SavedArea): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp()
        val detail = when {
            area.downloading -> "Download ${area.percent}% · ${bytesText(area.bytes)}" + if (area.waiting) " · waits for the box" else ""
            !area.complete -> "Not complete · ${bytesText(area.bytes)}"
            else -> "${area.styleName} · detail ${area.maxZoom} · ${bytesText(area.bytes)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(area.created))}"
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
            .setTitle("Update all map areas?")
            .setMessage("The app downloads all map areas again from the box. Keep the app open until the download is complete.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Update") { _, _ -> a.areas.updateAll() }
            .show()
    }

    private fun confirmDelete(area: SavedArea) {
        AlertDialog.Builder(a)
            .setTitle("Delete \"${area.name}\"?")
            .setMessage("The app removes the maps of this area from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> a.areas.delete(area) }
            .show()
    }

    /* ---------- save a new area ---------- */

    private fun save() {
        add(a.body("Move and zoom the map. Put the area that you want in the frame.", top = 12))
        add(a.label("Name"))
        val used = a.areas.list.map { it.name }.toSet()
        val name = generateSequence(a.areas.list.size + 1) { it + 1 }.map { "Area $it" }.first { it !in used }
        areaName = a.field("Name", name, max = Drawings.MAX_LABEL) { hideKeyboard() }.also(::add)
        add(a.label("Detail"))
        val depths = ArrayList<View>()
        add(LinearLayout(a).apply {
            for (z in Tiles.DEPTHS) addView(TextView(a).apply {
                text = z.toString()
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(a.color(R.color.ink))
                setBackgroundResource(R.drawable.bg_control)
                contentDescription = "Detail $z"
                isSelected = z == a.prefs.areaZoom
                setOnClickListener { hideKeyboard(); a.prefs.areaZoom = z; depths.forEach { it.isSelected = it === this }; estimate() }
                depths += this
            }, LinearLayout.LayoutParams(0, a.dp(52), 1f).apply { if (childCount > 0) marginStart = a.dp(6) })
        })
        add(a.body("14 has all the details of the map. A lower number gives a smaller download with less detail.", muted = true, top = 8))
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
        if (style == null || !a.reachable) return say("There is no connection to the box. You cannot save a map area now.", R.color.warn)
        estimateJob = a.scope.launch {
            delay(350)
            val list = sources.of(style.url) ?: return@launch say("The box did not answer.", R.color.warn)
            val box = a.frameBox() ?: return@launch say("This area is too large. Zoom in.", R.color.warn)
            val depth = a.prefs.areaZoom
            val tiles = Tiles.count(list, box, depth)
            if (tiles > Areas.MAX_TILES) return@launch say("This area is too large. It has ${number(tiles)} tiles. Choose less detail, or zoom in to make the area smaller.", R.color.warn)
            say("${number(tiles)} tiles. The app finds the size…")
            val bytes = Estimate.bytes(list, box, depth)
            val free = a.filesDir.usableSpace
            val line = "Size: about ${bytesText(bytes)} · ${number(tiles)} tiles\nFree space on this device: ${bytesText(free)}"
            when {
                bytes > free - Areas.KEEP_FREE_BYTES -> say("$line\nThere is not enough free space. Choose a smaller area.", R.color.warn)
                bytes > Areas.LARGE_BYTES -> say("$line\nThis is a large download. Use Wi-Fi.", R.color.amber, ok = true)
                else -> say(line, ok = true)
            }
        }
    }

    private fun download() {
        val style = a.currentStyle() ?: return
        val box = a.frameBox() ?: return
        val name = areaName?.text?.toString()?.trim().orEmpty().ifEmpty { "Area" }
        a.saveArea(name, style, box, a.prefs.areaZoom)
    }

    /* ---------- markers ---------- */

    private fun markers() {
        val list = a.markers.markers
        if (list.isEmpty()) add(a.body("There are no markers. To add a marker, touch and hold the map for 2 seconds. To add a marker at your position, touch and hold the blue dot.", muted = true, top = 12))
        for (m in list.asReversed()) {
            val where = a.coords(m.lat, m.lon, brief = true)
            add(a.row(m.label.ifEmpty { "(no name)" }, where + if (m.shared) " · shared" else "", dot = Color.parseColor(m.color)) { a.openMarker(m.id, fromList = true) })
        }
        add(a.label("File"))
        add(a.body("Save the markers to a file to use them in other map apps. On the Print a map page of the box, open the file with Load drawings.", muted = true))
        add(a.buttons(
            a.button("Save to a file") { a.saveMarkersFile() }.apply { enable(list.isNotEmpty()) },
            a.button("Add from a file") { a.loadMarkersFile() },
        ))
    }

    private fun marker() {
        markerShare = null
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
        val far = a.mono("")
        fun place() {
            val now = a.markers.get(m.id) ?: return
            grid.text = a.coords(now.lat, now.lon)
            far.text = a.fromHere(now.lat, now.lon).orEmpty()
            far.visibility = if (far.text.isEmpty()) View.GONE else View.VISIBLE
        }
        place()
        add(grid)
        add(far)

        // One choice, off until the person makes it. A server known to have no sharing does not offer it.
        if (m.shared || a.sharing.box != Sharing.Box.NONE) {
            markerShare = a.check("Share with the other devices", shareDetail(m), m.shared) { on -> hideKeyboard(); a.shareMarker(m.id, on); build() }
                .apply { layoutParams = lp(top = a.dp(10)) }.also(::add)
        }
        add(a.buttons(
            a.button("Delete", Kind.DANGER) { if (a.markers.get(m.id)?.shared == true) confirmDeleteShared(m.id) else { a.deleteMarker(m.id); back() } },
            a.button("Move") { show(Screen.MOVE) },
            top = 16,
        ))
    }

    private fun shareDetail(m: Marker): String = when {
        !m.shared -> "Off. Only this device has this marker."
        a.sharing.waiting(m.id) -> "The app sends it to the box when there is a connection."
        else -> "All devices on this box show this marker. They can change or delete it. Untick to keep the marker on this device only."
    }

    private fun confirmDeleteShared(id: String) {
        AlertDialog.Builder(a)
            .setTitle("Delete this shared marker?")
            .setMessage("The app deletes it from all devices on this box.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> a.deleteMarker(id); if (screen == Screen.MARKER) back() }
            .show()
    }

    /* ---------- sharing ---------- */

    private fun sharing() {
        val s = a.sharing
        shareAbout = a.body(s.about(), muted = true, top = 12).also(::add)
        add(a.label("My position"))
        val name = a.field("Your name on the map", a.prefs.shareName, max = ShareJson.MAX_NAME) { hideKeyboard() }
        if (s.on) {
            shareMine = a.body(s.mine()).also(::add)
            add(a.body("Sharing continues when you leave the app or when the screen goes off. To stop it, press Stop sharing my position. When you do not move, the app sends your position less often. This saves the battery.", muted = true, top = 8))
            add(a.label("Name"))
            name.doAfterTextChanged { text -> ShareJson.name(text?.toString().orEmpty()).ifEmpty { null }?.let { a.prefs.shareName = it } }
            add(name)
            add(a.button("Stop sharing my position", Kind.DANGER) { a.sharing.switchOff(); rebuild(Screen.SHARING) }.apply { layoutParams = lp(top = a.dp(12)) })
        } else {
            shareMine = null
            add(a.body("Off. When you share your position, the other devices on this box see it with this name. Sharing continues when you leave the app. It stops when you close Fieldmap."))
            add(a.label("Name"))
            add(name)
            val go = a.button("Share my position", Kind.PRIMARY) { a.startSharing(name.text.toString()) }
            fun ready() = go.enable(a.prefs.server != null && s.box != Sharing.Box.NONE && ShareJson.name(name.text.toString()).isNotEmpty())
            name.doAfterTextChanged { ready() }
            ready()
            add(go.apply { layoutParams = lp(top = a.dp(12)) })
        }
        add(a.label("Other devices"))
        sharePeers = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp() }.also(::add)
        sharePeerRows = "?"
        sharingFill()
    }

    private fun peerDetail(p: Peer): String = listOfNotNull(Ageing.text(p.age), a.fromHere(p.lat, p.lon, brief = true)).joinToString(" · ")

    /** The words of the Sharing screen as they stand now; the name being typed is left alone. */
    private fun sharingFill() {
        val s = a.sharing
        shareAbout?.text = s.about()
        shareMine?.text = s.mine()
        val list = sharePeers ?: return
        val others = s.others()
        val rows = others.joinToString { it.uid + it.name }
        if (rows != sharePeerRows) {
            // Rows are made again only when someone came, went or changed name: a row that is
            // being pressed must not be taken away under the finger.
            sharePeerRows = rows
            list.removeAllViews()
            if (others.isEmpty()) list.addView(a.body(if (s.box == Sharing.Box.ON && a.reachable) "No other device shares a position now." else "There are no positions to show.", muted = true))
            for (p in others) list.addView(a.row(p.name, peerDetail(p), icon = R.drawable.ic_next) { a.openPeer(p.uid, fromList = true) })
        } else {
            others.forEachIndexed { i, p -> (list.getChildAt(i)?.tag as? TextView)?.text = peerDetail(p) }
        }
    }

    /** Another phone, tapped on the map or chosen in the list: who, how long ago, where, how far. */
    private fun peer() {
        val uid = a.selectedPeer ?: return
        peerShown = a.sharing.other(uid)
        add(a.body(peerShown?.name.orEmpty(), top = 12).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        peerWhen = a.body("", muted = true, top = 4).also(::add)
        add(a.label("Position"))
        peerGrid = a.mono("", 18f, muted = false).also(::add)
        peerWhere = a.mono("").also(::add)
        peerFill()
    }

    private fun peerFill() {
        val uid = a.selectedPeer ?: return
        val info = a.sharing.info
        val now = a.sharing.other(uid)
        if (now != null) peerShown = now
        val p = peerShown ?: return
        peerWhen?.text = when {
            now == null -> "${p.name} does not share a position now. This is the last position that the app got."
            Ageing.look(p.age, info.interval, info.stale, p.still) == Ageing.Look.FRESH -> "Updated ${Ageing.text(p.age)}." + if (p.still && p.age >= 30) " ${p.name} does not move." else ""
            else -> "Last update ${Ageing.text(p.age)}. ${p.name} can be at a different place now."
        }
        peerGrid?.text = a.coords(p.lat, p.lon)
        peerWhere?.text = a.fromHere(p.lat, p.lon).orEmpty()
        peerWhere?.visibility = if (peerWhere?.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Once a second: the ages in the open panel of another phone, and in the list. */
    fun peerTick() {
        if (screen == Screen.PEER) peerFill()
        if (screen == Screen.SHARING) sharingFill()
    }

    /* ---------- server ---------- */

    /** The state of the connection in a few words, for the Box row of the menu. */
    private fun boxState(): String = when {
        a.remoteNow -> "connected through the internet"
        a.reachable -> "connected"
        else -> "offline"
    }

    private fun serverState(): String = when {
        a.prefs.server == null -> "Fieldmap shows the maps of a Fieldmap box.\n\n" +
            "1. Connect this device to the Wi-Fi of the box.\n" +
            "2. Press Find the box. Or type the address of the box and press Connect.\n\n" +
            "Then save map areas. You can use them without a connection to the box."
        a.remoteNow -> "Connected to the box through the internet. ${a.prefs.styles.size} map style${if (a.prefs.styles.size == 1) "" else "s"}."
        a.reachable -> "Connected to the box. ${a.prefs.styles.size} map style${if (a.prefs.styles.size == 1) "" else "s"}."
        else -> "Offline. There is no connection to the box. You can use the map areas on this device."
    }

    private fun server() {
        serverBusy = false
        serverText = a.body(serverState(), muted = true, top = 12).also(::add)
        add(a.label("Address"))
        val address = a.field("For example 192.168.1.10", a.prefs.server?.let(ServerAddress::short).orEmpty(), address = true) {}
        val go = { connect(address.text.toString()) }
        address.setOnEditorActionListener { _, _, _ -> go(); true }
        add(address)
        add(a.buttons(a.button("Find the box") { find() }, a.button("Connect", Kind.PRIMARY) { go() }))
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
        box.addView(a.label("Remote access"))
        if (info?.enabled == false || (info == null && a.remoteSwitchedOff)) {
            box.addView(a.body("Remote access is off on the box. To switch it on, open the Admin page of the box in a browser. " +
                (if (mine != null) "This device stays paired." else "You can then pair this device."), muted = true))
            return
        }
        if (mine != null) {
            // The box's own words about being reached from outside: fresh at home, as last heard away.
            val reach = info?.reach?.ifEmpty { null } ?: mine.reach
            box.addView(a.body(when {
                a.remoteNow -> "This device is paired. It is connected to the box through the internet now."
                a.reachable -> "This device is paired. When this device is not on the box's network, the app connects to the box through the internet."
                else -> "This device is paired, but the box does not answer through the internet. Connect to the box's network one time. The app then gets the new address of the box."
            }, muted = true))
            if (reach.isNotEmpty()) box.addView(a.body("The box says: $reach", muted = true, top = 8))
            box.addView(a.button("Unpair this device", Kind.DANGER) { a.removeRemote(); remoteSection(info) }.apply { layoutParams = lp(top = a.dp(10)) })
            return
        }
        box.addView(a.body("Pair this device to use the box through the internet when you are not on the box's network. " +
            (if (info?.pairingOpen == true) "Pairing is open on the box now." else "First, open the Devices page of the box and press Pair a device."), muted = true))
        val code = if (info?.pin == true) a.field("The box's code", "", max = 64) { hideKeyboard() }.apply { layoutParams = lp(top = a.dp(10)) }.also(box::addView) else null
        val said = a.body("", top = 8).apply { setTextColor(a.color(R.color.warn)); visibility = View.GONE }
        val button = a.button("Pair this device", Kind.PRIMARY) {}
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
        if (ServerAddress.candidates(typed).isEmpty()) return failed("Type the address of the box. For example: 192.168.1.10.")
        busy("The app connects to the box…")
        a.scope.launch {
            val found = TileServer.connect(typed)
            if (screen != Screen.SERVER) return@launch
            if (found == null) failed("No box answered at this address. Make sure that the address is correct. Make sure that this device is on the box's network.")
            else { serverBusy = false; a.useServer(found) }
        }
    }

    private fun find() {
        if (serverBusy) return
        hideKeyboard()
        busy("The app looks for the box…")
        a.scope.launch {
            val result = Finder.find()
            if (screen != Screen.SERVER) return@launch
            when {
                !result.searched -> failed("This device is not on a Wi-Fi network. Connect to the box's network, or type the address of the box.")
                result.found.isEmpty() -> failed("The app did not find a box on this network. Type the address of the box.")
                result.found.size == 1 -> { serverBusy = false; a.useServer(result.found[0]) }
                else -> {
                    serverBusy = false
                    serverText?.text = "The app found ${result.found.size} boxes. Choose one."
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
