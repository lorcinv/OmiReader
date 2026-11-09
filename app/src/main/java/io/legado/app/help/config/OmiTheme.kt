package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent

/** Omi's shared palette. Changing colour never replaces the reader's chosen typography. */
object OmiTheme {
    data class Palette(
        val id: String, val name: String, val background: String, val surface: String,
        val text: String, val muted: String, val accent: String, val reader: String
    )

    val palettes = listOf(
        Palette("mist", "雾蓝", "#f8f8f7", "#ffffff", "#263249", "#66748c", "#4b669b", "#efefed"),
        Palette("nord", "北境", "#e2e8f2", "#f4f6fa", "#2e3b52", "#5d6c86", "#4f658b", "#d5ddea"),
        Palette("sand", "暖沙", "#f3efe9", "#fdfbf8", "#38312b", "#7b6c5d", "#806247", "#e6dfd5"),
        Palette("night", "夜色", "#2e3440", "#363e4c", "#e5e9f0", "#b0bac9", "#88c0d0", "#292f3a")
    )

    fun current(context: Context): Palette {
        if (AppConfig.isNightTheme) return palettes[3]
        return day(context)
    }

    fun day(context: Context): Palette {
        val id = context.defaultSharedPreferences.getString("omiDayTheme", "mist")
        return palettes.firstOrNull { it.id == id && it.id != "night" } ?: palettes[0]
    }

    fun ensureDefaults(context: Context) {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean("omiDefaultsV1", false)) return
        val day = palettes[0]
        val night = palettes[3]
        prefs.edit()
            .putBoolean("omiDefaultsV1", true)
            .putString("omiDayTheme", day.id)
            .putString(PreferKey.language, "zh")
            .putString(PreferKey.themeMode, "1")
            .putInt(PreferKey.cPrimary, Color.parseColor(day.background))
            .putInt(PreferKey.cAccent, Color.parseColor(day.accent))
            .putInt(PreferKey.cBackground, Color.parseColor(day.background))
            .putInt(PreferKey.cBBackground, Color.parseColor(day.surface))
            .putInt(PreferKey.cNPrimary, Color.parseColor(night.background))
            .putInt(PreferKey.cNAccent, Color.parseColor(night.accent))
            .putInt(PreferKey.cNBackground, Color.parseColor(night.background))
            .putInt(PreferKey.cNBBackground, Color.parseColor(night.surface))
            .putBoolean(PreferKey.showDiscovery, false)
            .putBoolean(PreferKey.showRss, false)
            .putBoolean(PreferKey.showUnread, false)
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean("showMangaUi", false)
            .putInt(PreferKey.bookshelfLayout, 1)
            .putInt(PreferKey.barElevation, 0)
            .putBoolean(PreferKey.readBarStyleFollowPage, true)
            .apply()
        AppConfig.themeMode = "1"
        AppConfig.isEInkMode = false
    }

    fun apply(context: Context, palette: Palette) {
        val dark = palette.id == "night"
        val edit = context.defaultSharedPreferences.edit()
        if (!dark) edit.putString("omiDayTheme", palette.id)
        edit.putString(PreferKey.themeMode, if (dark) "2" else "1").apply()
        AppConfig.themeMode = if (dark) "2" else "1"
        AppConfig.isEInkMode = false
        if (!dark) {
            // Carry the user's typography to the chosen colour preset.
            val previous = ReadBookConfig.config
            val index = palettes.indexOfFirst { it.id == palette.id }.coerceAtLeast(0)
            val recoloured = previous.copy(
                name = palette.name, bgStr = palette.reader, bgType = 0,
                bgStrNight = palettes[3].reader, bgTypeNight = 0,
                textColor = palette.text, textColorNight = palettes[3].text,
                darkStatusIcon = true, darkStatusIconNight = false
            )
            ReadBookConfig.styleSelect = index
            ReadBookConfig.durConfig = recoloured
        } else {
            // Keep the day preset so the reader's day/night button returns to it.
            ReadBookConfig.durConfig.apply {
                bgStrNight = palette.reader
                bgTypeNight = 0
                setCurTextColor(Color.parseColor(palette.text))
                setCurStatusIconDark(false)
            }
        }
        ReadBookConfig.save()
        ThemeConfig.applyConfig(context, ThemeConfig.Config(
            palette.name, dark, palette.background, palette.accent,
            palette.background, palette.surface
        ))
        postEvent(EventBus.UP_CONFIG, arrayListOf(0, 1, 2, 8, 9, 6))
        postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
    }

    fun sheetBackground(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val r = 24 * context.resources.displayMetrics.density
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun showChooser(context: Context, afterChange: (() -> Unit)? = null) {
        val names = listOf(io.legado.app.R.string.omi_mist, io.legado.app.R.string.omi_nord, io.legado.app.R.string.omi_sand, io.legado.app.R.string.omi_night)
        io.legado.app.ui.omi.OmiSheet(context, context.getString(io.legado.app.R.string.omi_reading_theme)).apply {
            palettes.forEachIndexed { index, palette ->
                action(context.getString(names[index]), selected = current(context).id == palette.id) {
                    apply(context, palette); afterChange?.invoke()
                }
            }
            show()
        }
    }
}
