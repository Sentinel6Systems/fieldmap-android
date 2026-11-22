package io.github.sentinel6systems.fieldmap.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
import io.github.sentinel6systems.fieldmap.data.Message
import io.github.sentinel6systems.fieldmap.data.MessageRoute
import io.github.sentinel6systems.fieldmap.data.MessageStatus
import io.github.sentinel6systems.fieldmap.data.Palette
import io.github.sentinel6systems.fieldmap.data.PlannedRoute
import io.github.sentinel6systems.fieldmap.data.Recording
import io.github.sentinel6systems.fieldmap.geo.TrackStats
import io.github.sentinel6systems.fieldmap.data.RouteType
import io.github.sentinel6systems.fieldmap.geo.CoordFormat
import io.github.sentinel6systems.fieldmap.geo.Coords
import io.github.sentinel6systems.fieldmap.geo.Routes
import io.github.sentinel6systems.fieldmap.mesh.MeshNode
import io.github.sentinel6systems.fieldmap.mesh.MeshRadio
import io.github.sentinel6systems.fieldmap.mesh.MeshShare
import io.github.sentinel6systems.fieldmap.mesh.RadioState
import io.github.sentinel6systems.fieldmap.mesh.RadioSetup
import io.github.sentinel6systems.fieldmap.offline.Areas
import io.github.sentinel6systems.fieldmap.remote.RemoteAccess
import io.github.sentinel6systems.fieldmap.remote.RemoteInfo
import io.github.sentinel6systems.fieldmap.offline.Estimate
import io.github.sentinel6systems.fieldmap.offline.RoutingFiles
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
import io.github.sentinel6systems.fieldmap.share.Messaging
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
    STYLES("Map Style", MENU),
    AREAS("Offline Areas", MENU),
    SAVE("Download Area", AREAS),
    MARKERS("Markers", MENU),
    MARKER("Marker", null),
    MOVE("Move Marker", MARKER),
    ROUTE("New Route", MARKERS),
    RECORD("Track Recording", MENU),
    SHARING("Location Sharing", MENU),
    MESH("Mesh Radio", MENU),
    MESSAGES("Messages", MENU),
    CHAT("Conversation", null),
    NODE("Mesh Node", null),
    PEER("Shared Location", null),
    SERVER("Server", MENU),
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
    private var nodeFromList = false
    private var chatWith: String? = null
    private var chatFromPeer = false
    private var chatList: LinearLayout? = null
    private var chatShown = ""
    private var peerShown: Peer? = null
    private var peerWhen: TextView? = null
    private var peerGrid: TextView? = null
    private var peerWhere: TextView? = null

    fun back() = show(when {
        screen == Screen.MARKER && markerFromList -> Screen.MARKERS
        screen == Screen.PEER && peerFromList -> Screen.SHARING
        screen == Screen.NODE && nodeFromList -> Screen.MESH
        screen == Screen.CHAT -> if (chatFromPeer) Screen.PEER else Screen.MESSAGES
        // A route being edited goes back to its panel; a new one to the list it was started from.
        screen == Screen.ROUTE && a.routes.editing != null -> Screen.MARKER
        else -> screen.parent ?: Screen.NONE
    })

    fun showMarker(fromList: Boolean) {
        markerFromList = fromList
        show(Screen.MARKER)
    }

    /** The conversation with `partner` (a uid, or Message.EVERYONE); from a person's panel, Back leads there. */
    fun showChat(partner: String, fromPeer: Boolean) {
        chatWith = partner
        chatFromPeer = fromPeer
        show(Screen.CHAT)
    }

    fun showNode(fromList: Boolean) {
        nodeFromList = fromList
        show(Screen.NODE)
    }

    fun showPeer(fromList: Boolean) {
        peerFromList = fromList
        show(Screen.PEER)
    }

    /** Builds a screen again, if it is the one showing. */
    fun rebuild(which: Screen) { if (screen == which) { hideKeyboard(); build() } }

    fun show(next: Screen) {
        // A route being drawn is kept with Done only; any other way out asks before it is lost.
        if (screen == Screen.ROUTE && next != Screen.ROUTE && a.routes.active) { a.routes.leave(next); return }
        hideKeyboard()
        if (screen == Screen.SAVE) estimateJob?.cancel()
        // Move belongs to the marker's panel: the marker stays chosen between the two.
        if (screen == Screen.MARKER && next != Screen.MARKER && next != Screen.MOVE && next != Screen.ROUTE) a.select(null)
        if ((screen == Screen.MOVE || screen == Screen.ROUTE) && next != Screen.MARKER) a.select(null)
        // A conversation opened from a person's panel leads back to it: the person stays chosen between the two.
        if (screen == Screen.PEER && next != Screen.PEER && next != Screen.CHAT) a.selectPeer(null)
        if (screen == Screen.CHAT && next != Screen.CHAT) {
            Messaging.open = null
            if (next != Screen.PEER) a.selectPeer(null)
        }
        screen = next
        val open = next != Screen.NONE
        a.b.panel.visibility = if (open) View.VISIBLE else View.GONE
        // With a panel open only the zoom buttons stay on the map.
        for (v in listOf(a.b.position, a.b.menu, a.b.foot)) v.visibility = if (open) View.GONE else View.VISIBLE
        a.b.frame.visibility = if (next == Screen.SAVE) View.VISIBLE else View.GONE
        // A marker's panel is an arrow's and a route's too.
        val chosen = a.selected?.let { a.markers.get(it) }
        a.b.panelTitle.text = when {
            next == Screen.ROUTE -> if (a.routes.editing != null) "Edit Route" else next.title
            next == Screen.MARKER && chosen?.isRoute == true -> "Route"
            next == Screen.MARKER && chosen?.isTrack == true -> "Track"
            (next == Screen.MARKER || next == Screen.MOVE) && chosen?.isArrow == true -> if (next == Screen.MARKER) "Arrow" else "Move Arrow"
            else -> next.title
        }
        val hasBack = next.parent != null || (next == Screen.MARKER && markerFromList) || (next == Screen.PEER && peerFromList) ||
            (next == Screen.NODE && nodeFromList) || next == Screen.CHAT
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
            Screen.MESH -> if (meshLook() != meshShown) build()
            Screen.NODE -> if (nodeLook() != nodeShown) build()
            Screen.MESSAGES -> build()
            Screen.CHAT -> chatFill()
            // Only the words: the name may be being typed. A route's panel is drawn again when its legs were worked out.
            Screen.MARKER -> a.selected?.let { a.markers.get(it) }?.let { m ->
                if (m.route != null && routeLook(m.route) != routeShown) build() else (markerShare?.tag as? TextView)?.text = shareDetail(m)
            }
            Screen.ROUTE -> a.routes.route?.let { if (routeLook(it) != routeShown) build() }
            Screen.RECORD -> build()
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
            Screen.ROUTE -> route()
            Screen.MOVE -> add(a.body(when {
                a.selected?.let { a.markers.get(it) }?.isArrow != true -> "Tap the marker's new location on the map. You can move and zoom the map first."
                a.movingEnd -> "Tap the arrow's new head (end) on the map. You can move and zoom the map first."
                else -> "Tap the arrow's new start on the map. You can move and zoom the map first."
            }, top = 12))
            Screen.SHARING -> sharing()
            Screen.MESH -> mesh()
            Screen.NODE -> node()
            Screen.MESSAGES -> messages()
            Screen.CHAT -> chat()
            Screen.PEER -> peer()
            Screen.SERVER -> server()
            Screen.SETTINGS -> settings()
            Screen.RECORD -> record()
            Screen.NONE -> {}
        }
    }

    private fun add(v: View) = body.addView(v)
    private fun number(n: Long) = NumberFormat.getIntegerInstance().format(n)

    /* ---------- menu ---------- */

    private fun menu() {
        val saved = a.areas.list
        add(a.row("Map Style", a.shownStyleName() ?: "None") { show(Screen.STYLES) })
        add(a.row("Offline Areas", if (saved.isEmpty()) "None" else "${saved.size} downloaded · ${bytesText(a.areas.storedBytes())}") { show(Screen.AREAS) })
        add(a.row("Markers", if (a.markers.markers.isEmpty()) "None" else "${a.markers.markers.size}") { show(Screen.MARKERS) })
        add(a.row("Track Recording", when (Recording.state) {
            Recording.State.OFF -> "Off"
            Recording.State.ON -> "Recording · " + a.distanceText(TrackStats(Recording.segments).meters)
            Recording.State.PAUSED -> "Paused · " + a.distanceText(TrackStats(Recording.segments).meters)
        }) { show(Screen.RECORD) })
        add(a.row("Location Sharing", a.sharing.summary()) { show(Screen.SHARING) })
        add(a.row("Messages", Messaging.store.unread().let { if (it == 0) "No new messages" else "$it new" }) { show(Screen.MESSAGES) })
        add(a.row("Mesh Radio", if (a.prefs.radioAddress == null) "Not Paired" else meshStatus() +
            MeshRadio.nearby().size.let { if (MeshRadio.status == MeshRadio.Status.READY && it > 0) " · $it node${if (it == 1) "" else "s"} nearby" else "" }) { show(Screen.MESH) })
        add(a.row("Server", a.prefs.server?.let { ServerAddress.short(it) + " · " + boxState() } ?: "Not Configured") { show(Screen.SERVER) })
        add(a.row("Settings", "Coordinate format: ${a.prefs.coordFormat.title}") { show(Screen.SETTINGS) })
        // The one way to end a shared position for good, besides swiping the app away.
        add(a.button("Exit") { a.quit() }.apply { layoutParams = lp(top = a.dp(16)) })
        add(a.body("When you leave Fieldmap or swipe it away, it keeps sharing your location (while on) and takes in new messages with a notification. Exit stops it.", muted = true, top = 8))
        add(a.mono("Fieldmap ${BuildConfig.VERSION_NAME}", 12f).apply { layoutParams = lp(top = a.dp(14)) })
    }

    /* ---------- map style ---------- */

    private fun styles() {
        val all = a.prefs.styles
        if (all.isEmpty()) {
            add(a.body("Connect to a server first. The map styles come from the server.", muted = true, top = 12))
            add(a.button("Server") { show(Screen.SERVER) }.apply { layoutParams = lp(top = a.dp(12)) })
            return
        }
        val current = a.currentStyle()
        for (style in all) {
            val saved = a.areas.list.count { it.styleUrl == style.url }
            val detail = if (saved > 0) "$saved offline area${if (saved == 1) "" else "s"}" else if (!a.reachable) "Not downloaded" else null
            add(a.row(style.name, detail, icon = if (style.id == current?.id) R.drawable.ic_check else null) { a.chooseStyle(style) }
                .apply { if (style.id != current?.id && saved == 0 && !a.reachable) alpha = 0.55f })
        }
    }

    /* ---------- settings ---------- */

    /** The format of all positions in the app, each choice shown with your position (or the map's centre) in it. */
    private fun settings() {
        add(a.label("Coordinate Format"))
        val here = a.fix()?.let { it.lat to it.lon } ?: a.map?.cameraPosition?.target?.let { it.latitude to it.longitude }
        val chosen = a.prefs.coordFormat
        for (f in CoordFormat.entries) {
            add(a.row(f.title, here?.let { Coords.text(it.first, it.second, f) }, icon = if (f == chosen) R.drawable.ic_check else null) {
                a.prefs.coordFormat = f
                a.coordsChanged()
                build()
            })
        }
        add(a.body("All coordinates in the app use this format, including the top bar, markers, and shared locations.", muted = true, top = 12))

        // What routes show: each part can be turned off when the map or the panel gets too full.
        add(a.label("Routes"))
        fun option(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit) =
            add(a.check(title, detail, on) { set(it); a.routesChanged(); build() })
        val p = a.prefs
        option("Direction Indicators", "Chevrons along the line show the direction of travel.", p.routeDirection) { p.routeDirection = it }
        option("Leg Distances on Map", "Each leg's distance as a label on the line.", p.routeLegLabels) { p.routeLegLabels = it }
        option("Total Distance and Time", "In the route's panel.", p.routeTotals) { p.routeTotals = it }
        option("Leg Details", "Each leg's distance and bearing, in the route's panel.", p.routeLegs) { p.routeLegs = it }
    }

    /* ---------- saved areas ---------- */

    private fun areas() {
        // Opening the list looks for new or missing routing files too (at most twice a minute).
        a.syncRouting(notWithin = 30_000)
        val canSave = a.reachable && a.currentStyle() != null
        add(a.button("Download Area", Kind.PRIMARY) { a.startFraming() }.apply { layoutParams = lp(top = a.dp(12)); enable(canSave) })
        if (!canSave) add(a.body("Connect to the server to download an area.", muted = true, top = 8))
        if (a.areas.list.isEmpty()) {
            add(a.body("No offline areas. Download an area to use the map without a connection to the server.", muted = true, top = 14))
            return
        }
        add(a.label("Downloaded Areas"))
        for (area in a.areas.list) add(areaCard(area))
        // The routing files that come with the areas, for routes along roads and trails.
        val routing = a.routingFiles
        add(a.row("Routing Data", routing.downloading?.let { "Downloading ${it.removeSuffix(".rd5")} · ${routing.percent}%" }
            ?: routing.stored().let { files ->
                val have = if (files.isEmpty()) "None" else "${files.size} file${if (files.size == 1) "" else "s"} · ${bytesText(routing.storedBytes())}"
                have + when (routing.check) {
                    RoutingFiles.Check.NOT_YET -> if (files.isEmpty()) " · Not checked yet" else ""
                    RoutingFiles.Check.NO_ANSWER -> " · No answer from the server"
                    RoutingFiles.Check.NONE_ON_SERVER -> " · The server has no routing data"
                    RoutingFiles.Check.NOT_COVERED -> " · The server's routing data does not cover your offline areas"
                    RoutingFiles.Check.FAILED -> " · Download failed, retrying later"
                    RoutingFiles.Check.DONE -> ""
                }
            }, icon = null))
        add(a.label("Updates"))
        add(a.body("When the server has new maps, update your offline areas. The app downloads all areas again.", muted = true))
        add(a.button("Update All") { confirmUpdate() }.apply { layoutParams = lp(top = a.dp(10)); enable(a.reachable && a.areas.downloading == null) })
    }

    private fun areaCard(area: SavedArea): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp()
        val detail = when {
            area.downloading -> "Downloading ${area.percent}% · ${bytesText(area.bytes)}" + if (area.waiting) " · Waiting for server" else ""
            !area.complete -> "Incomplete · ${bytesText(area.bytes)}"
            else -> "${area.styleName} · Detail ${area.maxZoom} · ${bytesText(area.bytes)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(area.created))}"
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
            .setTitle("Update All Areas?")
            .setMessage("The app downloads all offline areas again from the server. Keep the app open until the download completes.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Update") { _, _ -> a.areas.updateAll() }
            .show()
    }

    private fun confirmDelete(area: SavedArea) {
        AlertDialog.Builder(a)
            .setTitle("Delete \"${area.name}\"?")
            .setMessage("The maps for this area will be removed from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> a.areas.delete(area); a.syncRouting() }
            .show()
    }

    /* ---------- save a new area ---------- */

    private fun save() {
        add(a.body("Move and zoom the map to fit the area in the frame.", top = 12))
        add(a.label("Area Name"))
        val used = a.areas.list.map { it.name }.toSet()
        val name = generateSequence(a.areas.list.size + 1) { it + 1 }.map { "Area $it" }.first { it !in used }
        areaName = a.field("Area Name", name, max = Drawings.MAX_LABEL) { hideKeyboard() }.also(::add)
        add(a.label("Detail Level"))
        val depths = ArrayList<View>()
        add(LinearLayout(a).apply {
            for (z in Tiles.DEPTHS) addView(TextView(a).apply {
                text = z.toString()
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(a.color(R.color.ink))
                setBackgroundResource(R.drawable.bg_control)
                contentDescription = "Detail Level $z"
                isSelected = z == a.prefs.areaZoom
                setOnClickListener { hideKeyboard(); a.prefs.areaZoom = z; depths.forEach { it.isSelected = it === this }; estimate() }
                depths += this
            }, LinearLayout.LayoutParams(0, a.dp(52), 1f).apply { if (childCount > 0) marginStart = a.dp(6) })
        })
        add(a.body("Level 14 includes all map detail. Lower levels download faster but show less detail.", muted = true, top = 8))
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
        if (style == null || !a.reachable) return say("No connection to the server. You cannot download an area now.", R.color.warn)
        estimateJob = a.scope.launch {
            delay(350)
            val list = sources.of(style.url) ?: return@launch say("No response from the server.", R.color.warn)
            val box = a.frameBox() ?: return@launch say("Area too large. Zoom in.", R.color.warn)
            val depth = a.prefs.areaZoom
            val tiles = Tiles.count(list, box, depth)
            if (tiles > Areas.MAX_TILES) return@launch say("Area too large (${number(tiles)} tiles). Lower the detail level or zoom in.", R.color.warn)
            say("${number(tiles)} tiles. Calculating size…")
            // With the routing files the area touches that are not on the device yet.
            val routing = a.routingFiles.bytesFor(box)
            val bytes = Estimate.bytes(list, box, depth) + routing
            val free = a.filesDir.usableSpace
            val line = "Size: about ${bytesText(bytes)} · ${number(tiles)} tiles" + (if (routing > 0) " · routing data ${bytesText(routing)}" else "") +
                "\nFree space: ${bytesText(free)}"
            when {
                bytes > free - Areas.KEEP_FREE_BYTES -> say("$line\nNot enough free space. Select a smaller area.", R.color.warn)
                bytes > Areas.LARGE_BYTES -> say("$line\nLarge download. Use Wi-Fi.", R.color.amber, ok = true)
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
        add(a.button("New Route", Kind.PRIMARY) { a.routes.start(null) }.apply { layoutParams = lp(top = a.dp(12)) })
        if (list.isEmpty()) add(a.body("No markers. To add one, touch and hold the map for 2 seconds, then lift your finger. To draw an arrow, " +
            "keep holding and drag to where it points. To add a marker at your location, touch and hold the blue dot.", muted = true, top = 12))
        for (m in list.asReversed()) {
            val where = when {
                m.track != null -> TrackStats(m.track.segments).let { "Track · " + a.distanceText(it.meters) + " · " + Routes.timeText((it.seconds / 60).toInt()) }
                m.route != null -> "Route · " + a.distanceText(a.routeLines.legs(m.route).sumOf { Routes.length(it.line) }) + " · ${m.route.points.size} points"
                m.isArrow -> "Arrow · " + a.arrowDetail(m)
                else -> a.coords(m.lat, m.lon, brief = true)
            }
            add(a.row(m.label.ifEmpty { "Unnamed" }, where + if (m.shared) " · Shared" else "", dot = Color.parseColor(m.color)) { a.openMarker(m.id, fromList = true) })
        }
        add(a.label("Import / Export"))
        add(a.body("Export markers to a GeoJSON file for use in other map apps. On the Print Map page of the server, open the file with Import Drawings.", muted = true))
        add(a.buttons(
            a.button("Export") { a.saveMarkersFile() }.apply { enable(list.isNotEmpty()) },
            a.button("Import") { a.loadMarkersFile() },
        ))
    }

    private fun marker() {
        markerShare = null
        val m = a.selected?.let { a.markers.get(it) } ?: return
        add(a.label("Name", top = 10))
        val name = a.field("Name", m.label, max = Drawings.MAX_LABEL) { hideKeyboard() }
        name.doAfterTextChanged { text -> a.markers.get(m.id)?.let { a.updateMarker(it.copy(label = text?.toString().orEmpty().trim())) } }
        add(name)

        add(a.label("Color"))
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

        if (m.track != null) {
            // A recorded track: how far and how long, moving and stopped, up and down, when.
            val t = TrackStats(m.track.segments)
            add(a.label("Distance and Time"))
            add(a.mono(a.distanceText(t.meters) + " · " + Routes.timeText((t.seconds / 60).toInt()), 18f, muted = false))
            add(a.mono("Moving " + Routes.timeText((t.movingSeconds / 60).toInt()) + " · Stopped " + Routes.timeText((t.stoppedSeconds / 60).toInt()), 15f, muted = false))
            t.climb?.let { (up, down) ->
                add(a.label("Elevation"))
                add(a.mono(a.heightText(up) + " up · " + a.heightText(down) + " down", 16f, muted = false))
            }
            add(a.label("Recorded"))
            val first = m.track.segments.first().first().time
            val last = m.track.segments.last().last().time
            add(a.mono(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(first)) + " – " +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)), 15f, muted = false))
            add(a.mono("${t.points} points" + if (m.track.segments.size > 1) " · ${m.track.segments.size} parts (paused between)" else "", 14f))
        } else if (m.route != null) {
            // A route: how its legs go, how long and how long it takes at its speed, each leg, then where
            // it starts and ends (as the settings say).
            val points = m.route.points
            routeShown = routeLook(m.route)
            add(a.label("Route Type"))
            add(a.mono(m.route.type.title, 16f, muted = false))
            routeNote(m.route)?.let { add(a.body(it, muted = true, top = 6)) }
            if (a.prefs.routeTotals) {
                add(a.label("Distance and Time"))
                add(a.mono(a.routeDetail(m.route), 18f, muted = false))
                add(a.row("Speed", a.speedText(m.route.mph)) { chooseSpeed(m.id) })
            }
            if (a.prefs.routeLegs) {
                add(a.label("Legs"))
                // Each leg's length as it is drawn (along the road when it follows one), and the true bearing from point to point.
                for ((i, leg) in a.routeLines.legs(m.route).withIndex()) {
                    val bearing = Routes.legs(listOf(points[i], points[i + 1])).first().bearing
                    add(a.mono("${i + 1} → ${i + 2}   " + a.distanceText(Routes.length(leg.line)) + " · " + Routes.bearingText(bearing), 15f, muted = false)
                        .apply { setPadding(0, a.dp(3), 0, a.dp(3)) })
                }
            }
            add(a.label("Start"))
            add(a.mono(a.coords(points.first().first, points.first().second), 16f, muted = false))
            add(a.label("End"))
            add(a.mono(a.coords(points.last().first, points.last().second), 16f, muted = false))
        } else if (m.isArrow) {
            // An arrow: how long and which way, then where it starts and where it points.
            add(a.label("Length and Bearing"))
            add(a.mono(a.arrowDetail(m), 18f, muted = false))
            add(a.label("Start"))
            add(a.mono(a.coords(m.lat, m.lon), 16f, muted = false))
            add(a.label("Head"))
            add(a.mono(a.coords(m.endLat!!, m.endLon!!), 16f, muted = false))
        } else {
            add(a.label("Location"))
            val grid = a.mono("", 18f, muted = false)
            val far = a.mono("")
            grid.text = a.coords(m.lat, m.lon)
            far.text = a.fromHere(m.lat, m.lon).orEmpty()
            far.visibility = if (far.text.isEmpty()) View.GONE else View.VISIBLE
            add(grid)
            add(far)
        }

        // One choice, off until the person makes it. A server known to have no sharing does not offer it.
        // A recorded track is not shared: it is exported.
        if (!m.isTrack && (m.shared || a.sharing.box != Sharing.Box.NONE)) {
            markerShare = a.check("Share with All Devices", shareDetail(m), m.shared) { on -> hideKeyboard(); a.shareMarker(m.id, on); build() }
                .apply { layoutParams = lp(top = a.dp(10)) }.also(::add)
        }
        val delete = a.button("Delete", Kind.DANGER) { if (a.markers.get(m.id)?.shared == true) confirmDeleteShared(m.id) else { a.deleteMarker(m.id); back() } }
        if (m.isTrack) {
            add(a.buttons(a.button("Delete", Kind.DANGER) { confirmDeleteTrack(m.id) }, a.button("Export") { a.exportTrack(m) }, top = 16))
        } else if (m.isRoute) {
            add(a.buttons(delete, a.button("Edit Route") { a.routes.start(m.id) }, top = 16))
        } else if (m.isArrow) {
            add(a.buttons(
                a.button("Move Start") { a.movingEnd = false; show(Screen.MOVE) },
                a.button("Move End") { a.movingEnd = true; show(Screen.MOVE) },
                top = 16,
            ))
            add(a.buttons(delete, top = 8))
        } else {
            add(a.buttons(delete, a.button("Move") { show(Screen.MOVE) }, top = 16))
        }
    }

    /** The speed a route's time is worked out at, from the list of Routes.SPEEDS. */
    private fun chooseSpeed(id: String) {
        val route = a.markers.get(id)?.route ?: return
        AlertDialog.Builder(a)
            .setTitle("Speed")
            .setSingleChoiceItems(Routes.SPEEDS.map { a.speedText(it) }.toTypedArray(), Routes.SPEEDS.indexOf(route.mph)) { dialog, i ->
                a.markers.get(id)?.let { m -> m.route?.let { a.updateMarker(m.copy(route = it.copy(mph = Routes.SPEEDS[i]))) } }
                dialog.dismiss()
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** New Route and Edit Route: how it works, its Route Type, how far, and Undo, Remove Point, Done. */
    private fun route() {
        val route = a.routes.route ?: return
        val points = route.points
        routeShown = routeLook(route)
        add(a.body("Tap the map to add a point. Drag a point to move it. Drag the dot between two points to add a point there. " +
            "Tap a point to select it.", muted = true, top = 12))
        add(a.row("Route Type", route.type.title) { chooseType() }.apply { layoutParams = lp(top = a.dp(6)) })
        val mph = a.routes.editing?.let { a.markers.get(it) }?.route?.mph ?: route.type.mph
        val summary = when (points.size) {
            0 -> "No points"
            1 -> "1 point"
            else -> "${points.size} points · " + a.routeDetail(route.copy(mph = mph))
        } + (a.routes.chosen?.let { " · Point ${it + 1} selected" } ?: "")
        add(a.mono(summary, 15f, muted = false).apply { setPadding(0, a.dp(10), 0, 0) })
        routeNote(route)?.let { add(a.body(it, muted = true, top = 6)) }
        add(a.buttons(
            a.button("Undo") { a.routes.undo() }.apply { enable(a.routes.canUndo) },
            a.button("Remove Point") { a.routes.removeChosen() }.apply { enable(a.routes.chosen != null) },
            top = 14,
        ))
        add(a.button("Done", Kind.PRIMARY) { a.routes.finish() }.apply { layoutParams = lp(top = a.dp(10)); enable(points.size >= 2) })
    }

    /** How a route stands, to draw its panel again when that changes (a leg was worked out). */
    private var routeShown = ""
    private fun routeLook(r: PlannedRoute) = r.toString() + a.routeLines.legs(r, ask = false).joinToString { "${it.state}${it.line.size}" }

    /** What to say when a route's legs do not (yet) follow roads or trails as its type says; null when they do. */
    private fun routeNote(r: PlannedRoute): String? {
        val legs = a.routeLines.legs(r)
        val states = legs.map { it.state }
        // The points off the road (one in the middle ends two legs).
        val offRoad = legs.withIndex().flatMap { (i, l) -> listOfNotNull(i.takeIf { l.offStart }, (i + 1).takeIf { l.offEnd }) }.toSet().size
        return listOfNotNull(
            when {
                LegState.NO_WAY in states -> "Dashed legs are straight: no way was found there, or this device has no routing data for the area. " +
                    "To get it, download an offline area there while connected to the server."
                LegState.WAITING in states -> "Finding the way…"
                else -> null
            },
            if (offRoad > 0) "${if (offRoad == 1) "One point is" else "$offRoad points are"} off the roads and trails for this Route Type: " +
                "the route goes along them to the nearest one, then straight (dashed) to the point." else null,
        ).joinToString("\n\n").ifEmpty { null }
    }

    /** Driving, Walking or Straight Line, for the route being drawn. */
    private fun chooseType() {
        val types = RouteType.entries
        AlertDialog.Builder(a)
            .setTitle("Route Type")
            .setSingleChoiceItems(types.map { it.title }.toTypedArray(), types.indexOf(a.routes.type)) { dialog, i ->
                dialog.dismiss()
                a.routes.changeType(types[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A recorded track cannot be made again: deleting asks first. */
    private fun confirmDeleteTrack(id: String) {
        AlertDialog.Builder(a)
            .setTitle("Delete Track?")
            .setMessage("This deletes the recorded track from this device. Export it first to keep a copy.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> a.deleteMarker(id); if (screen == Screen.MARKER) back() }
            .show()
    }

    /* ---------- track recording ---------- */

    /** Track Recording: the interval, and Start Recording; while on, Pause or Resume, and Stop. */
    private fun record() {
        val state = Recording.state
        add(a.body("Records your path on this device, also with the screen off or another app open. Standing still adds no " +
            "points; turns add points sooner, so winding trails keep their shape.", muted = true, top = 12))
        val interval = if (state == Recording.State.OFF) a.prefs.recordInterval else Recording.interval
        add(a.row("Point Interval", intervalText(interval)) { chooseInterval() }.apply { layoutParams = lp(top = a.dp(6)) })
        if (state == Recording.State.OFF) {
            add(a.button("Start Recording", Kind.PRIMARY) { a.startRecording() }.apply { layoutParams = lp(top = a.dp(12)) })
            return
        }
        val t = TrackStats(Recording.segments)
        add(a.label(if (state == Recording.State.ON) "Recording" else "Paused"))
        add(a.mono("Since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Recording.started)) + " · " + a.distanceText(t.meters) +
            " · ${t.points} point${if (t.points == 1) "" else "s"}", 16f, muted = false))
        add(a.buttons(
            if (state == Recording.State.ON) a.button("Pause") { Recording.pause() } else a.button("Resume") { Recording.resume() },
            a.button("Stop", Kind.PRIMARY) { a.stopRecording() },
            top = 14,
        ))
    }

    private fun intervalText(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

    /** 15 s, 30 s, 1 min or 5 min between points; while recording, from now on. */
    private fun chooseInterval() {
        val choices = Recording.INTERVALS
        val now = if (Recording.state == Recording.State.OFF) a.prefs.recordInterval else Recording.interval
        AlertDialog.Builder(a)
            .setTitle("Point Interval")
            .setSingleChoiceItems(choices.map(::intervalText).toTypedArray(), choices.indexOf(now)) { dialog, i ->
                dialog.dismiss()
                a.prefs.recordInterval = choices[i]
                if (Recording.state != Recording.State.OFF) Recording.interval(choices[i])
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "marker", "arrow" or "route", for the words about one. */
    private fun noun(m: Marker?) = if (m?.isTrack == true) "track" else if (m?.isRoute == true) "route" else if (m?.isArrow == true) "arrow" else "marker"

    private fun shareDetail(m: Marker): String = when {
        !m.shared -> "Off. This ${noun(m)} is only on this device."
        a.sharing.waiting(m.id) -> "Waiting to sync with the server."
        else -> "All devices on this server see this ${noun(m)} and can edit or delete it. Uncheck to keep it on this device only."
    }

    private fun confirmDeleteShared(id: String) {
        AlertDialog.Builder(a)
            .setTitle("Delete Shared ${noun(a.markers.get(id)).replaceFirstChar { it.uppercase() }}?")
            .setMessage("This deletes the ${noun(a.markers.get(id))} from all devices on this server.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> a.deleteMarker(id); if (screen == Screen.MARKER) back() }
            .show()
    }

    /* ---------- sharing ---------- */

    private fun sharing() {
        val s = a.sharing
        shareAbout = a.body(s.about(), muted = true, top = 12).also(::add)
        add(a.label("My Location"))
        val name = a.field("Display Name", a.prefs.shareName, max = ShareJson.MAX_NAME) { hideKeyboard() }
        if (s.on) {
            shareMine = a.body(s.mine()).also(::add)
            add(a.body("Sharing continues when you leave the app or the screen turns off. To stop, press Stop Sharing. When you are not moving, the app sends your location less often to save battery.", muted = true, top = 8))
            add(a.label("Display Name"))
            name.doAfterTextChanged { text -> ShareJson.name(text?.toString().orEmpty()).ifEmpty { null }?.let { a.prefs.shareName = it } }
            add(name)
            add(a.button("Stop Sharing", Kind.DANGER) { a.sharing.switchOff(); rebuild(Screen.SHARING) }.apply { layoutParams = lp(top = a.dp(12)) })
        } else {
            shareMine = null
            add(a.body("Off. When you share, other devices on this server see your location under this name. Sharing continues in the background and stops when you exit Fieldmap."))
            add(a.label("Display Name"))
            add(name)
            val go = a.button("Start Sharing", Kind.PRIMARY) { a.startSharing(name.text.toString()) }
            fun ready() = go.enable(a.prefs.server != null && s.box != Sharing.Box.NONE && ShareJson.name(name.text.toString()).isNotEmpty())
            name.doAfterTextChanged { ready() }
            ready()
            add(go.apply { layoutParams = lp(top = a.dp(12)) })
        }
        add(a.label("Other Devices"))
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
            if (others.isEmpty()) list.addView(a.body(if (s.box == Sharing.Box.ON && a.reachable) "No other devices are sharing." else "No locations to show.", muted = true))
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
        add(a.label("Location"))
        peerGrid = a.mono("", 18f, muted = false).also(::add)
        peerWhere = a.mono("").also(::add)
        peerFill()
        add(a.button("Send Message", Kind.PRIMARY) { showChat(uid, fromPeer = true) }.apply { layoutParams = lp(top = a.dp(16)) })
    }

    private fun peerFill() {
        val uid = a.selectedPeer ?: return
        val info = a.sharing.info
        val now = a.sharing.other(uid)
        if (now != null) peerShown = now
        val p = peerShown ?: return
        peerWhen?.text = when {
            now == null -> "${p.name} is no longer sharing. This is the last location received."
            a.sharing.look(p) == Ageing.Look.FRESH -> "Updated ${Ageing.text(p.age)}." + if (p.still && p.age >= 30) " ${p.name} is not moving." else ""
            else -> "Last update ${Ageing.text(p.age)}. ${p.name} may have moved."
        }
        peerGrid?.text = a.coords(p.lat, p.lon)
        peerWhere?.text = a.fromHere(p.lat, p.lon).orEmpty()
        peerWhere?.visibility = if (peerWhere?.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Once a second: the ages in the open panel of another phone, and in the list. */
    fun peerTick() {
        if (screen == Screen.PEER) peerFill()
        // The wait before the next trace route counts down.
        if (screen == Screen.NODE && nodeLook() != nodeShown) build()
        if (screen == Screen.SHARING) sharingFill()
    }

    /* ---------- mesh radio ---------- */

    // What the Mesh Radio screen was built from: it is built again only when that changed.
    private var meshShown = ""

    private fun meshLook(): String = with(MeshRadio) {
        listOf(status, problem, searching, found.keys.joinToString(), state.battery, state.firmware, a.prefs.radioAddress, a.prefs.radioPin,
            a.prefs.meshChannel?.rev, a.prefs.meshReadOnly, a.prefs.meshNodesOnMap, nearby().joinToString { nodeDetail(it) }).joinToString("|")
    }

    private fun meshStatus(): String = when (MeshRadio.status) {
        MeshRadio.Status.NONE -> "Not Paired"
        MeshRadio.Status.OFFLINE -> "Not Connected"
        MeshRadio.Status.PAIRING -> "Pairing…"
        MeshRadio.Status.CONNECTING -> "Connecting…"
        MeshRadio.Status.SETTING_UP -> "Configuring…"
        MeshRadio.Status.NEEDS_REGION -> "Region Required"
        MeshRadio.Status.NO_CHANNEL -> "Waiting for Server Channel"
        MeshRadio.Status.LOCKED -> "Locked"
        MeshRadio.Status.READY -> "Connected"
        MeshRadio.Status.FAILED -> "Error"
    }

    private fun mesh() {
        meshShown = meshLook()
        val radio = MeshRadio
        if (a.prefs.radioAddress == null) {
            add(a.body("Pair a Meshtastic radio to share locations and markers with the other devices of this server where there is no network. " +
                "Fieldmap configures the radio for the server's private channel.", muted = true, top = 12))
            if (radio.problem.isNotEmpty()) add(a.body(radio.problem, top = 8))
            add(a.button(if (radio.searching) "Searching…" else "Pair Radio", Kind.PRIMARY) { a.searchRadios() }
                .apply { enable(!radio.searching); layoutParams = lp(top = a.dp(12)) })
            if (radio.searching || radio.found.isNotEmpty()) {
                add(a.label("Radios Nearby"))
                if (radio.found.isEmpty()) add(a.body("Turn the radio on and keep it close to this device.", muted = true))
                for (f in radio.found.values.sortedByDescending { it.rssi }) add(a.row(f.name, "Signal: ${f.rssi} dBm") { radio.pair(f) })
            }
            return
        }
        val s = radio.state
        add(a.row(a.prefs.radioName ?: "Radio", meshStatus(), icon = null))
        add(a.body(meshExplain(), muted = true, top = 4))
        if (radio.status == MeshRadio.Status.NEEDS_REGION) {
            add(a.label("Region"))
            add(a.body("Choose the region where you use the radio. It sets the frequencies the radio may use.", muted = true))
            for (r in RadioSetup.regions) add(a.row(r.name, null) { radio.setRegion(r) })
        }
        add(a.label("Details"))
        if (s.model.isNotEmpty()) add(a.row("Model", s.model, icon = null))
        if (s.firmware.isNotEmpty()) add(a.row("Firmware", s.firmware, icon = null))
        s.battery?.let { add(a.row("Battery", if (it > 100) "External Power" else "$it %", icon = null)) }
        if (s.region != org.meshtastic.proto.Config.LoRaConfig.RegionCode.UNSET) add(a.row("Region", s.region.name, icon = null))
        add(a.row("Channel", a.prefs.meshChannel?.let { "Server channel ${it.name}" } ?: "Not received", icon = null))
        add(a.label("Public Channel"))
        val readOnly = a.prefs.meshReadOnly
        add(a.check("Read Only", if (readOnly) "This radio listens on the public channel and does not announce itself there."
            else "Other Meshtastic users see this radio under your display name.", readOnly) { radio.setReadOnly(it) })
        add(a.body("The public channel shows the other Meshtastic radios nearby. They can pass your packets on, but cannot read them. " +
            "Locations and markers always go on the server's private channel only.", muted = true, top = 4))
        val nodes = radio.nearby()
        add(a.label("Nodes Nearby"))
        add(a.check("Show on Map", "Other Meshtastic radios that report a location appear on the map as green triangles.", a.prefs.meshNodesOnMap) {
            a.prefs.meshNodesOnMap = it
            a.nodesChanged()
            build()
        })
        if (nodes.isEmpty()) add(a.body(if (radio.status == MeshRadio.Status.READY) "No other radios heard in the last 2 hours." else "Connect to the radio to see the radios nearby.", muted = true))
        for (n in nodes) add(a.row(n.name, nodeDetail(n)) { a.openNode(n.num, fromList = true) })
        if (a.prefs.radioPin != 0) {
            add(a.row("Bluetooth PIN", "%06d".format(a.prefs.radioPin), icon = null))
            add(a.body("Fieldmap replaced the radio's factory PIN. Use this PIN to pair the radio again.", muted = true))
        }
        val buttons = mutableListOf<View>()
        if (radio.status == MeshRadio.Status.OFFLINE || radio.status == MeshRadio.Status.FAILED) buttons += a.button("Connect", Kind.PRIMARY) { a.connectRadio() }
        buttons += a.button("Forget Radio", Kind.DANGER) { confirmForgetRadio() }
        add(a.buttons(*buttons.toTypedArray()))
    }

    /* ---------- messages ---------- */

    /** A person's name: as sharing shows them now, or as their last message had it. */
    private fun nameOf(partner: String): String = when (partner) {
        Message.PUBLIC -> "Group (Public)"
        Message.EVERYONE -> "Group (Private)"
        else -> a.sharing.other(partner)?.name ?: Messaging.store.nameOf(partner) ?: "Unknown device"
    }

    private fun clock(iso: String): String {
        val ms = ShareJson.millis(iso)
        val today = (System.currentTimeMillis() - ms) < 12 * 3600_000L
        return (if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)).format(Date(ms))
    }

    /**
     * The conversations: Group (Public) (Meshtastic's public channel, with a mesh radio), Group
     * (Private) (everybody of this server), then the people with messages or sharing now, the
     * latest first.
     */
    private fun messages() {
        val store = Messaging.store
        val people = (store.partners() + a.sharing.others().map { it.uid }).distinct().filter { it != Message.EVERYONE && it != Message.PUBLIC }
        add(a.body("Messages go through the server when it can be reached, and over the mesh radio when not. Up to ${Message.MAX} characters.", muted = true, top = 12))
        val groups = listOfNotNull(Message.PUBLIC.takeIf { a.prefs.radioAddress != null }, Message.EVERYONE)
        for (partner in groups + people) {
            if (partner == people.firstOrNull()) add(a.label("Direct Messages"))
            val last = store.conversation(partner).lastOrNull()
            val unread = store.unread(partner)
            val empty = when (partner) {
                Message.PUBLIC -> "Everybody on the Meshtastic public channel nearby"
                Message.EVERYONE -> "All devices of this server"
                else -> "No messages yet"
            }
            val detail = (if (unread > 0) "$unread new · " else "") + (last?.let { (if (it.mine) "You: " else "${it.name}: ".takeIf { _ -> partner in groups } ?: "") + it.text } ?: empty)
            add(a.row(nameOf(partner), detail) { showChat(partner, fromPeer = false) })
        }
    }

    private fun chat() {
        val partner = chatWith ?: return
        Messaging.open = partner
        Messaging.store.markRead(partner)
        add(a.body(nameOf(partner), top = 12).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        chatList = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp() }.also(::add)
        chatShown = ""
        chatFill()
        if (partner == Message.PUBLIC && a.prefs.meshReadOnly) {
            add(a.body("The public channel is Read Only (Menu > Mesh Radio). You see its messages but do not send there, " +
                "so other Meshtastic users do not see this radio.", muted = true, top = 16))
            return
        }
        add(a.label("New Message"))
        val text = a.field("Message", "", max = Message.MAX)
        add(text)
        val way = a.body("", muted = true, top = 4).also(::add)
        fun say() { way.text = routeText(partner) }
        say()
        fun send() {
            if (Messaging.send(partner, text.text.toString()) != null) text.setText("")
            hideKeyboard()
            say()
        }
        // The keyboard's Enter (Send, Done, or the Enter key itself) sends, and the keyboard goes.
        text.imeOptions = EditorInfo.IME_ACTION_SEND
        text.setOnEditorActionListener { _, action, event ->
            val enter = action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) send()
            enter
        }
        add(a.button("Send", Kind.PRIMARY) { send() }.apply { layoutParams = lp(top = a.dp(8)) })
    }

    /** The messages of the open conversation, the latest 50; built again only when they changed. */
    private fun chatFill() {
        val partner = chatWith ?: return
        val list = chatList ?: return
        Messaging.store.markRead(partner)
        val messages = Messaging.store.conversation(partner).takeLast(50)
        val look = messages.joinToString { it.id + it.status + it.via + it.deliveredTo.size }
        if (look == chatShown) return
        chatShown = look
        list.removeAllViews()
        if (messages.isEmpty()) list.addView(a.body("No messages yet.", muted = true, top = 8))
        for (m in messages) {
            // How it went or came: through the server, over the mesh, or both (a message to everybody).
            val how = when (m.via) { "mesh" -> " (Mesh)"; "server" -> " (Server)"; "mesh+server" -> " (Server and Mesh)"; else -> "" }
            val status = " · " + when {
                !m.mine -> "Received$how"
                m.status == MessageStatus.WAITING -> "Waiting"
                // To a group: how many devices said they have it.
                m.to == Message.EVERYONE -> "Sent$how" + if (m.deliveredTo.isEmpty()) "" else " · Delivered to ${m.deliveredTo.size}"
                m.status == MessageStatus.SENT -> "Sent$how"
                else -> "Delivered$how"
            }
            list.addView(a.mono((if (m.mine) "You" else m.name) + " · " + clock(m.time) + status, 12f).apply { setPadding(0, a.dp(10), 0, 0) })
            list.addView(a.body(m.text))
        }
    }

    /** How a message written now would go. */
    private fun routeText(partner: String): String {
        if (partner == Message.PUBLIC) return if (MeshRadio.status == MeshRadio.Status.READY)
            "Sends over the mesh radio on the public channel: every Meshtastic user nearby can read it."
            else "Waits for the mesh radio: the public channel is reached over the mesh only."
        val ways = Messaging.ways(partner)
        return when {
            ways.isEmpty() -> "No connection: messages wait and go out when the server or the mesh radio can be reached."
            partner == Message.EVERYONE -> "Sends through " + ways.joinToString(" and ") { if (it == MessageRoute.Way.SERVER) "the server" else "the mesh radio" } + "."
            ways.first() == MessageRoute.Way.SERVER -> "Sends through the server."
            ways.first() == MessageRoute.Way.MESH_DIRECT -> "Sends over the mesh radio, straight to this device."
            else -> "Sends over the mesh radio. A device that also reaches the server passes it on."
        }
    }

    // What the Mesh Node panel was built from.
    private var nodeShown = ""

    private fun nodeLook(): String = a.selectedNode?.let { num -> MeshRadio.state.nodes[num]?.let {
        "$it|" + nodeDetail(it) + (num in MeshShare.fieldmapNodes) + MeshRadio.traces[num]?.let { t -> t::class.simpleName + t.at } + MeshRadio.traceWait()
    } }.orEmpty()

    /** A trace route's way, each radio by its name with the signal it heard it at: "Hilltop (6.5 dB) → Target (-3.0 dB)". */
    private fun traceText(way: List<Pair<Int, Float?>>, last: String): String = way.mapIndexed { i, (num, snr) ->
        val name = if (i == way.lastIndex) last else MeshRadio.state.nodes[num]?.name ?: RadioState.nodeName(num)
        name + (snr?.let { " (%.1f dB)".format(it) } ?: "")
    }.joinToString(" → ")

    /** Another radio, tapped on the map or chosen in Nodes Nearby: who it is, how it is heard, where it says it is. */
    private fun node() {
        nodeShown = nodeLook()
        val n = a.selectedNode?.let { MeshRadio.state.nodes[it] } ?: return add(a.body("This radio is no longer heard.", muted = true, top = 12))
        add(a.body(n.name, top = 12).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        add(a.body(nodeDetail(n), muted = true, top = 4))
        add(a.label("Details"))
        add(a.row("Node ID", RadioState.nodeName(n.num) + if (n.shortName.isNotEmpty()) " · ${n.shortName}" else "", icon = null))
        if (n.model.isNotEmpty()) add(a.row("Model", n.model, icon = null))
        add(a.row("Channel", if (n.num in MeshShare.fieldmapNodes) "Fieldmap (server channel)" else "Public", icon = null))
        add(a.row("Connection", when (n.hops) {
            null -> "Not known"
            0 -> "Direct · SNR ${"%.1f".format(n.snr)} dB"
            1 -> "1 hop"
            else -> "${n.hops} hops"
        }, icon = null))
        add(a.row("Last Heard", Ageing.text(((System.currentTimeMillis() / 1000) - n.heard).toInt().coerceAtLeast(0)), icon = null))
        n.battery?.let { add(a.row("Battery", if (it > 100) "External Power" else "$it %", icon = null)) }
        add(a.label("Trace Route"))
        val wait = MeshRadio.traceWait()
        when (val t = MeshRadio.traces[n.num]) {
            is MeshRadio.Tracing.Waiting -> add(a.body("Tracing… The answer can take up to a minute.", muted = true))
            is MeshRadio.Tracing.NoAnswer -> add(a.body("No answer. The radio may be out of range, or its answer did not come back.", muted = true))
            is MeshRadio.Tracing.Done -> {
                add(a.body("Toward: This radio → " + traceText(t.trace.there, n.name)))
                add(a.body("Back: ${n.name} → " + traceText(t.trace.back, "This radio"), top = 4))
                add(a.mono(clock(ShareJson.time(t.at)), 12f))
            }
            null -> add(a.body("Shows which radios pass packets between this radio and that one, and how strong each link is.", muted = true))
        }
        add(a.button(if (wait > 0) "Trace Route (wait $wait s)" else "Trace Route") { MeshRadio.traceRoute(n.num); build() }
            .apply { enable(wait == 0 && MeshRadio.status == MeshRadio.Status.READY && MeshRadio.traces[n.num] !is MeshRadio.Tracing.Waiting); layoutParams = lp(top = a.dp(8)) })
        add(a.label("Location"))
        if (n.lat == null || n.lon == null) {
            add(a.body("This radio does not report its location.", muted = true))
            return
        }
        add(a.mono(a.coords(n.lat, n.lon), 18f, muted = false))
        a.fromHere(n.lat, n.lon)?.let { add(a.mono(it)) }
        if (n.radius >= 100) add(a.body("Approximate: the radio reports its location only to within about ${distanceText(n.radius)}.", muted = true, top = 6))
        add(a.body("This is the location the radio reports about itself.", muted = true, top = 6))
    }

    private fun distanceText(m: Double): String = if (m >= 1000) "%.1f km".format(m / 1000) else "${m.toInt()} m"

    /** Another radio in a line: how near, how strong, how long ago, on which channel. */
    private fun nodeDetail(n: MeshNode): String = listOfNotNull(
        when (n.hops) { null -> null; 0 -> "Direct · SNR ${"%.0f".format(n.snr)} dB"; 1 -> "1 hop"; else -> "${n.hops} hops" },
        Ageing.text(((System.currentTimeMillis() / 1000) - n.heard).toInt().coerceAtLeast(0)),
        if (n.num in MeshShare.fieldmapNodes) "Fieldmap" else "Public",
    ).joinToString(" · ")

    private fun meshExplain(): String = when (MeshRadio.status) {
        MeshRadio.Status.OFFLINE -> if (!MeshRadio.bluetoothOn) "Bluetooth is off. Turn it on to connect to the radio." else "The radio is off or out of range. Fieldmap connects again when it is near."
        MeshRadio.Status.PAIRING -> "Enter the radio's PIN when Android asks for it. Radios with a screen show the PIN there."
        MeshRadio.Status.CONNECTING -> "Reading the radio's settings."
        MeshRadio.Status.SETTING_UP -> "Writing the server's channel to the radio. The radio restarts, then connects again."
        MeshRadio.Status.NEEDS_REGION -> "The radio has no region yet."
        MeshRadio.Status.NO_CHANNEL -> "Connect to the server once while Location Sharing is enabled on it. Fieldmap then gets the server's private channel and configures the radio."
        MeshRadio.Status.LOCKED -> "The radio is locked with a passphrase. Unlock it in the Meshtastic app, then connect again."
        MeshRadio.Status.READY -> "The radio uses the server's private channel."
        MeshRadio.Status.FAILED -> MeshRadio.problem
        MeshRadio.Status.NONE -> ""
    }

    private fun confirmForgetRadio() {
        AlertDialog.Builder(a)
            .setTitle("Forget Radio?")
            .setMessage("Fieldmap stops using this radio. The radio keeps its settings. To pair it with this device again, remove it in Android's Bluetooth settings first.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ -> MeshRadio.forget() }
            .show()
    }

    /* ---------- server ---------- */

    /** The state of the connection in a few words, for the Server row of the menu. */
    private fun boxState(): String = when {
        a.remoteNow -> "Connected (remote)"
        a.reachable -> "Connected"
        else -> "Offline"
    }

    private fun serverState(): String = when {
        a.prefs.server == null -> "Fieldmap shows the maps of a Fieldmap server.\n\n" +
            "1. Connect this device to the Wi-Fi of the server.\n" +
            "2. Press Find Server, or enter the server address and press Connect.\n\n" +
            "Then download offline areas to use the maps without a connection."
        a.remoteNow -> "Connected (remote) · ${a.prefs.styles.size} map style${if (a.prefs.styles.size == 1) "" else "s"}"
        a.reachable -> "Connected · ${a.prefs.styles.size} map style${if (a.prefs.styles.size == 1) "" else "s"}"
        else -> "Offline. No connection to the server. You can use the offline areas on this device."
    }

    private fun server() {
        serverBusy = false
        serverText = a.body(serverState(), muted = true, top = 12).also(::add)
        add(a.label("Server Address"))
        val address = a.field("For example 192.168.1.10", a.prefs.server?.let(ServerAddress::short).orEmpty(), address = true) {}
        val go = { connect(address.text.toString()) }
        address.setOnEditorActionListener { _, _, _ -> go(); true }
        add(address)
        add(a.buttons(a.button("Find Server") { find() }, a.button("Connect", Kind.PRIMARY) { go() }))
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
        box.addView(a.label("Remote Access"))
        if (info?.enabled == false || (info == null && a.remoteSwitchedOff)) {
            box.addView(a.body("Remote connectivity is disabled on the server. To enable it, open Admin > Remote Access on the server in a browser. " +
                (if (mine != null) "This device stays paired." else "You can then pair this device."), muted = true))
            return
        }
        if (mine != null) {
            // The server's own words about remote connections: fresh at home, as last heard away.
            val reach = info?.reach?.ifEmpty { null } ?: mine.reach
            box.addView(a.body(when {
                a.netbirdNow -> "Paired. Connected remotely through NetBird VPN."
                a.remoteNow -> "Paired. Connected remotely."
                a.reachable -> "Paired. Remote connection available."
                else -> "Paired, but the server can't be reached over the internet. Connect to the server's network once to update its address."
            }, muted = true))
            if (reach.isNotEmpty()) box.addView(a.body("Server status: $reach", muted = true, top = 8))
            box.addView(a.button("Unpair Device", Kind.DANGER) { a.removeRemote(); remoteSection(info) }.apply { layoutParams = lp(top = a.dp(10)) })
            return
        }
        box.addView(a.body("Pair this device to connect to the server over the internet when you are away from its network. " +
            (if (info?.pairingOpen == true) "Pairing mode is active on the server." else "First, open Admin > Devices on the server and press Start Pairing."), muted = true))
        val code = if (info?.pin == true) a.field("Access Code", "", max = 64) { hideKeyboard() }.apply { layoutParams = lp(top = a.dp(10)) }.also(box::addView) else null
        val said = a.body("", top = 8).apply { setTextColor(a.color(R.color.warn)); visibility = View.GONE }
        val button = a.button("Pair Device", Kind.PRIMARY) {}
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
        if (ServerAddress.candidates(typed).isEmpty()) return failed("Enter the server address. For example: 192.168.1.10.")
        busy("Connecting to server…")
        a.scope.launch {
            val found = TileServer.connect(typed)
            if (screen != Screen.SERVER) return@launch
            if (found == null) failed("No server responded at this address. Check the address and make sure this device is on the server's network.")
            else { serverBusy = false; a.useServer(found) }
        }
    }

    private fun find() {
        if (serverBusy) return
        hideKeyboard()
        busy("Searching for server…")
        a.scope.launch {
            val result = Finder.find()
            if (screen != Screen.SERVER) return@launch
            when {
                !result.searched -> failed("This device is not on a Wi-Fi network. Connect to the server's network, or enter the server address.")
                result.found.isEmpty() -> failed("No server found on this network. Enter the server address.")
                result.found.size == 1 -> { serverBusy = false; a.useServer(result.found[0]) }
                else -> {
                    serverBusy = false
                    serverText?.text = "Found ${result.found.size} servers. Select one."
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
