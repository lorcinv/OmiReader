package io.legado.app

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.OmiTheme
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matcher
import org.hamcrest.Matchers.startsWith
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Focused checks for Omi's home navigation and colour-only theme changes. */
@RunWith(AndroidJUnit4::class)
class OmiUiInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val themeKeys = listOf(
        "omiDayTheme", PreferKey.themeMode, PreferKey.cPrimary, PreferKey.cAccent,
        PreferKey.cBackground, PreferKey.cBBackground, PreferKey.cNPrimary,
        PreferKey.cNAccent, PreferKey.cNBackground, PreferKey.cNBBackground
    )
    private lateinit var preferences: Map<String, *>
    private lateinit var configs: List<ReadBookConfig.Config>
    private lateinit var shared: ReadBookConfig.Config
    private var style = 0
    private var comicStyle = 0
    private var sharedLayout = false
    private var comic = false
    private var eink = false
    private var chapter = 0
    private var character = 0

    @Before
    fun saveSettings() {
        instrumentation.runOnMainSync {
            preferences = context.defaultSharedPreferences.all.filterKeys { it in themeKeys }
            configs = ReadBookConfig.configList.map { it.copy() }
            shared = ReadBookConfig.shareConfig.copy()
            style = ReadBookConfig.readStyleSelect
            comicStyle = ReadBookConfig.comicStyleSelect
            sharedLayout = ReadBookConfig.shareLayout
            comic = ReadBookConfig.isComic
            eink = AppConfig.isEInkMode
            chapter = ReadBook.durChapterIndex
            character = ReadBook.durChapterPos
            ReadBookConfig.isComic = false
        }
    }

    @After
    fun restoreSettings() {
        instrumentation.runOnMainSync {
            context.defaultSharedPreferences.edit().apply {
                themeKeys.forEach { key ->
                    when (val value = preferences[key]) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
            AppConfig.themeMode = context.defaultSharedPreferences.getString(PreferKey.themeMode, "0")
            AppConfig.isEInkMode = eink
            ReadBookConfig.configList.clear()
            ReadBookConfig.configList.addAll(configs)
            ReadBookConfig.shareConfig = shared
            ReadBookConfig.readStyleSelect = style
            ReadBookConfig.comicStyleSelect = comicStyle
            ReadBookConfig.shareLayout = sharedLayout
            ReadBookConfig.isComic = comic
            ReadBook.durChapterIndex = chapter
            ReadBook.durChapterPos = character
            ReadBookConfig.save()
            ThemeConfig.applyDayNight(context)
        }
    }

    @Test
    fun homeHasLibraryBookmarksRecentAndSettingsInOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navigation = activity.findViewById<io.legado.app.ui.omi.OmiBottomBar>(R.id.bottom_navigation_view)
                val visibleIds = navigation.menu.children.filter { it.isVisible }.map { it.itemId }.toList()
                assertEquals(listOf(R.id.menu_bookshelf, R.id.menu_discovery, R.id.menu_rss, R.id.menu_my_config), visibleIds)
                assertEquals(4, activity.findViewById<ViewPager>(R.id.view_pager_main).adapter?.count)
            }
            onView(withId(R.id.menu_add_local)).check(matches(isDisplayed()))
            onView(withId(R.id.menu_my_config)).perform(click())
            onView(withText("阅读主题")).check(matches(isDisplayed()))
            onView(withText(R.string.omi_layout)).check(doesNotExist())
            onView(withText(R.string.omi_sources)).check(matches(isDisplayed()))
            onView(withText(R.string.omi_backup)).check(matches(isDisplayed()))
            onView(withText(R.string.omi_disclaimer)).perform(click())
            onView(withText(R.string.omi_disclaimer_text)).check(matches(isDisplayed()))
            android.os.SystemClock.sleep(350)
            onView(withText(R.string.omi_done)).perform(click())
            onView(withText(R.string.omi_sources)).perform(click())
            onView(withText(R.string.omi_sources_help)).check(matches(isDisplayed()))
            android.os.SystemClock.sleep(350)
            onView(withText(R.string.omi_source_management)).perform(click())
            awaitActivity<io.legado.app.ui.book.source.manage.BookSourceActivity>(dismissHelp = true)
            onView(withId(R.id.recycler_view)).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            awaitActivity<MainActivity>()
            onView(withText(R.string.omi_sources)).perform(click())
            android.os.SystemClock.sleep(350)
            onView(withText(R.string.omi_search_online)).perform(click())
            awaitActivity<io.legado.app.ui.book.search.SearchActivity>()
            onView(withId(R.id.content_view)).check(matches(isDisplayed()))
            onView(withId(R.id.search_view)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isDisplayed()
                override fun getDescription() = "Finish editing the optional source search"
                override fun perform(controller: UiController, view: View) { view.clearFocus(); controller.loopMainThreadUntilIdle() }
            })
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            androidx.test.espresso.Espresso.pressBack()
            awaitActivity<MainActivity>()
            onView(withText(R.string.omi_backup)).perform(click())
            awaitActivity<io.legado.app.ui.config.ConfigActivity>(dismissHelp = true)
            onView(withId(R.id.configFrameLayout)).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            awaitActivity<MainActivity>()
            onView(withId(R.id.menu_bookshelf)).perform(click())
            onView(withId(R.id.menu_add_local)).check(matches(isDisplayed()))
        }
    }

    private inline fun <reified T : android.app.Activity> awaitActivity(dismissHelp: Boolean = false) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .any {
                        if (it !is T) return@any false
                        if (dismissHelp && it is androidx.fragment.app.FragmentActivity) {
                            it.supportFragmentManager.fragments.filterIsInstance<io.legado.app.ui.widget.dialog.TextDialog>()
                                .filter { help -> help.isVisible }.forEach { help -> help.dismiss() }
                        }
                        it.window.decorView.hasWindowFocus()
                    }
            }
            if (ready) return
            android.os.SystemClock.sleep(50)
        }
        fail("Expected a focused ${T::class.java.simpleName}")
    }

    @Test
    fun chooserPersistsAndKeepsSharedTypographyAndReadingPosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                ReadBookConfig.shareLayout = false
                ReadBookConfig.durConfig = ReadBookConfig.durConfig.copy(textSize = 18, lineSpacingExtra = 9)
                ReadBookConfig.shareLayout = true
                ReadBookConfig.shareConfig = ReadBookConfig.durConfig.copy(
                    textSize = 27, lineSpacingExtra = 21, paragraphSpacing = 10, textFont = "omi-test-font"
                )
                ReadBook.durChapterIndex = 8
                ReadBook.durChapterPos = 1234
            }
            onView(withId(R.id.menu_my_config)).perform(click())
            onView(withText("阅读主题")).perform(click())
            selectThemeRow("暖沙")
            onView(withId(R.id.bottom_navigation_view)).check(matches(isDisplayed()))
            scenario.recreate()
            scenario.onActivity {
                assertEquals("sand", OmiTheme.current(it).id)
                assertEquals("sand", it.defaultSharedPreferences.getString("omiDayTheme", null))
                assertReadingState()
            }
            listOf("night", "mist").forEach { id ->
                val palette = OmiTheme.palettes.single { it.id == id }
                onView(org.hamcrest.Matchers.allOf(withText("阅读主题"), isDisplayed())).perform(click())
                selectThemeRow(palette.name)
                onView(withId(R.id.bottom_navigation_view)).check(matches(isDisplayed()))
                scenario.onActivity {
                    assertEquals(id, OmiTheme.current(it).id)
                    assertEquals(Color.parseColor(palette.text), ReadBookConfig.textColor)
                    assertEquals(id != "night", ReadBookConfig.durConfig.curStatusIconDark())
                    val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
                    assertEquals(id != "night", bars.isAppearanceLightStatusBars)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        assertEquals(id != "night", bars.isAppearanceLightNavigationBars)
                    }
                    assertReadingState()
                }
            }
        }
    }

    @Test
    fun individualTypographyAlsoSurvivesAColourChange() {
        instrumentation.runOnMainSync {
            ReadBookConfig.shareLayout = false
            ReadBookConfig.durConfig = ReadBookConfig.durConfig.copy(textSize = 25, lineSpacingExtra = 19)
            OmiTheme.apply(context, OmiTheme.palettes[1])
            assertEquals(25, ReadBookConfig.textSize)
            assertEquals(19, ReadBookConfig.lineSpacingExtra)
            assertEquals(chapter, ReadBook.durChapterIndex)
            assertEquals(character, ReadBook.durChapterPos)
        }
    }

    @Test
    fun shippedReadingPresetsHaveFourSolidOmiPalettes() {
        val presets = JSONArray(context.assets.open("defaultData/readConfig.json").bufferedReader().use { it.readText() })
        assertEquals(4, presets.length())
        OmiTheme.palettes.forEachIndexed { index, palette ->
            val preset = presets.getJSONObject(index)
            assertEquals(palette.name, preset.getString("name"))
            assertEquals(palette.reader, preset.getString("bgStr"))
            assertEquals(0, preset.getInt("bgType"))
            assertEquals(20, preset.getInt("textSize"))
            assertEquals(24, preset.getInt("paddingLeft"))
            assertEquals(24, preset.getInt("paddingRight"))
            assertFalse(preset.getBoolean("showHeaderLine"))
            assertFalse(preset.getBoolean("showFooterLine"))
        }
    }

    private fun assertReadingState() {
        assertEquals(27, ReadBookConfig.textSize)
        assertEquals(21, ReadBookConfig.lineSpacingExtra)
        assertEquals(10, ReadBookConfig.paragraphSpacing)
        assertEquals("omi-test-font", ReadBookConfig.textFont)
        assertEquals(8, ReadBook.durChapterIndex)
        assertEquals(1234, ReadBook.durChapterPos)
    }

    private fun selectThemeRow(name: String) {
        val tap = click()
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        fun animationTime(resource: Int): Long =
            (AnimationUtils.loadAnimation(context, resource).computeDurationHint() * scale).toLong() + 32L
        onView(withText(name)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = tap.constraints
            override fun getDescription() = "tap the theme row after its window animation settles"
            override fun perform(uiController: UiController, view: View) {
                // WindowManager animations are outside Espresso's main-loop idling.
                // Keep the actual input gesture and all resulting-state assertions.
                uiController.loopMainThreadForAtLeast(animationTime(R.anim.omi_sheet_enter))
                tap.perform(uiController, view)
                uiController.loopMainThreadForAtLeast(animationTime(R.anim.omi_sheet_exit))
            }
        })
        onView(withText("同时调整书架和书页配色。")).check(doesNotExist())
    }
}
