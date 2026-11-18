package io.github.sentinel6systems.fieldmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import io.github.sentinel6systems.fieldmap.R
import io.github.sentinel6systems.fieldmap.data.Drawings
import io.github.sentinel6systems.fieldmap.data.Marker
import io.github.sentinel6systems.fieldmap.data.MarkerStore
import io.github.sentinel6systems.fieldmap.databinding.ActivityMainBinding
import io.github.sentinel6systems.fieldmap.geo.GpsEngine
import io.github.sentinel6systems.fieldmap.geo.Utm
import io.github.sentinel6systems.fieldmap.offline.Areas
import io.github.sentinel6systems.fieldmap.offline.Box
import io.github.sentinel6systems.fieldmap.offline.SavedArea
import io.github.sentinel6systems.fieldmap.offline.bytesText
import io.github.sentinel6systems.fieldmap.server.Found
import io.github.sentinel6systems.fieldmap.server.MapStyle
import io.github.sentinel6systems.fieldmap.server.Net
import io.github.sentinel6systems.fieldmap.server.TileServer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/** How the map follows the phone's position. */
enum class Follow { FREE, NORTH, HEADING }

/**
 * The one screen of the app: the map of a fieldmap-server box (or any TileServer GL), the
 * phone's position on it, coloured dot markers, and saved areas that work with no network.
 * The panels over the map are built in Panels.kt.
 */
class MainActivity : AppCompatActivity() {
    internal lateinit var b: ActivityMainBinding
    internal lateinit var prefs: Prefs
    internal lateinit var markers: MarkerStore
    internal lateinit var areas: Areas
    private lateinit var panels: Panels
    internal val scope = MainScope()
    internal var map: MapLibreMap? = null
    private var style: Style? = null

    /** The server answered the last time it was asked. */
    internal var reachable = false
        private set
    private var probing = false
    private var viewedRefreshed = false
    private var pendingStyle: String? = null        // the style address being loaded
    private var shownStyle: String? = null          // the style address on screen; null is the blank map
    private val missingStyles = HashSet<String>()   // addresses that turned out not to be on the phone

    internal var follow = Follow.FREE
        private set
    private var locationOn = false
    private var headingByCourse = false
    private var zoomOnFix = false
    /** The marker whose panel is open. */
    internal var selected: String? = null
        private set

    private val ticker = Handler(Looper.getMainLooper())
    private var ticks = 0
    private var insets = androidx.core.graphics.Insets.NONE
    private var keyboard = 0
    private var lastAreasDraw = 0L
    private var mapPadding = DoubleArray(4)         // left, top, right, bottom

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Until the server has answered, the map reads only what is on the phone.
        MapLibre.setConnected(false)
        Net.start(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, b.root).isAppearanceLightStatusBars = !night

        prefs = Prefs(this)
        markers = MarkerStore(File(filesDir, "markers.geojson"))
        areas = Areas(this)
        panels = Panels(this)

        b.map.onCreate(savedInstanceState)
        b.map.addOnDidFailLoadingMapListener { pendingStyle?.let(::styleMissing) }
        b.map.getMapAsync(::setUpMap)
        @SuppressLint("ClickableViewAccessibility")       // watches only; the map still gets every touch
        b.map.setOnTouchListener { _, e -> watchHold(e); false }

        b.zoomIn.setOnClickListener { zoomBy(1.0) }
        b.zoomOut.setOnClickListener { zoomBy(-1.0) }
        b.position.setOnClickListener { positionPressed() }
        b.menu.setOnClickListener { panels.show(Screen.MENU) }
        b.panelClose.setOnClickListener { panels.show(Screen.NONE) }
        b.panelBack.setOnClickListener { panels.back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (panels.screen == Screen.NONE) finish() else panels.back() }
        })

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, wi ->
            insets = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            keyboard = wi.getInsets(WindowInsetsCompat.Type.ime()).bottom
            place()
            wi
        }
        b.root.addOnLayoutChangeListener { _, l, t, r, bottom, ol, ot, or, ob -> if (r - l != or - ol || bottom - t != ob - ot) b.root.post { place() } }
        b.panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutMap() }
        b.top.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutMap() }

        areas.onChange = ::areasChanged
        areas.onSaved = { toast("Saved: ${it.name} (${bytesText(it.bytes)})") }
        areas.load()

        followChanged()
        updateStatus()
        if (prefs.server == null) panels.show(Screen.SERVER)
    }

    /* ---------- the map ---------- */

    private fun setUpMap(m: MapLibreMap) {
        map = m
        // North is up unless the position button says otherwise: no turning or tilting by hand.
        m.uiSettings.apply {
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
        }
        m.setMaxZoomPreference(20.0)
        prefs.camera?.let { (lat, lon, zoom) -> m.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build() }
        m.addOnCameraMoveListener { updateReadout() }
        m.addOnCameraIdleListener {
            // After leaving heading-up by dragging, the map turns back to north.
            if (follow == Follow.FREE && m.cameraPosition.bearing != 0.0) m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
            saveCamera()
            panels.cameraIdle()
        }
        m.addOnMapClickListener { tapped(it) }
        layoutMap()
        showStyle()
        probe()
    }

    /** The style the person chose, else the screen style of fieldmap-server, else the first. */
    internal fun currentStyle(): MapStyle? = prefs.styles.let { all ->
        all.firstOrNull { it.id == prefs.styleId } ?: all.firstOrNull { it.id == "topo-light" } ?: all.firstOrNull()
    }

    /** The name of the style on screen. */
    internal fun shownStyleName(): String? =
        prefs.styles.firstOrNull { it.url == shownStyle }?.name ?: areas.list.firstOrNull { it.styleUrl == shownStyle }?.styleName

    /** Loads a style: the chosen one, or the one at `url`; null is the blank map. */
    internal fun showStyle(url: String? = currentStyle()?.url) {
        val m = map ?: return
        notice(null)
        if (url == null) {
            pendingStyle = null
            shownStyle = null
            m.setStyle(Style.Builder().fromJson(BLANK.replace("#000000", hex(R.color.panel2))), ::styleLoaded)
            return
        }
        pendingStyle = url
        m.setStyle(Style.Builder().fromUri(url)) { st ->
            if (pendingStyle == url) { pendingStyle = null; shownStyle = url; missingStyles.clear() }
            styleLoaded(st)
        }
        // With no server in reach a style that is not on the phone never arrives: MapLibre
        // waits for the network without a word. What is on the phone loads at once.
        if (!reachable) b.root.postDelayed({ if (!reachable) styleMissing(url) }, 2500)
    }

    /** A style could not be loaded: show a saved area's style instead, or say what is missing. */
    private fun styleMissing(url: String) {
        if (pendingStyle != url) return
        if (reachable) { pendingStyle = null; showStyle(null); notice("The server did not give this map style. Choose another under Menu, Map style."); return }
        missingStyles += url
        val centre = map?.cameraPosition?.target
        val saved = areas.list.filter { it.styleUrl !in missingStyles }
        val other = saved.firstOrNull { centre != null && it.bounds?.contains(centre) == true } ?: saved.firstOrNull()
        if (other != null) {
            val name = prefs.styles.firstOrNull { it.url == url }?.name ?: "This map style"
            toast("$name is not saved on this phone. Showing ${other.styleName.ifEmpty { "a saved map" }}.")
            showStyle(other.styleUrl)
            return
        }
        pendingStyle = null
        showStyle(null)
        notice(
            if (areas.list.isEmpty()) "The server is out of reach and no map area is saved on this phone yet.\n\nOn the server's network: Menu, Saved areas, Save a new area."
            else "This map style is not saved on the phone. Choose a saved area under Menu, Saved areas.",
        )
    }

    private fun styleLoaded(st: Style) {
        style = st
        // Our layers go under the position symbol when it is already there.
        val below = st.layers.firstOrNull { it.id.contains("location") }?.id
        fun add(layer: Layer) = if (below != null) st.addLayerBelow(layer, below) else st.addLayer(layer)

        st.addSource(GeoJsonSource(AREAS, areasOnMap()))
        st.addSource(GeoJsonSource(MARKERS, markersOnMap()))
        add(LineLayer("fm-areas-case", AREAS).withProperties(lineColor(Color.WHITE), lineWidth(4.5f), lineOpacity(0.7f)))
        add(LineLayer("fm-areas", AREAS).withProperties(lineColor("#3f7d1c"), lineWidth(2f), lineDasharray(arrayOf(3f, 2f))))
        // The dots of the print tool: the colour, a white edge; a thin dark line keeps a white
        // dot visible on a white map.
        add(CircleLayer("fm-markers-ring", MARKERS).withProperties(circleRadius(12.5f), circleColor("#111111"), circleOpacity(0.55f)))
        add(CircleLayer(MARKER_DOTS, MARKERS).withProperties(circleRadius(9f), circleColor(Expression.get("color")), circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f)))
        add(
            CircleLayer(MARKER_SELECTED, MARKERS).withProperties(circleRadius(17f), circleOpacity(0f), circleStrokeColor(color(R.color.sig)), circleStrokeWidth(3f))
                .withFilter(selectedFilter()),
        )
        // Names need a font the style has (and so one that is saved with an area).
        val fonts = st.layers.mapNotNull { (it as? SymbolLayer)?.textFont?.value }.filter { it.isNotEmpty() }
        val font = fonts.firstOrNull { f -> f[0].contains("bold", true) && !f[0].contains("italic", true) } ?: fonts.firstOrNull()
        if (font != null) add(
            SymbolLayer("fm-markers-name", MARKERS).withProperties(
                textField(Expression.get("label")), textFont(font), textSize(15f), textOffset(arrayOf(1.1f, 0f)),
                textAnchor(Property.TEXT_ANCHOR_LEFT), textAllowOverlap(true),
                textColor("#111111"), textHaloColor(Color.WHITE), textHaloWidth(1.8f),
            ),
        )
        startLocation(st)
        updateReadout()
    }

    private fun selectedFilter() = Expression.eq(Expression.get("id"), Expression.literal(selected ?: ""))

    private fun markersOnMap(): FeatureCollection = FeatureCollection.fromFeatures(
        markers.markers.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
                addStringProperty("id", m.id)
                addStringProperty("label", m.label)
                addStringProperty("color", m.color)
            }
        },
    )

    private fun areasOnMap(): FeatureCollection = FeatureCollection.fromFeatures(
        areas.list.mapNotNull { it.bounds }.map { r ->
            val ring = listOf(
                Point.fromLngLat(r.longitudeWest, r.latitudeSouth), Point.fromLngLat(r.longitudeEast, r.latitudeSouth), Point.fromLngLat(r.longitudeEast, r.latitudeNorth),
                Point.fromLngLat(r.longitudeWest, r.latitudeNorth), Point.fromLngLat(r.longitudeWest, r.latitudeSouth),
            )
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        },
    )

    private fun hex(colorRes: Int) = String.format("#%06x", color(colorRes) and 0xffffff)

    /* ---------- where things sit ---------- */

    /** Feet and miles where they are used on the road, else metres and kilometres. */
    private val imperial = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

    private val sideways get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Puts the strip, the buttons and the panel inside the screen's edges and cut-outs. */
    private fun place() {
        val m = dp(12)
        b.top.setPadding(insets.left + dp(14), insets.top + dp(6), insets.right + dp(12), dp(9))
        b.menu.updateLayoutParams<FrameLayout.LayoutParams> { leftMargin = insets.left + m; bottomMargin = insets.bottom + m }
        b.foot.updateLayoutParams<FrameLayout.LayoutParams> {
            leftMargin = insets.left + m + resources.getDimensionPixelSize(R.dimen.map_button) + m
            bottomMargin = insets.bottom + m
        }
        // Upright, the buttons are a column up the right edge; on its side, a row along the bottom.
        b.controls.orientation = if (sideways) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
        b.controls.gravity = Gravity.BOTTOM or Gravity.END
        for ((i, button) in listOf(b.zoomIn, b.zoomOut, b.position).withIndex()) {
            val space = if (i == 0) 0 else if (i == 2) dp(22) else dp(10)
            button.updateLayoutParams<android.widget.LinearLayout.LayoutParams> { topMargin = if (sideways) 0 else space; leftMargin = if (sideways) space else 0 }
        }
        // Upright, the panel covers the lower part; on its side, the right part.
        b.panel.updateLayoutParams<FrameLayout.LayoutParams> {
            width = if (sideways) dp(380) else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (sideways) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            gravity = if (sideways) Gravity.END else Gravity.BOTTOM
        }
        b.panel.maxHeight = if (sideways) Int.MAX_VALUE else ((b.root.height - insets.top) * 0.62f).toInt().coerceAtLeast(dp(260)) + keyboard
        b.panel.setPadding(0, if (sideways) insets.top else 0, if (sideways) insets.right else 0, maxOf(insets.bottom, keyboard))
        layoutMap()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        b.root.post { place() }
    }

    /**
     * Tells the map which part of it is in view, so that its centre (the crosshair, the
     * followed position) is the centre of what can be seen, and sizes the frame to it.
     */
    internal fun layoutMap() {
        val w = b.root.width
        val h = b.root.height
        if (w == 0 || h == 0) return
        val top = b.top.bottom
        val open = b.panel.visibility == View.VISIBLE
        val right = if (open && sideways) w - b.panel.left else 0
        // The keyboard does not move the map.
        val bottom = if (open && !sideways) (h - b.panel.top - (keyboard - insets.bottom).coerceAtLeast(0)).coerceAtLeast(0) else 0
        b.frame.setCovered(0, top, right, bottom)
        val dx = -right / 2f
        val dy = (top - bottom) / 2f
        b.crosshair.translationX = dx
        b.crosshair.translationY = dy
        b.notice.translationX = dx
        b.toast.translationY = top + dp(10).toFloat()
        b.toast.translationX = dx
        // The zoom buttons stay on the map beside or above an open panel, when there is room.
        val gap = dp(12)
        val beside = if (open && sideways) right + gap else insets.right + gap
        val above = if (open && !sideways) h - b.panel.top + gap else insets.bottom + gap
        val lp = b.controls.layoutParams as FrameLayout.LayoutParams
        if (lp.rightMargin != beside || lp.bottomMargin != above) b.controls.updateLayoutParams<FrameLayout.LayoutParams> { rightMargin = beside; bottomMargin = above }
        val room = h - above - top >= 2 * resources.getDimensionPixelSize(R.dimen.map_button) + 3 * gap
        b.controls.visibility = if (room) View.VISIBLE else View.INVISIBLE
        val m = map ?: return
        val padding = doubleArrayOf(0.0, top.toDouble(), right.toDouble(), bottom.toDouble())
        if (!padding.contentEquals(mapPadding)) {
            mapPadding = padding
            // While the map follows the phone, moving the camera ourselves would let go of it.
            if (follow != Follow.FREE && locationOn) m.locationComponent.paddingWhileTracking(padding)
            else m.moveCamera(CameraUpdateFactory.paddingTo(padding))
        }
    }

    /** The area inside the frame. */
    internal fun frameBox(): Box? {
        val m = map ?: return null
        val r = b.frame.rect
        if (r.width() <= 0 || r.height() <= 0) return null
        val nw = m.projection.fromScreenLocation(PointF(r.left, r.top))
        val se = m.projection.fromScreenLocation(PointF(r.right, r.bottom))
        if (se.longitude <= nw.longitude || nw.latitude <= se.latitude) return null
        return Box(nw.longitude, se.latitude, se.longitude, nw.latitude)
    }

    /** The next start opens where the map was. */
    private fun saveCamera() {
        map?.cameraPosition?.let { c -> c.target?.let { prefs.camera = Triple(it.latitude, it.longitude, c.zoom) } }
    }

    private fun zoomBy(steps: Double) {
        val m = map ?: return
        val zoom = (m.cameraPosition.zoom + steps).coerceIn(m.minZoomLevel, m.maxZoomLevel)
        if (follow != Follow.FREE && locationOn) m.locationComponent.zoomWhileTracking(zoom, 250)
        else m.animateCamera(CameraUpdateFactory.zoomTo(zoom), 250)
    }

    /* ---------- the server ---------- */

    /** Asks the server for its styles: this is how the app knows whether it is in reach. */
    internal fun probe() {
        val base = prefs.server
        if (base == null || probing) return
        probing = true
        scope.launch {
            val styles = Net.reach { TileServer.styles(base) }
            probing = false
            if (base != prefs.server) return@launch
            if (!styles.isNullOrEmpty()) prefs.styles = styles
            setReachable(styles != null)
        }
    }

    private fun setReachable(on: Boolean) {
        val was = reachable
        reachable = on
        // Out of reach, MapLibre is told so: it then draws from the phone at once and stops asking.
        MapLibre.setConnected(on)
        if (on == was) return
        updateStatus()
        if (on) {
            if (!viewedRefreshed) { viewedRefreshed = true; areas.refreshViewed() }
            if (pendingStyle == null && shownStyle != currentStyle()?.url) showStyle()
        }
        panels.refresh()
    }

    /** A server answered at the address typed or found: use it from now on. */
    internal fun useServer(found: Found) {
        val first = prefs.server != found.base
        prefs.server = found.base
        prefs.styles = found.styles
        setReachable(true)
        updateStatus()
        showStyle()
        panels.show(Screen.NONE)
        val style = currentStyle()
        if (style == null) { notice("This server has no map styles."); return }
        scope.launch {
            val info = TileServer.info(style.url) ?: return@launch
            if (info.empty) notice("No maps on this server yet. Build them on its Map builder page.")
            // A new server: start on the area its base map covers.
            val c = info.coverage
            if (first && c != null) b.root.post { layoutMap(); map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(c.north, c.east, c.south, c.west), dp(24))) }
        }
    }

    internal fun chooseStyle(style: MapStyle) {
        prefs.styleId = style.id
        showStyle()
        panels.show(Screen.NONE)
    }

    private fun updateStatus() {
        val saving = areas.downloading
        val (text, colour) = when {
            saving != null -> "Saving ${saving.percent}%" to R.color.amber
            prefs.server == null -> "No server" to R.color.muted
            reachable -> "Connected" to R.color.sig
            else -> "Offline" to R.color.muted
        }
        b.status.text = text
        b.status.setTextColor(color(colour))
    }

    /* ---------- saved areas ---------- */

    private fun areasChanged() {
        updateStatus()
        keepScreenOn()
        // A download reports many times a second: draw at most four times.
        val now = System.currentTimeMillis()
        if (now - lastAreasDraw < 250 && areas.downloading != null) return
        lastAreasDraw = now
        style?.getSourceAs<GeoJsonSource>(AREAS)?.setGeoJson(areasOnMap())
        panels.refresh()
    }

    /** Goes to a saved area, in the style it was saved with. */
    internal fun showArea(area: SavedArea) {
        panels.show(Screen.NONE)
        setFollow(Follow.FREE)
        prefs.styles.firstOrNull { it.url == area.styleUrl }?.let { prefs.styleId = it.id }
        if (shownStyle != area.styleUrl) showStyle(area.styleUrl)
        area.bounds?.let { map?.animateCamera(CameraUpdateFactory.newLatLngBounds(it, dp(28)), 600) }
    }

    /** Shows the frame for choosing an area: north up, flat. */
    internal fun startFraming() {
        setFollow(Follow.FREE)
        map?.let { if (it.cameraPosition.bearing != 0.0) it.moveCamera(CameraUpdateFactory.bearingTo(0.0)) }
        panels.show(Screen.SAVE)
    }

    internal fun saveArea(name: String, style: MapStyle, box: Box) {
        areas.save(name, style, LatLngBounds.from(box.north, box.east, box.south, box.west), resources.displayMetrics.density) {
            toast("The area could not be saved: $it")
        }
        panels.show(Screen.AREAS)
    }

    /* ---------- markers ---------- */

    private fun mark(lat: Double, lon: Double) {
        val marker = markers.add(lat, lon, prefs.color)
        markersChanged()
        openMarker(marker.id, fromList = false)
    }

    // Holding a finger on the map for two seconds marks the spot under it: the one way to make
    // a marker. Long enough not to happen by accident while moving the map; a ring around the
    // finger shows it filling up.
    private var holdAt: PointF? = null
    private val holdDone = Runnable {
        val at = holdAt
        endHold()
        val m = map ?: return@Runnable
        val spot = at?.let(m.projection::fromScreenLocation) ?: return@Runnable
        b.map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // A finger on the position symbol means "where I am": take the position itself.
        val here = lastLocation()?.let { LatLng(it.latitude, it.longitude) }
        val onMe = here != null && m.projection.toScreenLocation(here).let { hypot(it.x - at.x, it.y - at.y) < dp(32) }
        if (onMe && here != null) mark(here.latitude, here.longitude) else mark(spot.latitude, spot.longitude)
    }

    private fun watchHold(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (map != null && panels.screen != Screen.SAVE) {
                holdAt = PointF(e.x, e.y)
                b.hold.start(e.x, e.y, HOLD_MS)
                b.map.postDelayed(holdDone, HOLD_MS)
            }
            // A finger does not keep perfectly still for two seconds; one that travels is moving the map.
            MotionEvent.ACTION_MOVE -> holdAt?.let { if (hypot(e.x - it.x, e.y - it.y) > dp(14)) endHold() }
            else -> endHold()                       // lifted, or a second finger
        }
    }

    private fun endHold() {
        holdAt = null
        b.map.removeCallbacks(holdDone)
        b.hold.stop()
    }

    private fun tapped(at: LatLng): Boolean {
        val m = map ?: return false
        if (panels.screen == Screen.SAVE) return false
        val p = m.projection.toScreenLocation(at)
        val reach = dp(26).toFloat()                 // a gloved finger is not exact
        val hits = m.queryRenderedFeatures(RectF(p.x - reach, p.y - reach, p.x + reach, p.y + reach), MARKER_DOTS)
        val id = hits.firstOrNull()?.getStringProperty("id") ?: return false
        openMarker(id, fromList = false)
        return true
    }

    internal fun openMarker(id: String, fromList: Boolean) {
        val marker = markers.get(id) ?: return
        select(id)
        panels.showMarker(fromList)
        // Bring it into view, unless the map is following the phone.
        if (follow == Follow.FREE) b.root.post { map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(marker.lat, marker.lon)), 400) }
    }

    internal fun select(id: String?) {
        selected = id
        style?.getLayerAs<CircleLayer>(MARKER_SELECTED)?.setFilter(selectedFilter())
    }

    internal fun updateMarker(marker: Marker) {
        markers.update(marker)
        prefs.color = marker.color
        markersChanged()
    }

    internal fun deleteMarker(id: String) {
        markers.delete(id)
        select(null)
        markersChanged()
    }

    internal fun markersChanged() {
        style?.getSourceAs<GeoJsonSource>(MARKERS)?.setGeoJson(markersOnMap())
    }

    /** Distance and true bearing from the phone to a place, in words; null without a position. */
    internal fun fromHere(lat: Double, lon: Double): String? {
        val here = lastLocation() ?: return null
        val out = FloatArray(2)
        Location.distanceBetween(here.latitude, here.longitude, lat, lon, out)
        if (out[0] < 5) return "Where you are"
        val bearing = ((out[1] + 360) % 360).toInt()
        val far = if (imperial) {
            val miles = out[0] / 1609.344
            if (miles < 0.1) "${(out[0] / 0.3048).toInt()} ft" else String.format(Locale.US, "%.1f mi", miles)
        } else {
            if (out[0] < 1000) "${out[0].toInt()} m" else String.format(Locale.US, "%.1f km", out[0] / 1000)
        }
        return String.format(Locale.US, "%s from you, bearing %03d° true", far, bearing)
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(markers.toGeoJson().toByteArray()) }
            toast("Markers saved to the file.")
        } catch (e: Exception) {
            toast("The file could not be written.")
        }
    }

    private val loadFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val loaded = Drawings.fromGeoJson(text, prefs.color, markers::newId)
            val added = markers.addAll(loaded.markers)
            markersChanged()
            panels.refresh()
            toast(
                "Added $added marker${if (added == 1) "" else "s"}." +
                    (if (loaded.markers.size > added) " ${loaded.markers.size - added} were already here." else "") +
                    (if (loaded.skipped > 0) " ${loaded.skipped} other drawing${if (loaded.skipped == 1) "" else "s"} (arrows, shapes) left out." else ""),
            )
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "The file could not be read.")
        } catch (e: Exception) {
            toast("The file could not be read.")
        }
    }

    internal fun saveMarkersFile() = saveFile.launch("fieldmap-markers-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.geojson")
    internal fun loadMarkersFile() = loadFile.launch(arrayOf("*/*"))

    /* ---------- the phone's position ---------- */

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            style?.let(::startLocation)
            if (locationOn) setFollow(Follow.NORTH)
        } else {
            toast("Without the location permission the map cannot show where you are.")
        }
    }

    private fun mayLocate() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun startLocation(st: Style) {
        if (!mayLocate()) return
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) {
            val options = LocationComponentOptions.builder(this).pulseEnabled(false).accuracyAlpha(0.18f).build()
            val request = LocationEngineRequest.Builder(1000).setFastestInterval(500).setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY).build()
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, st).locationComponentOptions(options)
                    .useDefaultLocationEngine(false).locationEngine(GpsEngine(this)).locationEngineRequest(request).build(),
            )
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                // Dragging the map lets go of the position.
                override fun onCameraTrackingDismissed() { follow = Follow.FREE; followChanged() }
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
        locationOn = true
    }

    private fun lastLocation(): Location? = if (locationOn) try { map?.locationComponent?.lastKnownLocation } catch (e: SecurityException) { null } else null

    /** One button: follow with north up, then with the direction of travel up, and back. */
    private fun positionPressed() {
        if (!locationOn) {
            if (mayLocate()) style?.let(::startLocation)
            else { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return }
        }
        if (locationOn) setFollow(if (follow == Follow.NORTH) Follow.HEADING else Follow.NORTH)
    }

    internal fun setFollow(mode: Follow) {
        val m = map
        if (m != null && locationOn) {
            val lc = m.locationComponent
            // From far out, following starts at a walking and driving zoom.
            val zoom = if (m.cameraPosition.zoom < 12) 15.0 else null
            // Without a position yet there is nothing to zoom to: do it when the first one comes.
            zoomOnFix = mode != Follow.FREE && zoom != null && lastLocation() == null
            when (mode) {
                Follow.FREE -> lc.cameraMode = CameraMode.NONE
                Follow.NORTH -> lc.setCameraMode(CameraMode.TRACKING, 600, zoom, 0.0, null, null)
                Follow.HEADING -> lc.setCameraMode(if (headingByCourse) CameraMode.TRACKING_GPS else CameraMode.TRACKING_COMPASS, 600, zoom, null, null, null)
            }
        }
        follow = if (locationOn) mode else Follow.FREE
        followChanged()
        layoutMap()
    }

    private fun followChanged() {
        when (follow) {
            Follow.FREE -> b.position.set(R.drawable.ic_locate, getString(R.string.follow), false)
            Follow.NORTH -> b.position.set(R.drawable.ic_follow, getString(R.string.north_up), true)
            Follow.HEADING -> b.position.set(R.drawable.ic_heading, getString(R.string.head_up), true)
        }
        showCrosshair()
        keepScreenOn()
        updateReadout()
    }

    /** The crosshair marks the map's centre whenever that is not the phone's own position. */
    internal fun showCrosshair() {
        b.crosshair.visibility = if (follow == Follow.FREE && panels.screen != Screen.SAVE) View.VISIBLE else View.GONE
    }

    /**
     * Heading-up turns the map by the direction of travel when moving at more than a brisk
     * walk (a phone in a car mount has no useful compass), and by the compass when slower.
     */
    private fun chooseHeadingSource() {
        val loc = lastLocation() ?: return
        if (zoomOnFix) {
            zoomOnFix = false
            if (follow != Follow.FREE) map?.locationComponent?.zoomWhileTracking(15.0, 600)
        }
        val byCourse = if (headingByCourse) loc.speed > 1.5f else loc.speed > 3f && loc.hasBearing()
        if (byCourse == headingByCourse) return
        headingByCourse = byCourse
        val lc = map?.locationComponent ?: return
        lc.renderMode = if (byCourse) RenderMode.GPS else RenderMode.COMPASS
        if (follow == Follow.HEADING) lc.cameraMode = if (byCourse) CameraMode.TRACKING_GPS else CameraMode.TRACKING_COMPASS
    }

    /** The strip at the top: the grid reference and latitude/longitude of the phone, or of the crosshair. */
    private fun updateReadout() {
        val m = map ?: return
        val here = lastLocation()
        if (follow != Follow.FREE) {
            if (here == null) { b.grid.text = "Waiting for GPS"; b.latlon.text = "No position yet"; return }
            b.grid.text = Utm.toMgrs(here.latitude, here.longitude) ?: "Outside the MGRS grid"
            // Rounded up, never "±0": feet where the scale bar is in feet and miles.
            val accuracy = when {
                !here.hasAccuracy() -> ""
                imperial -> " ±${kotlin.math.ceil(here.accuracy / 0.3048).toInt().coerceAtLeast(1)} ft"
                else -> " ±${kotlin.math.ceil(here.accuracy.toDouble()).toInt().coerceAtLeast(1)} m"
            }
            b.latlon.text = String.format(Locale.US, "YOU%s · %.5f, %.5f", accuracy, here.latitude, here.longitude)
        } else {
            val c = m.cameraPosition.target ?: return
            b.grid.text = Utm.toMgrs(c.latitude, c.longitude) ?: "Outside the MGRS grid"
            b.latlon.text = String.format(Locale.US, "CROSSHAIR · %.5f, %.5f", c.latitude, c.longitude)
        }
        // MapLibre counts in density-independent pixels; the bar is drawn in real ones.
        b.scale.setMetersPerPixel(m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 0.0) / resources.displayMetrics.density)
    }

    /* ---------- small things ---------- */

    /** A card in the middle of the map; null takes it away. */
    internal fun notice(text: String?) {
        b.notice.text = text
        b.notice.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private val hideToast = Runnable { b.toast.visibility = View.GONE }

    /** A line under the top strip that goes away by itself. */
    internal fun toast(text: String) {
        b.toast.text = text
        b.toast.visibility = View.VISIBLE
        b.toast.removeCallbacks(hideToast)
        b.toast.postDelayed(hideToast, 4500)
    }

    /** The screen stays on while the map follows the phone and while an area is downloading. */
    private fun keepScreenOn() {
        if (follow != Follow.FREE || areas.downloading != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (follow != Follow.FREE) updateReadout()
            chooseHeadingSource()
            // Every quarter of a minute, and more often right after opening while nothing answers.
            if (ticks % 15 == 0 || (ticks < 6 && !reachable)) probe()
            ticks++
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onStart() { super.onStart(); b.map.onStart() }

    override fun onResume() {
        super.onResume()
        b.map.onResume()
        ticks = 0
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        saveCamera()
        b.map.onPause()
        super.onPause()
    }

    override fun onStop() { b.map.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); b.map.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); b.map.onSaveInstanceState(outState) }

    override fun onDestroy() {
        scope.cancel()
        areas.onChange = {}
        areas.onSaved = {}
        b.map.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val AREAS = "fm-areas"
        const val MARKERS = "fm-markers"
        const val MARKER_DOTS = "fm-markers-dot"
        const val MARKER_SELECTED = "fm-markers-selected"
        /** How long a finger rests on the map to mark the spot. */
        const val HOLD_MS = 2000L
        /** The map before any server is known: one plain colour. */
        const val BLANK = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#000000"}}]}"""
    }
}
