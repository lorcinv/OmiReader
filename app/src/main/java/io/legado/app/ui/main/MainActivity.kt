package io.legado.app.ui.main

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.help.config.OmiTheme
import io.legado.app.ui.omi.OmiLibraryFragment
import io.legado.app.ui.omi.OmiSettingsFragment
import io.legado.app.ui.omi.OmiReadingOptions
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.bottomPadding

class MainActivity : BaseActivity<ActivityMainBinding>(), io.legado.app.ui.font.FontSelectDialog.CallBack {
    override val curFontPath get() = io.legado.app.help.config.ReadBookConfig.textFont
    override fun selectFont(path: String) { io.legado.app.help.config.ReadBookConfig.textFont = path; io.legado.app.ui.omi.OmiReaderPanels.changed() }
    override val binding by viewBinding(ActivityMainBinding::inflate)
    private val destinations = listOf(R.id.menu_bookshelf, R.id.menu_discovery, R.id.menu_rss, R.id.menu_my_config)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        OmiReadingOptions.ensureDefaults(this)
        binding.viewPagerMain.adapter = Pages(supportFragmentManager)
        binding.viewPagerMain.offscreenPageLimit = 3
        binding.bottomNavigationView.apply {
            onItemSelected = { id ->
                val selected = destinations.indexOf(id)
                if (selected >= 0) binding.viewPagerMain.setCurrentItem(selected, false)
            }
            setOnApplyWindowInsetsListenerCompat { view, insets ->
                view.bottomPadding = insets.navigationBarHeight
                insets.inset(0, 0, 0, insets.navigationBarHeight)
            }
        }
        binding.viewPagerMain.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { binding.bottomNavigationView.selectedItemId = destinations[position] }
        })
        binding.viewPagerMain.setCurrentItem(savedInstanceState?.getInt("omiTab") ?: 0, false)
        binding.bottomNavigationView.selectedItemId = destinations[binding.viewPagerMain.currentItem]
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("omiTab", binding.viewPagerMain.currentItem)
        super.onSaveInstanceState(outState)
    }
    override fun observeLiveBus() { observeEvent<String>(EventBus.RECREATE) { recreate() } }
    fun setOrganizing(active: Boolean) { binding.bottomNavigationView.visibility = if (active) android.view.View.GONE else android.view.View.VISIBLE }
    @Suppress("DEPRECATION")
    private class Pages(manager: FragmentManager) : FragmentPagerAdapter(manager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getCount() = 4
        override fun getItem(position: Int): Fragment = when (position) {
            0 -> OmiLibraryFragment(0, "library")
            1 -> OmiLibraryFragment(1, "bookmarks")
            2 -> OmiLibraryFragment(2, "recent")
            else -> OmiSettingsFragment(3)
        }
    }
}
