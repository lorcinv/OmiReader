package io.legado.app.ui.omi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.Menu
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import io.legado.app.R
import io.legado.app.help.config.OmiTheme

/** Native rendering of OmiComic mobile.css:719–723 and its AppIcon SVGs. */
class OmiBottomBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    val menu: Menu = PopupMenu(context, this).menu.apply { android.view.MenuInflater(context).inflate(R.menu.main_bnv, this) }
    var onItemSelected: ((Int) -> Unit)? = null
    private val icons = listOf(R.drawable.omi_ic_shelf, R.drawable.omi_ic_bookmark, R.drawable.omi_ic_history, R.drawable.omi_ic_settings)
    private val activeIcons = listOf(R.drawable.omi_ic_shelf_active, R.drawable.omi_ic_bookmark_active, R.drawable.omi_ic_history_active, R.drawable.omi_ic_settings_active)
    var selectedItemId: Int = R.id.menu_bookshelf
        set(value) { field = value; updateSelection() }
    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor(OmiTheme.current(context).surface))
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val button = LinearLayout(context).apply {
                id = item.itemId; orientation = VERTICAL; gravity = Gravity.CENTER
                contentDescription = item.title; isFocusable = true
                addView(ImageView(context).apply { setImageResource(icons[i]) }, LayoutParams(context.omiDp(20), context.omiDp(20)))
                addView(omiText(context, item.title ?: "", 10).apply { gravity = Gravity.CENTER }, LayoutParams(-1, context.omiDp(17)).apply { topMargin = context.omiDp(3) })
                setOnClickListener { selectedItemId = item.itemId; onItemSelected?.invoke(item.itemId) }
            }
            addView(button, LayoutParams(0, context.omiDp(60), 1f))
        }
        updateSelection()
    }
    private fun updateSelection() {
        val palette = OmiTheme.current(context)
        for (i in 0 until childCount) {
            val row = getChildAt(i) as LinearLayout
            val active = row.id == selectedItemId
            row.isSelected = active; row.alpha = if (active) 1f else .68f
            val color = Color.parseColor(if (active) palette.text else palette.muted)
            (row.getChildAt(0) as ImageView).apply { setImageResource(if (active) activeIcons[i] else icons[i]); setColorFilter(color) }
            (row.getChildAt(1) as TextView).apply { setTextColor(color); typeface = Typeface.create("sans-serif", if (active) Typeface.BOLD else Typeface.NORMAL) }
            menu.getItem(i).isChecked = active
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(context.omiDp(60) + paddingBottom, MeasureSpec.EXACTLY))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, Paint().apply { color = Color.parseColor(OmiTheme.current(context).muted); alpha = 35; strokeWidth = context.resources.displayMetrics.density })
    }
}
