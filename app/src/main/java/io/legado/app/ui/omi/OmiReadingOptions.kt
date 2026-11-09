package io.legado.app.ui.omi

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.OmiTheme
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent

object OmiReadingOptions {
    fun brightness(context: Context) = OmiSheet(context, context.getString(R.string.omi_brightness)).apply {
        val activity = context.omiActivity() ?: return@apply
        body.addView(SeekBar(context).apply {
            max = 100
            progress = ((activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f) * 100).toInt()
            contentDescription = context.getString(R.string.omi_brightness)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user) activity.window.attributes = activity.window.attributes.apply { screenBrightness = (value / 100f).coerceAtLeast(.02f) }
                }
            })
        }, LinearLayout.LayoutParams(-1, context.omiDp(56)))
        action(context.getString(R.string.omi_system)) { activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f } }
        show()
    }
    fun ensureDefaults(context: Context) {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean("omiReaderV2", false)) {
            if (!prefs.getBoolean("omiCustomReadingInfo", false)) ensureReadingInfo()
            return
        }
        ReadBookConfig.shareConfig = ReadBookConfig.config.copy()
        ReadBookConfig.shareLayout = true
        ReadBookConfig.hideStatusBar = true
        ReadBookConfig.hideNavigationBar = true
        ReadTipConfig.tipHeaderLeft = ReadTipConfig.none
        ReadTipConfig.tipHeaderMiddle = ReadTipConfig.none
        ReadTipConfig.tipHeaderRight = ReadTipConfig.none
        ReadTipConfig.headerMode = 2
        ReadTipConfig.tipFooterLeft = ReadTipConfig.chapterTitle
        ReadTipConfig.tipFooterMiddle = ReadTipConfig.none
        ReadTipConfig.tipFooterRight = ReadTipConfig.pageAndTotal
        ReadTipConfig.footerMode = 0
        ReadBookConfig.save()
        prefs.edit().putBoolean("omiReaderV2", true)
            .putBoolean(PreferKey.hideStatusBar, true).putBoolean(PreferKey.hideNavigationBar, true)
            .putInt("omiPageAnimation", 1).putBoolean("showReadTitleBarAddition", false).apply()
    }
    private fun ensureReadingInfo() {
        ReadBookConfig.shareLayout = true
        val config = ReadBookConfig.config
        if (config.headerMode == 2 && config.footerMode == 0 && config.tipFooterLeft == ReadTipConfig.chapterTitle &&
            config.tipFooterMiddle == ReadTipConfig.none && config.tipFooterRight == ReadTipConfig.pageAndTotal) return
        config.headerMode = 2
        config.tipHeaderLeft = ReadTipConfig.none; config.tipHeaderMiddle = ReadTipConfig.none; config.tipHeaderRight = ReadTipConfig.none
        config.footerMode = 0
        config.tipFooterLeft = ReadTipConfig.chapterTitle
        config.tipFooterMiddle = ReadTipConfig.none
        config.tipFooterRight = ReadTipConfig.pageAndTotal
        ReadBookConfig.save()
    }
    fun show(context: Context, changed: () -> Unit = {}) = OmiSheet(context, context.getString(R.string.omi_reading_settings)).apply {
        OmiReaderPanels.populate(this, changed); show()
    }
    fun typography(context: Context, changed: () -> Unit = {}) = OmiReaderPanels.fullTypography(context, changed)
    fun pageEffect(context: Context, changed: () -> Unit = {}) = OmiSheet(context, context.getString(R.string.omi_page_turn)).apply {
        OmiReaderPanels.pageEffects(this, changed); show()
    }
}
