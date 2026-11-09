package io.legado.app.ui.omi

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.legado.app.R
import io.legado.app.help.config.OmiTheme

/** OmiComic MobileSheet dimensions: 12dp outer gutter, 24dp radius, 18dp inner gutter. */
class OmiSheet(context: Context, title: CharSequence) : BottomSheetDialog(context) {
    private var doneAction: (() -> Boolean)? = null
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.omiDp(18), context.omiDp(8), context.omiDp(18), context.omiDp(18))
        background = omiBackground(context, OmiTheme.current(context).surface, 24)
    }
    init {
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(omiText(context, title, 18, true), LinearLayout.LayoutParams(0, context.omiDp(52), 1f))
        heading.addView(omiText(context, context.getString(R.string.omi_done), 14).apply {
            setTextColor(Color.parseColor(OmiTheme.current(context).accent))
            setPadding(context.omiDp(12), 0, 0, 0)
            setOnClickListener { complete() }
        }, LinearLayout.LayoutParams(context.omiDp(64), context.omiDp(52)))
        panel.addView(heading)
        val scroll = androidx.core.widget.NestedScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        setContentView(panel)
        setOnShowListener {
            OmiControls.styleSliders(panel)
            val host = context.omiActivity()
            if ((host is io.legado.app.ui.book.read.BaseReadBookActivity || host is io.legado.app.ui.book.read.RichDocumentActivity) && io.legado.app.help.config.ReadBookConfig.hideStatusBar) {
                window?.let { w ->
                    androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            window?.setDimAmount(.22f)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val sheet = findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            sheet.setBackgroundColor(Color.TRANSPARENT)
            val p = sheet.layoutParams as ViewGroup.MarginLayoutParams
            p.width = minOf(context.resources.displayMetrics.widthPixels - context.omiDp(24), context.omiDp(560))
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT
            p.leftMargin = (context.resources.displayMetrics.widthPixels - p.width) / 2
            p.rightMargin = p.leftMargin
            p.bottomMargin = context.omiDp(12)
            sheet.layoutParams = p
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.maxHeight = (context.resources.displayMetrics.heightPixels * .90f).toInt()
            ViewCompat.setOnApplyWindowInsetsListener(sheet) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    bottomMargin = context.omiDp(12) + safe.bottom
                    view.layoutParams = this
                }
                insets
            }
            ViewCompat.requestApplyInsets(sheet)
        }
    }
    private fun complete() {
        if (doneAction?.invoke() != false) dismiss()
    }
    fun action(label: CharSequence, icon: Int = 0, selected: Boolean = false, close: Boolean = true, run: () -> Unit): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.omiDp(48)
            setPadding(context.omiDp(4), context.omiDp(4), context.omiDp(4), context.omiDp(4))
            if (selected) background = omiBackground(context, OmiTheme.current(context).reader, 12)
            if (icon != 0) addView(omiIcon(context, icon, label.toString()), LinearLayout.LayoutParams(context.omiDp(36), context.omiDp(40)))
            addView(omiText(context, label, 15), LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) addView(omiText(context, "✓", 18), LinearLayout.LayoutParams(context.omiDp(28), -2))
            setOnClickListener { if (close) dismiss(); run() }
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }
    fun note(text: CharSequence) {
        body.addView(omiText(context, text, 13).apply {
            setTextColor(Color.parseColor(OmiTheme.current(context).muted))
            setPadding(0, context.omiDp(10), 0, context.omiDp(10))
        })
    }
    fun input(initial: String = "", hint: String = "", submit: (String) -> Unit) {
        val edit = EditText(context).apply {
            setSingleLine(true); setText(initial); this.hint = hint
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setTextColor(Color.parseColor(OmiTheme.current(context).text))
            setHintTextColor(Color.parseColor(OmiTheme.current(context).muted))
        }
        body.addView(edit, LinearLayout.LayoutParams(-1, context.omiDp(54)))
        var submitted = false
        doneAction = {
            val value = edit.text.toString().trim()
            if (value.isEmpty()) {
                edit.error = context.getString(R.string.omi_name_required)
                false
            } else {
                if (!submitted) { submitted = true; submit(value) }
                true
            }
        }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { complete(); true } else false
        }
    }
}

fun Context.omiDp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
fun Context.omiActivity(): androidx.fragment.app.FragmentActivity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is androidx.fragment.app.FragmentActivity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? androidx.fragment.app.FragmentActivity
}
fun omiBackground(context: Context, color: String, radius: Int = 12) = GradientDrawable().apply {
    setColor(Color.parseColor(color)); cornerRadius = context.omiDp(radius).toFloat()
}
fun omiText(context: Context, value: CharSequence, size: Int = 14, bold: Boolean = false) = TextView(context).apply {
    text = value; textSize = size.toFloat(); gravity = Gravity.CENTER_VERTICAL
    setTextColor(Color.parseColor(OmiTheme.current(context).text))
    if (bold) setTypeface(typeface, Typeface.BOLD)
}
fun omiIcon(context: Context, icon: Int, label: String, click: (() -> Unit)? = null) = androidx.appcompat.widget.AppCompatImageButton(context).apply {
    setImageResource(omiComicIcon(icon)); contentDescription = label
    setColorFilter(Color.parseColor(OmiTheme.current(context).muted))
    setBackgroundColor(Color.TRANSPARENT)
    setPadding(context.omiDp(10), context.omiDp(10), context.omiDp(10), context.omiDp(10))
    isClickable = click != null
    isFocusable = click != null
    if (click != null) setOnClickListener { click() }
}

fun omiComicIcon(icon: Int): Int = when (icon) {
    R.drawable.ic_arrow_back -> R.drawable.omi_ic_back
    R.drawable.ic_arrow_right -> R.drawable.omi_ic_chevron
    R.drawable.ic_cursor_left -> R.drawable.omi_ic_back
    R.drawable.ic_cursor_right -> R.drawable.omi_ic_next
    R.drawable.ic_search -> R.drawable.omi_ic_search
    R.drawable.ic_add -> R.drawable.omi_ic_add
    R.drawable.ic_more_vert -> R.drawable.omi_ic_more
    R.drawable.ic_bookmark -> R.drawable.omi_ic_bookmark
    R.drawable.ic_toc -> R.drawable.omi_ic_contents
    R.drawable.ic_bottom_books -> R.drawable.omi_ic_library
    R.drawable.ic_history -> R.drawable.omi_ic_history
    R.drawable.ic_settings, R.drawable.ic_interface_setting, R.drawable.ic_cfg_other -> R.drawable.omi_ic_settings
    R.drawable.ic_view_quilt -> R.drawable.omi_ic_grid
    R.drawable.ic_folder_outline, R.drawable.ic_create_folder_outline, R.drawable.ic_folder_open -> R.drawable.omi_ic_move
    R.drawable.ic_cfg_theme -> R.drawable.omi_ic_cover
    R.drawable.ic_cfg_about -> R.drawable.omi_ic_info
    R.drawable.ic_daytime -> R.drawable.omi_ic_sun
    R.drawable.ic_brightness -> R.drawable.omi_ic_moon
    R.drawable.ic_read_aloud -> R.drawable.omi_ic_voice
    R.drawable.ic_auto_page -> R.drawable.omi_ic_auto_page
    else -> icon
}
