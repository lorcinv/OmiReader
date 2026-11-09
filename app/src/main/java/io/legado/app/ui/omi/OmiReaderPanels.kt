package io.legado.app.ui.omi

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.OmiTheme
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent

/** One flat reader panel; all existing typesetting values remain available. */
object OmiReaderPanels {
    fun changed(after: () -> Unit = {}) {
        ReadBookConfig.save()
        postEvent(EventBus.UP_CONFIG, arrayListOf(0, 1, 2, 5, 6, 8, 9, 10, 11, 12))
        after()
    }
    fun populate(sheet: OmiSheet, after: () -> Unit = {}, typography: () -> Unit = { OmiReadingOptions.typography(sheet.context, after) }, colors: () -> Unit = { colors(sheet.context, after) }) {
        val c = sheet.context.omiActivity() ?: sheet.context
        val shortcuts = LinearLayout(c).apply { gravity = Gravity.CENTER }
        fun shortcut(label: Int, icon: Int, run: () -> Unit) {
            shortcuts.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(omiIcon(c, icon, c.getString(label)), LinearLayout.LayoutParams(c.omiDp(38), c.omiDp(36)))
                addView(omiText(c, c.getString(label), 12).apply { gravity = Gravity.CENTER })
                setOnClickListener { sheet.dismiss(); run() }
            }, LinearLayout.LayoutParams(0, c.omiDp(64), 1f))
        }
        shortcut(R.string.omi_typography, R.drawable.omi_ic_settings, typography)
        shortcut(R.string.omi_page_colors, R.drawable.omi_ic_cover, colors)
        shortcut(R.string.omi_brightness, R.drawable.ic_daytime) { OmiReadingOptions.brightness(c) }
        sheet.body.addView(shortcuts)
        pageEffects(sheet, after)
        sheet.note(c.getString(R.string.omi_reading_theme))
        val themes = LinearLayout(c)
        val names = listOf(R.string.omi_mist, R.string.omi_nord, R.string.omi_sand, R.string.omi_night)
        OmiTheme.palettes.forEachIndexed { i, palette ->
            themes.addView(omiText(c, c.getString(names[i]), 12).apply {
                gravity = Gravity.CENTER; background = omiBackground(c, palette.reader, 12)
                setTextColor(Color.parseColor(palette.text))
                setOnClickListener { OmiTheme.apply(c, palette); after() }
            }, LinearLayout.LayoutParams(0, c.omiDp(44), 1f).apply { setMargins(c.omiDp(3), 0, c.omiDp(3), 0) })
        }
        sheet.body.addView(themes)
        fullscreen(sheet, after)
    }
    fun fullscreen(sheet: OmiSheet, after: () -> Unit = {}) {
        val c = sheet.context.omiActivity() ?: sheet.context
        sheet.body.addView(SwitchCompat(c).apply {
            text = c.getString(R.string.omi_fullscreen); minHeight = c.omiDp(54)
            setTextColor(Color.parseColor(OmiTheme.current(c).text)); isChecked = ReadBookConfig.hideStatusBar
            setOnCheckedChangeListener { _, checked ->
                ReadBookConfig.hideStatusBar = checked; ReadBookConfig.hideNavigationBar = checked
                c.defaultSharedPreferences.edit().putBoolean(PreferKey.hideStatusBar, checked).putBoolean(PreferKey.hideNavigationBar, checked).apply()
                changed(after)
            }
        })
    }
    fun pageEffects(sheet: OmiSheet, after: () -> Unit = {}) {
        val c = sheet.context
        sheet.note(c.getString(R.string.omi_page_turn))
        val row = LinearLayout(c)
        val selected = c.defaultSharedPreferences.getInt("omiPageAnimation", 1)
        val buttons = mutableListOf<android.widget.TextView>()
        listOf(R.string.omi_page_effect_cover, R.string.omi_page_effect_slide, R.string.omi_page_effect_simulate, R.string.omi_page_effect_scroll, R.string.omi_page_effect_none).forEachIndexed { id, label ->
            val button = omiText(c, c.getString(label), 12).apply {
                gravity = Gravity.CENTER
                isSelected = selected == id
                background = omiBackground(c, if (isSelected) OmiTheme.current(c).reader else OmiTheme.current(c).surface, 10)
                setOnClickListener {
                    c.defaultSharedPreferences.edit().putInt("omiPageAnimation", id).apply()
                    ReadBookConfig.pageAnim = id
                    buttons.forEachIndexed { n, item -> item.isSelected = n == id; item.background = omiBackground(c, if (n == id) OmiTheme.current(c).reader else OmiTheme.current(c).surface, 10) }
                    changed(after)
                }
            }
            buttons += button; row.addView(button, LinearLayout.LayoutParams(0, c.omiDp(44), 1f))
        }
        sheet.body.addView(row)
    }
    fun colors(context: Context, after: () -> Unit = {}) = OmiSheet(context, context.getString(R.string.omi_page_colors)).apply {
        action(context.getString(R.string.background_color), R.drawable.omi_ic_cover) {
            pickColor(context, runCatching { Color.parseColor(ReadBookConfig.durConfig.curBgStr()) }.getOrDefault(Color.WHITE)) { color ->
                ReadBookConfig.durConfig.setCurBg(0, "#%06X".format(color and 0xffffff)); changed(after)
            }
        }
        action(context.getString(R.string.text_color), R.drawable.omi_ic_rename) {
            pickColor(context, ReadBookConfig.textColor) { color -> ReadBookConfig.durConfig.setCurTextColor(color); changed(after) }
        }
        OmiControls.slider(context, body, context.getString(R.string.omi_background_opacity), ReadBookConfig.durConfig.bgAlpha, 0, 100) { value, stopped ->
            if (stopped) { ReadBookConfig.durConfig.bgAlpha = value; changed(after) }
        }
        show()
    }
    private fun pickColor(context: Context, value: Int, selected: (Int) -> Unit) {
        val host = context.omiActivity() ?: return
        val dialog = ColorPickerDialog.newBuilder().setColor(value).setDialogType(ColorPickerDialog.TYPE_CUSTOM).create()
        dialog.setColorPickerDialogListener(object : ColorPickerDialogListener {
            override fun onColorSelected(dialogId: Int, color: Int) = selected(color)
            override fun onDialogDismissed(dialogId: Int) = Unit
        })
        dialog.show(host.supportFragmentManager, "omi-page-color")
    }
    fun fullTypography(context: Context, after: () -> Unit = {}, complete: Boolean = false) = OmiSheet(context, context.getString(R.string.omi_typography)).apply {
        note(context.getString(R.string.omi_global_settings))
        if (complete) {
            pageEffects(this, after)
            action(context.getString(R.string.omi_page_colors), R.drawable.omi_ic_cover) { colors(context, after) }
            action(context.getString(R.string.omi_reading_theme), R.drawable.omi_ic_cover) { OmiTheme.showChooser(context, after) }
        }
        fun slider(label: Int, value: Int, min: Int, max: Int, write: (Int) -> Unit) {
            OmiControls.slider(context, body, context.getString(label), value, min, max) { n, stopped -> if (stopped) { write(n); changed(after) } }
        }
        slider(R.string.omi_font_size, ReadBookConfig.textSize, 8, 64) { ReadBookConfig.textSize = it }
        slider(R.string.omi_line_spacing, ReadBookConfig.lineSpacingExtra, 0, 40) { ReadBookConfig.lineSpacingExtra = it }
        slider(R.string.omi_paragraph_spacing, ReadBookConfig.paragraphSpacing, 0, 40) { ReadBookConfig.paragraphSpacing = it }
        slider(R.string.omi_letter_spacing, (ReadBookConfig.letterSpacing * 100).toInt(), -20, 60) { ReadBookConfig.letterSpacing = it / 100f }
        slider(R.string.omi_margin, ReadBookConfig.paddingLeft, 0, 60) { ReadBookConfig.paddingLeft = it; ReadBookConfig.paddingRight = it }
        slider(R.string.omi_vertical_margin, ReadBookConfig.paddingTop, 0, 60) { ReadBookConfig.paddingTop = it; ReadBookConfig.paddingBottom = it }
        slider(R.string.omi_indent, ReadBookConfig.paragraphIndent.length, 0, 4) { ReadBookConfig.paragraphIndent = "　".repeat(it) }
        action(context.getString(R.string.text_font), R.drawable.omi_ic_library) { context.omiActivity()?.let { FontSelectDialog().show(it.supportFragmentManager, "omi-font") } }
        val bold = SwitchCompat(context).apply {
            text = context.getString(R.string.omi_bold); minHeight = context.omiDp(48); isChecked = ReadBookConfig.textBold == 1
            setTextColor(Color.parseColor(OmiTheme.current(context).text))
            setOnCheckedChangeListener { _, selected -> ReadBookConfig.textBold = if (selected) 1 else 0; changed(after) }
        }
        body.addView(bold)
        show()
    }
}
