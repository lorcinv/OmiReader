package io.legado.app.ui.omi

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.OmiTheme
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.startActivity

class OmiSettingsFragment() : Fragment(), MainFragmentInterface {
    constructor(position: Int) : this() { arguments = Bundle().apply { putInt("position", position) } }
    override val position: Int? get() = arguments?.getInt("position")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = requireContext()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(OmiTheme.current(c).background))
        }
        root.addView(omiText(c, getString(R.string.setting), 20, true).apply { setPadding(c.omiDp(20), 0, 0, 0) }, LinearLayout.LayoutParams(-1, c.omiDp(56)))
        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.omiDp(16), c.omiDp(10), c.omiDp(16), c.omiDp(24)) }
        fun entry(title: Int, icon: Int, subtitle: String = "", action: () -> Unit) {
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = c.omiDp(68); setOnClickListener { action() } }
            row.addView(omiIcon(c, icon, getString(title)), LinearLayout.LayoutParams(c.omiDp(40), c.omiDp(44)))
            row.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; setPadding(c.omiDp(10), c.omiDp(8), 0, c.omiDp(8))
                addView(omiText(c, getString(title), 16))
                if (subtitle.isNotEmpty()) addView(omiText(c, subtitle, 12).apply { setTextColor(Color.parseColor(OmiTheme.current(c).muted)) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(omiIcon(c, R.drawable.ic_arrow_right, getString(title)), LinearLayout.LayoutParams(c.omiDp(32), c.omiDp(40)))
            content.addView(row)
        }
        entry(R.string.omi_reading_theme, R.drawable.ic_cfg_theme) { OmiTheme.showChooser(c) }
        entry(R.string.omi_reading_settings, R.drawable.ic_interface_setting, getString(R.string.omi_global_settings)) { OmiReadingOptions.show(c) }
        entry(R.string.omi_sources, R.drawable.omi_ic_library) {
            OmiSheet(c, getString(R.string.omi_sources)).apply {
                note(getString(R.string.omi_sources_help))
                action(getString(R.string.omi_source_management), R.drawable.omi_ic_add) { startActivity<BookSourceActivity>() }
                action(getString(R.string.omi_search_online), R.drawable.omi_ic_search) { startActivity<SearchActivity>() }
                show()
            }
        }
        entry(R.string.omi_backup, R.drawable.ic_cfg_backup, getString(R.string.omi_backup_note)) {
            startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.BACKUP_CONFIG) }
        }
        entry(R.string.omi_disclaimer, R.drawable.omi_ic_info) {
            OmiSheet(c, getString(R.string.omi_disclaimer)).apply { note(getString(R.string.omi_disclaimer_text)); show() }
        }
        entry(R.string.omi_language, R.drawable.ic_translate) { language() }
        entry(R.string.omi_about, R.drawable.ic_cfg_about) { startActivity<AboutActivity>() }
        root.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets -> view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0); insets }
        return root
    }
    private fun language() {
        val c = requireContext(); val current = c.defaultSharedPreferences.getString(PreferKey.language, "zh")
        OmiSheet(c, getString(R.string.omi_language)).apply {
            listOf("" to getString(R.string.omi_system), "zh" to "简体中文", "tw" to "繁體中文", "en" to "English", "ja" to "日本語").forEach { (code, name) ->
                action(name, selected = current == code) {
                    c.defaultSharedPreferences.edit().putString(PreferKey.language, code).commit()
                    AppContextWrapper.updateApplicationLocale(c.applicationContext)
                    requireActivity().recreate()
                }
            }; show()
        }
    }
}
