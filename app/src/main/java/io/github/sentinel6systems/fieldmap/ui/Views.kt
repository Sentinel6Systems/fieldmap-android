package io.github.sentinel6systems.fieldmap.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.sentinel6systems.fieldmap.R
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A square button on the map: a symbol with a word under it. `android:tag="primary"` fills it green. */
@SuppressLint("ResourceType")
class MapButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val icon = ImageView(context)
    private val caption = TextView(context)
    private val primary get() = tag == "primary"

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        elevation = context.dp(3).toFloat()
        setBackgroundResource(if (primary) R.drawable.bg_primary else R.drawable.bg_control)
        addView(icon, LayoutParams(context.dp(28), context.dp(28)))
        caption.typeface = Typeface.MONOSPACE
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        caption.letterSpacing = 0.06f
        caption.isAllCaps = true
        caption.gravity = Gravity.CENTER
        caption.maxLines = 1
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
        // The two attributes are read by their place in the list.
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.src, android.R.attr.text))
        set(a.getResourceId(0, 0), a.getString(1).orEmpty(), false)
        a.recycle()
    }

    /** on: the green edge and symbol of something that is switched on. */
    fun set(iconRes: Int, text: String, on: Boolean) {
        if (iconRes != 0) icon.setImageResource(iconRes)
        caption.text = text
        caption.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (text.isNotEmpty()) contentDescription = text
        isSelected = on
        val c = context.color(if (primary) R.color.on_sig else if (on) R.color.sig else R.color.ink)
        icon.imageTintList = ColorStateList.valueOf(c)
        caption.setTextColor(c)
    }
}

/** The panel: never taller than `maxHeight`, so some map always stays in view. */
class PanelLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var maxHeight = Int.MAX_VALUE
        set(v) { field = v; requestLayout() }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = MeasureSpec.getSize(heightSpec)
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY || maxHeight >= size) super.onMeasure(widthSpec, heightSpec)
        else super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }
}

/** A marker's dot as it is on the map: the colour with a white edge and a thin dark line around it. */
class DotView(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        paint.style = Paint.Style.FILL
        paint.color = 0x99111111.toInt(); canvas.drawCircle(width / 2f, height / 2f, r, paint)
        paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(width / 2f, height / 2f, r * 0.9f, paint)
        paint.color = color; canvas.drawCircle(width / 2f, height / 2f, r * 0.7f, paint)
    }
}

/** The frame that marks the area to save: the map outside it is dimmed. */
class AreaFrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** The frame, in this view's pixels. */
    val rect = RectF()
    private val dim = Paint().apply { color = 0x73000000 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(3).toFloat(); color = context.color(R.color.sig) }
    private val under = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(6).toFloat(); color = 0xB3FFFFFF.toInt() }

    /** The frame fills the view except for what is covered at each side, less a margin. */
    fun setCovered(left: Int, top: Int, right: Int, bottom: Int) {
        val m = context.dp(22).toFloat()
        rect.set(left + m, top + m, width - right - m, height - bottom - m)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, rect.top, dim)
        canvas.drawRect(0f, rect.bottom, w, h, dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
        canvas.drawRect(rect, under)
        canvas.drawRect(rect, edge)
    }
}

/** A scale bar: a round distance and the line that long on the map. Miles and feet where they are used. */
class ScaleBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val imperial = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")
    private var label = ""
    private var length = 0f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat(); color = 0xFF111111.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(5).toFloat(); color = 0xCCFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = context.dp(13).toFloat(); color = 0xFF111111.toInt() }
    private val textHalo = Paint(text).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(3).toFloat(); color = 0xCCFFFFFF.toInt() }

    fun setMetersPerPixel(mpp: Double) {
        if (mpp <= 0 || width == 0) return
        val max = (width - context.dp(4)) * mpp                       // metres the full width shows
        val small = if (imperial) 0.3048 else 1.0                     // a foot or a metre
        val big = if (imperial) 1609.344 else 1000.0                  // a mile or a kilometre
        val unit = if (max >= (if (imperial) big / 2 else big)) big else small      // "0.5 mi", not "2000 ft"
        val value = round125(max / unit)
        length = (value * unit / mpp).toFloat()
        val name = if (unit == big) (if (imperial) "mi" else "km") else (if (imperial) "ft" else "m")
        label = "${if (value >= 1) value.toLong().toString() else value.toString()} $name"
        invalidate()
    }

    /** The largest of 1, 2, 5 × a power of ten that is not above v. */
    private fun round125(v: Double): Double {
        val p = 10.0.pow(floor(log10(v)))
        val d = v / p
        return p * when { d >= 5 -> 5; d >= 2 -> 2; else -> 1 }
    }

    override fun onDraw(canvas: Canvas) {
        if (length <= 0) return
        val y = height - context.dp(4).toFloat()
        val x0 = context.dp(2).toFloat()
        val tick = context.dp(6).toFloat()
        for (p in listOf(halo, line)) {
            canvas.drawLine(x0, y, x0 + length, y, p)
            canvas.drawLine(x0, y, x0, y - tick, p)
            canvas.drawLine(x0 + length, y, x0 + length, y - tick, p)
        }
        canvas.drawText(label, x0 + context.dp(6), y - context.dp(6), textHalo)
        canvas.drawText(label, x0 + context.dp(6), y - context.dp(6), text)
    }
}
