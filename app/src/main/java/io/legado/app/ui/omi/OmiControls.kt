package io.legado.app.ui.omi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.help.config.OmiTheme

/** OmiComic's thin range track and compact circular thumb, rendered natively. */
object OmiControls {
    fun style(bar: SeekBar) {
        val c = bar.context; val p = OmiTheme.current(c)
        // ThemeSeekBar installs a tint for every layer. Clear it so the inactive
        // track keeps its own gray instead of being recolored like the progress.
        bar.progressTintList = null
        bar.progressBackgroundTintList = null
        bar.secondaryProgressTintList = null
        bar.thumbTintList = null
        fun track(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = c.omiDp(2).toFloat(); setSize(c.omiDp(1), c.omiDp(4)) }
        // Keep opacity in the color. ProgressBar can reset a LayerDrawable's alpha,
        // which would otherwise turn the remaining track into a solid dark line.
        val base = track(androidx.core.graphics.ColorUtils.setAlphaComponent(Color.parseColor(p.muted), 55))
        val progress = ClipDrawable(track(Color.parseColor(p.accent)), Gravity.START, ClipDrawable.HORIZONTAL)
        bar.progressDrawable = LayerDrawable(arrayOf(base, progress)).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        bar.thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(p.accent)); setSize(c.omiDp(14), c.omiDp(14)) }
        bar.thumbOffset = c.omiDp(7); bar.splitTrack = false
        bar.minimumHeight = c.omiDp(40); bar.maxHeight = c.omiDp(4)
        bar.setPadding(c.omiDp(8), 0, c.omiDp(8), 0)
    }
    fun styleSliders(view: View) {
        if (view is SeekBar) style(view)
        if (view is ViewGroup) repeat(view.childCount) { styleSliders(view.getChildAt(it)) }
    }
    fun slider(context: Context, parent: LinearLayout, label: String, value: Int, min: Int, max: Int, changed: (Int, Boolean) -> Unit): SeekBar {
        val caption = omiText(context, "$label  $value", 14)
        parent.addView(caption, LinearLayout.LayoutParams(-1, context.omiDp(32)))
        return SeekBar(context).apply {
            this.max = max - min; progress = (value - min).coerceIn(0, this.max); contentDescription = label
            style(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, n: Int, user: Boolean) { caption.text = "$label  ${n + min}"; if (user) changed(n + min, false) }
                override fun onStopTrackingTouch(bar: SeekBar) { changed(bar.progress + min, true) }
            })
            parent.addView(this, LinearLayout.LayoutParams(-1, context.omiDp(44)))
        }
    }
}
