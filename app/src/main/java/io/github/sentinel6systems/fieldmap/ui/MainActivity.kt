package io.github.sentinel6systems.fieldmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
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
import io.github.sentinel6systems.fieldmap.remote.Enrolment
import io.github.sentinel6systems.fieldmap.remote.RemoteAccess
import io.github.sentinel6systems.fieldmap.server.Found
import io.github.sentinel6systems.fieldmap.server.Http
import io.github.sentinel6systems.fieldmap.server.MapStyle
import io.github.sentinel6systems.fieldmap.server.Net
import io.github.sentinel6systems.fieldmap.server.Route
import io.github.sentinel6systems.fieldmap.server.TileServer
import io.github.sentinel6systems.fieldmap.share.Ageing
import io.github.sentinel6systems.fieldmap.share.Fix
import io.github.sentinel6systems.fieldmap.share.ShareJson
import io.github.sentinel6systems.fieldmap.share.ShareService
import io.github.sentinel6systems.fieldmap.share.toFix
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
import org.maplibre.android.module.http.HttpRequestUtil
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
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
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
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOpacity
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
 * The panels over the map are built in Panels.kt; sharing with other phones runs in Sharing.kt.
 */
class MainActivity : AppCompatActivity() {
    internal lateinit var b: ActivityMainBinding
    internal lateinit var prefs: Prefs
    internal lateinit var markers: MarkerStore
    internal lateinit var areas: Areas
    internal lateinit var sharing: Sharing
        private set
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
    /** The other phone whose panel is open. */
    internal var selectedPeer: String? = null
        private set

    private val ticker = Handler(Looper.getMainLooper())
    private var ticks = 0
    private var insets = androidx.core.graphics.Insets.NONE
    private var keyboard = 0
    private var lastAreasDraw = 0L
    private var mapPadding = DoubleArray(4)         // left, top, right, bottom
    private var remoteChecked = 0L
    /** The box's gateway said remote access is switched off (on its pages: Admin, Remote access). */
    internal var remoteSwitchedOff = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Until the server has answered, the map reads only what is on the phone.
        MapLibre.setConnected(false)
        // The map sends its requests through the app's own client, and so through the remote link too.
        HttpRequestUtil.setOkHttpClient(Http.client)
        Net.start(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, b.root).isAppearanceLightStatusBars = !night

        prefs = Prefs(this)
        applyRemote()
        markers = MarkerStore(File(filesDir, "markers.geojson"))
        areas = Areas(this)
        sharing = Sharing(this)
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
            override fun handleOnBackPressed() {
                when {
                    panels.screen != Screen.NONE -> panels.back()
                    // While the position is shared, Back leaves the app as Home does: sharing goes on.
                    ShareService.running -> moveTaskToBack(true)
                    else -> finish()
                }
            }
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
        areas.onSaved = { toast("Map area saved: ${it.name} (${bytesText(it.bytes)})") }
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
        if (reachable) { pendingStyle = null; showStyle(null); notice("The box does not have this map style. Choose a different style in Menu › Map style."); return }
        missingStyles += url
        val centre = map?.cameraPosition?.target
        val saved = areas.list.filter { it.styleUrl !in missingStyles }
        val other = saved.firstOrNull { centre != null && it.bounds?.contains(centre) == true } ?: saved.firstOrNull()
        if (other != null) {
            val name = prefs.styles.firstOrNull { it.url == url }?.name ?: "This map style"
            toast("$name is not on this device. The map shows ${other.styleName.ifEmpty { "a map area" }}.")
            showStyle(other.styleUrl)
            return
        }
        pendingStyle = null
        showStyle(null)
        notice(
            if (areas.list.isEmpty()) "There is no connection to the box. This device has no map areas.\n\nConnect to the box. Then go to Menu › Map areas › Save a map area."
            else "This map style is not on this device. Choose a map area in Menu › Map areas.",
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
        // The other phones: a diamond with the name beside it, so that nobody takes one for a
        // marker or for the phone's own position. Grey and fading once it stops being heard from.
        st.addImage(PEER_FRESH, peerSymbol(Color.parseColor("#00b4d8")))
        st.addImage(PEER_OLD, peerSymbol(Color.parseColor("#8a8f8a")))
        st.addSource(GeoJsonSource(PEERS, peersOnMap()))
        add(
            CircleLayer(PEER_SELECTED, PEERS).withProperties(circleRadius(19f), circleOpacity(0f), circleStrokeColor(color(R.color.sig)), circleStrokeWidth(3f))
                .withFilter(peerFilter()),
        )
        add(SymbolLayer(PEER_SYMBOLS, PEERS).withProperties(iconImage(Expression.get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true), iconOpacity(Expression.get("opacity"))))
        // The name is a layer of its own: letters that are not on the phone must not hide the diamond.
        if (font != null) add(
            SymbolLayer("fm-peers-name", PEERS).withProperties(
                textField(Expression.get("name")), textFont(font), textSize(15f), textOffset(arrayOf(1.2f, 0f)),
                textAnchor(Property.TEXT_ANCHOR_LEFT), textAllowOverlap(true), textIgnorePlacement(true), textOpacity(Expression.get("opacity")),
                // In the diamond's own colour, darker: a person's name is not one of the map's place names.
                textColor("#004e63"), textHaloColor(Color.WHITE), textHaloWidth(2.2f),
            ),
        )
        startLocation(st)
        updateReadout()
    }

    /** The symbol of another phone: a diamond in the given colour, white-edged like the markers. */
    private fun peerSymbol(fill: Int): Bitmap {
        val size = dp(30)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = size / 2f
        fun diamond(r: Float, colour: Int) {
            paint.color = colour
            canvas.drawPath(Path().apply { moveTo(c, c - r); lineTo(c + r, c); lineTo(c, c + r); lineTo(c - r, c); close() }, paint)
        }
        diamond(c, 0x8c111111.toInt())
        diamond(c - dp(2), Color.WHITE)
        diamond(c - dp(6), fill)
        return bitmap
    }

    private fun peerFilter() = Expression.eq(Expression.get("id"), Expression.literal(selectedPeer ?: ""))

    private fun peersOnMap(): FeatureCollection = sharing.info.let { info ->
        FeatureCollection.fromFeatures(
            sharing.others().map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    addStringProperty("id", p.uid)
                    addStringProperty("name", p.name)
                    addStringProperty("icon", if (Ageing.look(p.age, info.interval, info.stale, p.still) == Ageing.Look.FRESH) PEER_FRESH else PEER_OLD)
                    addNumberProperty("opacity", Ageing.opacity(p.age, info.interval, info.stale, p.still))
                }
            },
        )
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
     * Tells the map which part of it is in view, so that its centre (the followed position)
     * is the centre of what can be seen, and sizes the frame to it.
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
        b.notice.translationX = dx
        b.progress.translationY = top.toFloat()
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
        // As the keyboard closes under a tall panel, the panel is for a moment still where the
        // keyboard held it: no map would be left in view, and the map would jump. Wait for the
        // panel's own layout, which comes here again.
        if (top + bottom > h - dp(40)) return
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

    /**
     * Asks the server for its styles: this is how the app knows whether it is in reach. First
     * the way it is reached at home; if nothing answers there and this phone has been let in,
     * through the remote link.
     */
    internal fun probe() {
        val base = prefs.server
        if (base == null || probing) return
        probing = true
        scope.launch {
            var way = Route.LAN
            var styles = Net.reach { TileServer.styles(base, way = Route.LAN) }
            val end = Http.remote
            if (styles == null && end != null) {
                Net.untie()
                way = Route.REMOTE
                // Each address the box gave, the one that answered last first.
                val order = listOf(end.current) + end.endpoints.indices.filter { it != end.current }
                for (i in order) {
                    end.current = i
                    styles = TileServer.styles(base, 6000, Route.REMOTE)
                    if (styles != null) break
                    // The gateway answers 403 to a phone its owner removed on the box, and 401 to
                    // everyone while remote access is switched off on the box (Admin, Remote access).
                    val answer = TileServer.status("${base}styles.json", 6000, Route.REMOTE)
                    if (answer == 403 && base == prefs.server) {
                        removeRemote()
                        toast("The box removed this device. To use the box through the internet, pair this device again on the box's network.")
                        break
                    }
                    if (answer == 401) {
                        if (!remoteSwitchedOff) toast("Remote access is off on the box.")
                        remoteSwitchedOff = true
                        break
                    }
                }
            }
            probing = false
            if (base != prefs.server) return@launch
            if (!styles.isNullOrEmpty()) prefs.styles = styles
            val changed = styles != null && Http.route != way
            if (styles != null) Http.route = way
            if (styles != null) remoteSwitchedOff = false
            setReachable(styles != null)
            if (changed) { updateStatus(); panels.refresh() }
            if (styles != null && way == Route.LAN) refreshRemote(base)
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

    /* ---------- the remote link ---------- */

    /** True while the server is reached through the remote link. */
    internal val remoteNow get() = reachable && Http.route == Route.REMOTE

    /** What this phone was given by the server it is set to, if it has been let in there. */
    internal fun enrolment(): Enrolment? = prefs.enrolment?.takeIf { it.home == prefs.server }

    /** Tells the client where home is and, when this phone has been let in, where the gateway is. */
    private fun applyRemote() {
        Http.setHome(prefs.server)
        Http.remote = enrolment()?.let(RemoteAccess::end)
    }

    /** At home, now and then: have the box's addresses from outside changed? */
    private fun refreshRemote(base: String) {
        val mine = enrolment() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - remoteChecked < REMOTE_CHECK_MS && remoteChecked != 0L) return
        remoteChecked = now
        scope.launch {
            val info = RemoteAccess.info(base) ?: return@launch
            if (base != prefs.server || (info.endpoints == mine.endpoints && info.reach == mine.reach)) return@launch
            prefs.enrolment = mine.copy(endpoints = info.endpoints, reach = info.reach)
            applyRemote()
            panels.refresh()
        }
    }

    /** Asks the server to let this phone in. `done` gets null, or what went wrong in the server's words. */
    internal fun setUpRemote(code: String?, done: (String?) -> Unit) {
        val base = prefs.server ?: return done("Connect to the box first.")
        scope.launch {
            try {
                val paired = RemoteAccess.enrol(base, "${Build.MANUFACTURER} ${Build.MODEL}".trim(), code)
                prefs.enrolment = paired.copy(reach = RemoteAccess.info(base)?.reach.orEmpty())
                applyRemote()
                done(null)
            } catch (e: RemoteAccess.Refused) {
                done(e.message)
            }
        }
    }

    /** Forgets the remote link on this phone. The server keeps the phone on its list until its owner removes it. */
    internal fun removeRemote() {
        prefs.enrolment = null
        applyRemote()
        Http.route = Route.LAN
    }

    /** A server answered at the address typed or found: use it from now on. */
    internal fun useServer(found: Found) {
        val first = prefs.server != found.base
        if (first) sharing.serverChanged()
        prefs.server = found.base
        prefs.styles = found.styles
        Http.route = Route.LAN
        applyRemote()
        setReachable(true)
        updateStatus()
        showStyle()
        panels.show(Screen.NONE)
        val style = currentStyle()
        if (style == null) { notice("This box has no map styles."); return }
        scope.launch {
            val info = TileServer.info(style.url) ?: return@launch
            if (info.empty) notice("The box has no maps yet. Build the maps on the Map builder page of the box.")
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
            // Short: a wide tag cuts the grid reference beside it. The bar under the strip says "download".
            saving != null -> "${saving.percent}%" to R.color.amber
            prefs.server == null -> "No box" to R.color.muted
            // The same word on the box's network and through the internet: the map works the
            // same. Menu › Box tells which way.
            reachable -> "Connected" to R.color.sig
            else -> "Offline" to R.color.muted
        }
        b.status.text = text
        b.status.setTextColor(color(colour))
        // A download also shows as a bar under the strip, whatever the panel shows.
        b.progress.visibility = if (saving != null) View.VISIBLE else View.GONE
        if (saving != null) b.progress.progress = saving.percent
        // While this phone's position is shared, the strip says so: amber while it is getting
        // through, grey while it is not (out of reach, no position yet).
        b.sharing.visibility = if (sharing.on) View.VISIBLE else View.GONE
        b.sharing.setTextColor(color(if (sharing.live) R.color.amber else R.color.muted))
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

    internal fun saveArea(name: String, style: MapStyle, box: Box, maxZoom: Int) {
        areas.save(name, style, LatLngBounds.from(box.north, box.east, box.south, box.west), maxZoom, resources.displayMetrics.density) {
            toast("The app did not save the map area. $it")
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
        val spot = at?.let(::spotAt) ?: return@Runnable
        b.map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        mark(spot.latitude, spot.longitude)
    }

    /** The point under a finger. A finger on the position symbol means "where I am": the position itself. */
    private fun spotAt(at: PointF): LatLng? {
        val m = map ?: return null
        val here = lastLocation()?.let { LatLng(it.latitude, it.longitude) }
        if (here != null && m.projection.toScreenLocation(here).let { hypot(it.x - at.x, it.y - at.y) < dp(32) }) return here
        return m.projection.fromScreenLocation(at)
    }

    private fun watchHold(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (map != null && panels.screen != Screen.SAVE && panels.screen != Screen.MOVE) {
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
        // After Move in a marker's panel, the next tap is the marker's new position.
        if (panels.screen == Screen.MOVE) {
            val spot = spotAt(p) ?: return true
            selected?.let(markers::get)?.let { updateMarker(it.copy(lat = spot.latitude, lon = spot.longitude)) }
            b.map.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            panels.show(Screen.MARKER)
            return true
        }
        val reach = dp(26).toFloat()                 // a gloved finger is not exact
        val around = RectF(p.x - reach, p.y - reach, p.x + reach, p.y + reach)
        m.queryRenderedFeatures(around, MARKER_DOTS).firstOrNull()?.getStringProperty("id")?.let { openMarker(it, fromList = false); return true }
        m.queryRenderedFeatures(around, PEER_SYMBOLS).firstOrNull()?.getStringProperty("id")?.let { openPeer(it, fromList = false); return true }
        return false
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
        markers.update(if (marker.shared) sharing.edited(marker) else marker)
        prefs.color = marker.color
        markersChanged()
    }

    internal fun deleteMarker(id: String) {
        if (markers.get(id)?.shared == true) sharing.removed(id)
        markers.delete(id)
        select(null)
        markersChanged()
    }

    /**
     * Shares a marker with everyone who reaches the box, or takes it back: it then goes from
     * the box and stays on this phone.
     */
    internal fun shareMarker(id: String, on: Boolean) {
        val marker = markers.get(id) ?: return
        val now = if (on) {
            // The box takes only plain ids; a marker from a file may have another kind.
            sharing.edited(if (ShareJson.ID.matches(id)) marker else marker.copy(id = markers.newId()))
        } else {
            sharing.removed(id)
            marker.copy(shared = false, updated = "", by = "")
        }
        markers.replaceAll(markers.markers.map { if (it.id == id) now else it })
        if (now.id != id && selected == id) select(now.id)
        markersChanged()
    }

    /** The markers after a round with the box: other phones' changes are in, deleted ones are out. */
    internal fun sharedMarkers(list: List<Marker>) {
        val open = selected?.let(markers::get)
        markers.replaceAll(list)
        markersChanged()
        val now = open?.let { markers.get(it.id) }
        if (open != null && now == null) {
            if (panels.screen == Screen.MOVE) panels.show(Screen.MARKER)
            panels.back()
            toast("Another device deleted ${open.label.ifEmpty { "this marker" }}.")
        } else if (open != null && now != open.copy(updated = now?.updated.orEmpty(), by = now?.by.orEmpty())) {
            panels.rebuild(Screen.MARKER)
        }
        panels.refresh()
    }

    /* ---------- the other phones ---------- */

    /** Draws the other phones again: one came, went, moved or grew older. */
    internal fun peersChanged() {
        style?.getSourceAs<GeoJsonSource>(PEERS)?.setGeoJson(peersOnMap())
    }

    /** Sharing changed its state: the tag in the strip and the panels follow. */
    internal fun shareChanged() {
        updateStatus()
        panels.refresh()
    }

    internal fun openPeer(uid: String, fromList: Boolean) {
        val peer = sharing.other(uid) ?: return
        selectPeer(uid)
        panels.showPeer(fromList)
        if (follow == Follow.FREE) b.root.post { map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(peer.lat, peer.lon)), 400) }
    }

    internal fun selectPeer(uid: String?) {
        selectedPeer = uid
        style?.getLayerAs<CircleLayer>(PEER_SELECTED)?.setFilter(peerFilter())
    }

    /**
     * Android 13 and later ask before an app may show a notification. Sharing goes on either
     * way; once allowed, the notification that says so is put up again, to be seen.
     */
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) keepSharing() }
    private var notifyAsked = false

    /**
     * Has the position kept shared when the app leaves the screen. Only once the position
     * may be used, and only from here, with the app in view: Android allows no other start.
     */
    private fun keepSharing() {
        if (!sharing.on || !mayLocate()) return
        ShareService.start(this)
        // The notification that says sharing is on needs its own permission on newer Androids.
        // Asked while it is missing, once each time the app is opened: also where sharing was
        // on already, from before the app had this notification.
        if (!notifyAsked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyAsked = true
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Closes the app for good: the position goes from the others' maps, and nothing runs on. */
    internal fun quit() {
        sharing.closing()
        finishAndRemoveTask()
    }

    /** Switches sharing this phone's position on. It needs the position, so the permission is asked for first. */
    internal fun startSharing(name: String) {
        val go = {
            sharing.switchOn(name)
            panels.rebuild(Screen.SHARING)
            // Switched on by hand: worth asking about the notification again.
            notifyAsked = false
            keepSharing()
        }
        if (mayLocate()) {
            if (!locationOn) style?.let(::startLocation)
            go()
        } else {
            afterLocation = go
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    /** The phone's position as it is shared; null without one. */
    internal fun fix(): Fix? = lastLocation()?.toFix()

    internal fun markersChanged() {
        style?.getSourceAs<GeoJsonSource>(MARKERS)?.setGeoJson(markersOnMap())
    }

    /** Distance and true bearing from the phone to a place, in words; null without a position. `brief`: for one line of a list. */
    internal fun fromHere(lat: Double, lon: Double, brief: Boolean = false): String? {
        val here = lastLocation() ?: return null
        val out = FloatArray(2)
        Location.distanceBetween(here.latitude, here.longitude, lat, lon, out)
        if (out[0] < 5) return "At your position"
        val bearing = ((out[1] + 360) % 360).toInt()
        val far = if (imperial) {
            val miles = out[0] / 1609.344
            if (miles < 0.1) "${(out[0] / 0.3048).toInt()} ft" else String.format(Locale.US, "%.1f mi", miles)
        } else {
            if (out[0] < 1000) "${out[0].toInt()} m" else String.format(Locale.US, "%.1f km", out[0] / 1000)
        }
        return String.format(Locale.US, if (brief) "%s, bearing %03d°" else "%s from you, bearing %03d° true", far, bearing)
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(markers.toGeoJson().toByteArray()) }
            toast("The app saved the markers in the file.")
        } catch (e: Exception) {
            toast("The app cannot write the file.")
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
                "The app added $added marker${if (added == 1) "" else "s"}." +
                    (if (loaded.markers.size > added) " ${loaded.markers.size - added} were on this device already." else "") +
                    (if (loaded.skipped > 0) " The app does not show ${loaded.skipped} other drawing${if (loaded.skipped == 1) "" else "s"}, for example arrows and shapes." else ""),
            )
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "The app cannot read the file.")
        } catch (e: Exception) {
            toast("The app cannot read the file.")
        }
    }

    internal fun saveMarkersFile() = saveFile.launch("fieldmap-markers-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.geojson")
    internal fun loadMarkersFile() = loadFile.launch(arrayOf("*/*"))

    /* ---------- the phone's position ---------- */

    /** What asked for the location permission when it was not the position button. */
    private var afterLocation: (() -> Unit)? = null

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val then = afterLocation
        afterLocation = null
        if (granted.values.any { it }) {
            style?.let(::startLocation)
            if (then != null) then() else if (locationOn) setFollow(Follow.NORTH)
        } else {
            toast(if (then != null) "The app cannot share your position without the Location permission." else "The map cannot show your position without the Location permission.")
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
        keepScreenOn()
        updateReadout()
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

    /** The strip at the top: the grid reference and latitude/longitude of the device, and the scale bar. */
    private fun updateReadout() {
        val m = map ?: return
        // MapLibre counts in density-independent pixels; the bar is drawn in real ones.
        b.scale.setMetersPerPixel(m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 0.0) / resources.displayMetrics.density)
        val here = lastLocation()
        if (here == null) {
            b.grid.text = "No GPS position"
            b.latlon.text = if (locationOn) "The app waits for the GPS" else "Press Follow to show your position"
            return
        }
        b.grid.text = Utm.toMgrs(here.latitude, here.longitude) ?: "Outside the MGRS grid"
        // Rounded up, never "±0": feet where the scale bar is in feet and miles.
        val accuracy = when {
            !here.hasAccuracy() -> ""
            imperial -> " ±${kotlin.math.ceil(here.accuracy / 0.3048).toInt().coerceAtLeast(1)} ft"
            else -> " ±${kotlin.math.ceil(here.accuracy.toDouble()).toInt().coerceAtLeast(1)} m"
        }
        b.latlon.text = String.format(Locale.US, "YOU%s · %.5f, %.5f", accuracy, here.latitude, here.longitude)
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
            updateReadout()
            chooseHeadingSource()
            sharing.tick()
            panels.peerTick()
            // Every quarter of a minute, and more often right after opening while nothing answers.
            if (ticks % 15 == 0 || (ticks < 6 && !reachable)) probe()
            ticks++
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onStart() {
        super.onStart()
        b.map.onStart()
        // In view, the app sends the position itself; the service waits, ready to take over.
        ShareService.shown(true)
        keepSharing()
    }

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

    override fun onStop() {
        b.map.onStop()
        // Out of view: from here on the service keeps the position shared.
        ShareService.shown(false)
        super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); b.map.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); b.map.onSaveInstanceState(outState) }

    override fun onDestroy() {
        // Closed for good: this phone's position goes from the box at once.
        if (isFinishing) sharing.closing()
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
        const val PEERS = "fm-peers"
        const val PEER_SYMBOLS = "fm-peers-symbol"
        const val PEER_SELECTED = "fm-peers-selected"
        const val PEER_FRESH = "fm-peer"
        const val PEER_OLD = "fm-peer-old"
        /** How often, at home, the app asks whether the server's address from outside changed. */
        const val REMOTE_CHECK_MS = 600_000L
        /** How long a finger rests on the map to mark the spot. */
        const val HOLD_MS = 2000L
        /** The map before any server is known: one plain colour. */
        const val BLANK = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#000000"}}]}"""
    }
}
