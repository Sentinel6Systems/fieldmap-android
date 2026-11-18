package io.github.sentinel6systems.fieldmap.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.sentinel6systems.fieldmap.R

// The pieces every panel is built from, in the look of fieldmap-server's pages
// (ui/fieldmap.css): square edges, one green signal colour, small monospace capitals for
// labels. Everything you press is at least 56 dp high.

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(width: Int = MATCH, height: Int = WRAP, weight: Float = 0f, top: Int = 0, start: Int = 0) =
    LinearLayout.LayoutParams(width, height, weight).apply { topMargin = top; marginStart = start }

/** A small section label with a rule beside it. */
fun Context.label(text: String, top: Int = 18): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = lp(top = dp(top)).apply { bottomMargin = dp(8) }
    addView(TextView(context).apply { setTextAppearance(R.style.Label); this.text = text }, lp(WRAP))
    addView(View(context).apply { setBackgroundColor(color(R.color.line)) }, lp(0, dp(1), 1f, start = dp(12)))
}

fun Context.body(text: CharSequence, muted: Boolean = false, top: Int = 0): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(color(if (muted) R.color.muted else R.color.ink))
    setLineSpacing(0f, 1.15f)
    layoutParams = lp(top = dp(top))
}

fun Context.mono(text: CharSequence, sizeSp: Float = 14f, muted: Boolean = true): TextView = TextView(this).apply {
    this.text = text
    typeface = Typeface.MONOSPACE
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(if (muted) R.color.muted else R.color.ink))
}

enum class Kind { NORMAL, PRIMARY, DANGER }

fun Context.button(text: String, kind: Kind = Kind.NORMAL, onClick: () -> Unit): TextView = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    typeface = Typeface.DEFAULT_BOLD
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    minHeight = resources.getDimensionPixelSize(R.dimen.control)
    setPadding(dp(14), 0, dp(14), 0)
    setBackgroundResource(if (kind == Kind.PRIMARY) R.drawable.bg_primary else R.drawable.bg_control)
    setTextColor(color(when (kind) { Kind.PRIMARY -> R.color.on_sig; Kind.DANGER -> R.color.warn; Kind.NORMAL -> R.color.ink }))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    layoutParams = lp()
}

/** Greys a button out and stops it answering. */
fun View.enable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.45f
}

/** Buttons side by side, sharing the width. */
fun Context.buttons(vararg views: View, top: Int = 12): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = lp(top = dp(top))
    views.forEachIndexed { i, v -> addView(v, lp(0, WRAP, 1f, start = if (i == 0) 0 else dp(10))) }
}

fun Context.field(hint: String, value: String, address: Boolean = false, max: Int = 0, done: () -> Unit = {}): EditText = EditText(this).apply {
    this.hint = hint
    setText(value)
    setSingleLine()
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTextColor(color(R.color.ink))
    setHintTextColor(color(R.color.muted))
    minHeight = resources.getDimensionPixelSize(R.dimen.control)
    setPadding(dp(14), 0, dp(14), 0)
    setBackgroundResource(R.drawable.bg_field)
    inputType = if (address) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    if (max > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(max))
    imeOptions = EditorInfo.IME_ACTION_DONE
    setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { done(); true } else false }
    setSelectAllOnFocus(true)
    layoutParams = lp()
}

/**
 * A row of a list: a title, a line of detail under it, and optionally something in front
 * (a colour dot) and behind (an arrow or a tick). The whole row is the target.
 */
fun Context.row(title: String, detail: CharSequence? = null, dot: Int? = null, icon: Int? = R.drawable.ic_next, onClick: (() -> Unit)? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(4), dp(8), dp(4), dp(9))
        setBackgroundResource(R.drawable.bg_row)
        layoutParams = lp()
        if (dot != null) addView(DotView(context, dot), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(color(R.color.ink))
            })
            if (detail != null) addView(mono(detail, 13f))
        }, lp(0, WRAP, 1f))
        if (icon != null && onClick != null) addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(if (icon == R.drawable.ic_check) R.color.sig else R.color.muted))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) })
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
    }
