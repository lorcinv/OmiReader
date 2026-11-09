package io.legado.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.MobiFile
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.omi.OmiLibraryState
import io.legado.app.ui.omi.OmiReadingOptions
import io.legado.app.ui.omi.OmiSheet
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OmiV2InstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val token = UUID.randomUUID().toString()
    private val books = mutableListOf<Book>()
    private val files = mutableListOf<File>()
    private var library: String? = null
    private var language: String? = null
    private var animation = 1
    private var shared = ReadBookConfig.shareConfig.copy()

    @Before fun setup() {
        library = context.defaultSharedPreferences.getString("omiLibraryV2", null)
        language = context.defaultSharedPreferences.getString(PreferKey.language, null)
        animation = context.defaultSharedPreferences.getInt("omiPageAnimation", 1)
        context.defaultSharedPreferences.edit().remove("omiLibraryV2").putString(PreferKey.language, "zh").commit()
        instrumentation.runOnMainSync {
            AppContextWrapper.updateApplicationLocale(context)
            OmiReadingOptions.ensureDefaults(context)
            shared = ReadBookConfig.shareConfig.copy()
        }
    }

    @After fun cleanup() {
        books.forEach { book ->
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            context.defaultSharedPreferences.edit().remove("omiProgress:${book.bookUrl}").commit()
        }
        files.forEach { it.delete() }
        MobiFile.clear()
        context.defaultSharedPreferences.edit().putString("omiLibraryV2", library).putString(PreferKey.language, language).putInt("omiPageAnimation", animation).commit()
        instrumentation.runOnMainSync { ReadBookConfig.shareConfig = shared; ReadBookConfig.save(); AppContextWrapper.updateApplicationLocale(context) }
    }

    @Test fun libraryPagesAndLayoutChangesKeepTheActivityAndRecyclerView() {
        seed(27)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { count(scenario) == 24 }
            var original: MainActivity? = null
            var recycler: RecyclerView? = null
            scenario.onActivity { activity ->
                original = activity; recycler = visibleRecycler(activity)
                assertEquals(2, (recycler!!.layoutManager as GridLayoutManager).spanCount)
                assertFalse(recycler!!.isVerticalScrollBarEnabled)
            }
            tap(R.string.omi_more, description = true); tap(R.string.omi_list); tap(R.string.omi_done)
            scenario.onActivity { activity ->
                assertSame(original, activity); assertSame(recycler, visibleRecycler(activity))
                assertEquals(1, (recycler!!.layoutManager as GridLayoutManager).spanCount)
            }
            tap(R.string.next_page, description = true)
            await { count(scenario) == 3 }
            scenario.recreate()
            saveFrame("page-after-recreate")
            await { count(scenario) == 3 }
            tap(R.string.prev_page, description = true)
            await { count(scenario) == 24 }
        }
    }

    @Test fun foldersTagsAndDetailsAreReachableFromTheLibrary() {
        seed(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { count(scenario) == 1 }
            tap(R.string.omi_more, true); tap(R.string.omi_new_folder)
            tap(R.string.omi_done)
            onView(withHint(R.string.omi_folder)).check(matches(hasErrorText(context.getString(R.string.omi_name_required))))
            onView(withText(R.string.omi_save)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withHint(R.string.omi_folder)).perform(replaceText("Fiction"), closeSoftKeyboard())
            tap(R.string.omi_done)
            await { count(scenario) == 2 }
            tap(R.string.omi_new_tag, true)
            onView(withHint(R.string.omi_tags)).perform(replaceText("Travel"), closeSoftKeyboard())
            tap(R.string.omi_done)
            scenario.onActivity { activity ->
                val row = visibleRecycler(activity).findViewHolderForAdapterPosition(1)!!.itemView
                views(row).first { it.contentDescription == activity.getString(R.string.omi_details) }.performClick()
            }
            tap(R.string.omi_move_folder)
            onView(allOf(withText("Fiction"), isDisplayed())).perform(click())
            await { count(scenario) == 1 }
            onView(allOf(withText("Fiction"), isDisplayed())).perform(click())
            await { count(scenario) == 1 }
            scenario.onActivity { activity ->
                val row = visibleRecycler(activity).findViewHolderForAdapterPosition(0)!!.itemView
                views(row).first { it.contentDescription == activity.getString(R.string.omi_details) }.performClick()
            }
            tap(R.string.omi_tags)
            onView(allOf(withText("Travel"), isDisplayed(), isAssignableFrom(android.widget.CheckBox::class.java))).perform(click())
            tap(R.string.omi_done)
            scenario.recreate()
            await { count(scenario) == 1 }
            val state = OmiLibraryState(context)
            assertEquals("Fiction", state.folders.single().name)
            assertEquals(state.folders.single().id, state.bookFolders[books.single().bookUrl])
            assertTrue(state.bookTags[books.single().bookUrl]!!.contains(state.tags.single().id))
        }
    }

    @Test fun comicNavigationUsesGrayIdleAndBrightSelectedWithoutAPill() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun check(selected: Int) = scenario.onActivity { activity ->
                val bar = activity.findViewById<io.legado.app.ui.omi.OmiBottomBar>(R.id.bottom_navigation_view)
                assertEquals(selected, bar.selectedItemId)
                for (i in 0 until bar.childCount) {
                    val row = bar.getChildAt(i) as android.widget.LinearLayout
                    assertEquals(if (row.id == selected) 1f else .68f, row.alpha, .001f)
                    assertNull(row.background)
                    assertEquals(row.id == selected, (row.getChildAt(1) as TextView).typeface.isBold)
                }
            }
            check(R.id.menu_bookshelf)
            onView(withId(R.id.menu_discovery)).perform(click()); check(R.id.menu_discovery)
            onView(withId(R.id.menu_rss)).perform(click()); check(R.id.menu_rss)
        }
    }

    @Test fun longPressFloatingActionsAddToShelfAndMoveIntoItsFolder() {
        seed(1)
        val original = books.single().bookUrl
        val state = OmiLibraryState(context)
        val shelf = state.addTag("Study shelf")!!
        val folder = state.addFolder("Novels", "", shelf.id)!!
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { count(scenario) == 1 }
            scenario.onActivity { visibleRecycler(it).findViewHolderForAdapterPosition(0)!!.itemView.performLongClick() }
            onView(withContentDescription(R.string.omi_add_shelf)).perform(click())
            onView(allOf(withText("Study shelf"), isAssignableFrom(android.widget.CheckBox::class.java))).perform(click())
            tap(R.string.omi_done)
            onView(withContentDescription(R.string.omi_done)).perform(click())
            onView(allOf(withText("Study shelf"), isDisplayed())).perform(click())
            await { count(scenario) == 2 }
            saveFrame("shelf-selected")
            scenario.onActivity { visibleRecycler(it).findViewHolderForAdapterPosition(1)!!.itemView.performLongClick() }
            SystemClock.sleep(300)
            saveFrame("comic-floating-actions")
            onView(withContentDescription(R.string.omi_move_folder)).perform(click())
            onView(allOf(withText("Novels"), isDisplayed())).perform(click())
            await { count(scenario) == 1 }
            saveFrame("comic-folder-stack")
            onView(allOf(withText("Novels"), isDisplayed())).perform(click())
            await { count(scenario) == 1 }
            scenario.onActivity { activity ->
                assertTrue(views(visibleRecycler(activity).findViewHolderForAdapterPosition(0)!!.itemView).filterIsInstance<TextView>().any { it.text == books.single().name })
            }
            assertEquals(folder.id, OmiLibraryState(context).folderFor(original, shelf.id))
            assertEquals(original, appDb.bookDao.getBook(original)!!.bookUrl)
            androidx.test.espresso.Espresso.pressBack()
            await { count(scenario) == 1 }
            onView(allOf(withText("Novels"), isDisplayed())).check(matches(isDisplayed()))
        }
    }

    @Test fun comicGridSliderUpdatesTheAttachedGridLive() {
        seed(3)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { count(scenario) == 3 }
            var recycler: RecyclerView? = null
            scenario.onActivity { recycler = visibleRecycler(it) }
            tap(R.string.omi_more, true)
            onView(withContentDescription(R.string.omi_grid_columns)).perform(swipeRight())
            scenario.onActivity { activity ->
                assertSame(recycler, visibleRecycler(activity))
                assertTrue((visibleRecycler(activity).layoutManager as GridLayoutManager).spanCount >= 5)
            }
            saveFrame("comic-grid-slider")
            tap(R.string.omi_done)
        }
    }

    @Test fun longPressDragPersistsOrderWithoutOpeningDetails() {
        seed(3)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { count(scenario) == 3 }
            scenario.onActivity { activity -> visibleRecycler(activity).findViewHolderForAdapterPosition(0)!!.itemView.performLongClick() }
            onView(withContentDescription(R.string.omi_move_folder)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.omi_add_shelf)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.omi_sort)).perform(click())
            await { var focus = false; scenario.onActivity { focus = it.window.decorView.hasWindowFocus() }; focus }
            var from = floatArrayOf(); var to = floatArrayOf()
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.hasWindowFocus())
                val done = views(activity.window.decorView).first { it.contentDescription == activity.getString(R.string.omi_done) }
                val bounds = android.graphics.Rect(); done.getGlobalVisibleRect(bounds)
                val safeBottom = ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                assertTrue("Sorting actions must stay above Android navigation", bounds.bottom <= activity.resources.displayMetrics.heightPixels - safeBottom)
                val rv = visibleRecycler(activity)
                fun center(index: Int): FloatArray {
                    val view = rv.findViewHolderForAdapterPosition(index)!!.itemView
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    return floatArrayOf(xy[0] + view.width / 2f, xy[1] + view.height / 3f)
                }
                from = center(0); to = center(1)
            }
            SystemClock.sleep(400)
            saveFrame("before-drag")
            val start = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(it); it.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, from[0], from[1]); SystemClock.sleep(650)
            for (i in 1..20) { send(MotionEvent.ACTION_MOVE, from[0] + (to[0] - from[0]) * i / 20, from[1]); SystemClock.sleep(16) }
            send(MotionEvent.ACTION_UP, to[0], to[1])
            saveFrame("after-drag")
            await { OmiLibraryState(context).order["|"]?.firstOrNull() == "b:${books[1].bookUrl}" }
            scenario.recreate()
            await { count(scenario) == 3 }
            scenario.onActivity { activity ->
                val first = visibleRecycler(activity).findViewHolderForAdapterPosition(0)!!.itemView
                assertTrue(views(first).filterIsInstance<TextView>().any { it.text == books[1].name })
            }
        }
    }

    @Test fun japaneseSwitchKeepsProcessAndSelectedDestination() {
        val pid = Process.myPid()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.menu_my_config)).perform(click())
            tap(R.string.omi_language)
            onView(withText("日本語")).perform(click())
            await {
                var correct = false
                scenario.onActivity { correct = it.resources.configuration.locales[0].language == "ja" }
                correct
            }
            scenario.onActivity { activity ->
                assertEquals(pid, Process.myPid())
                assertEquals(3, activity.findViewById<ViewPager>(R.id.view_pager_main).currentItem)
                assertEquals("言語", activity.getString(R.string.omi_language))
            }
            onView(allOf(withText("言語"), isDisplayed())).perform(click())
            onView(withText("简体中文")).perform(click())
        }
    }

    @Test fun pageEffectAppliesToAllBooksAcrossReopening() {
        seed(2)
        books[0].setPageAnim(1); books[1].setPageAnim(3)
        appDb.bookDao.update(*books.toTypedArray())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.menu_my_config)).perform(click())
            onView(allOf(withText(R.string.omi_reading_settings), isDisplayed())).perform(click())
            tap(R.string.omi_page_effect_none); tap(R.string.omi_done)
            assertTrue(books.all { it.getPageAnim() == 4 })
            scenario.recreate()
            assertTrue(books.all { appDb.bookDao.getBook(it.bookUrl)!!.getPageAnim() == 4 })
            onView(allOf(withText(R.string.omi_reading_settings), isDisplayed())).perform(click())
            tap(R.string.omi_page_colors)
            tap(R.string.background_color)
            scenario.onActivity { activity ->
                assertNotNull("The custom page color picker must actually open", activity.supportFragmentManager.findFragmentByTag("omi-page-color"))
            }
            androidx.test.espresso.Espresso.pressBack()
        }
    }

    @Test fun fullHeightSheetCanReachEveryActionWithoutADragHandle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var sheet: OmiSheet? = null
            scenario.onActivity { activity ->
                sheet = OmiSheet(activity, "Long contents").apply { repeat(40) { i -> action("Chapter $i") {} }; show() }
            }
            await { var expanded = false; scenario.onActivity { expanded = sheet!!.behavior.state == BottomSheetBehavior.STATE_EXPANDED }; expanded }
            scenario.onActivity { activity ->
                val panel = sheet!!.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
                assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheet!!.behavior.state)
                assertTrue(panel.height > activity.resources.displayMetrics.heightPixels * .65f)
                assertTrue(panel.height <= activity.resources.displayMetrics.heightPixels * .91f)
                sheet!!.dismiss()
            }
        }
    }

    @Test fun legacyGroupsMigrateToTagsWithoutMovingSourceFiles() {
        seed(1)
        context.defaultSharedPreferences.edit().remove("omiGroupsMigrated").commit()
        val grouped = books.single().apply { group = 8 }
        val state = OmiLibraryState(context)
        state.migrateGroups(listOf(BookGroup(8, "Legacy")), listOf(grouped))
        val restored = OmiLibraryState(context)
        assertEquals("Legacy", restored.tags.single().name)
        assertTrue(restored.bookTags[grouped.bookUrl]!!.contains(restored.tags.single().id))
        assertTrue(restored.folders.isEmpty())
    }

    @Test fun realMobiImageUsesViewportInsteadOfTextMargins() = runBlocking {
        val file = File(context.cacheDir, "mobi-image-$token.mobi").also { files += it }
        instrumentation.context.assets.open("local-formats/omi-image.mobi").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = LocalBook.importFile(Uri.fromFile(file)).also { books += it }
        val chapters = LocalBook.getChapterList(book)
        val pattern = Regex("<img[^>]*src=\"([^\"]+)\"[^>]*>")
        val match = chapters.firstNotNullOfOrNull { chapter -> pattern.find(MobiFile.getContent(book, chapter).orEmpty())?.let { chapter to it.value } }
        assertNotNull("Fixture must contain a real MOBI image", match)
        instrumentation.runOnMainSync {
            ReadBookConfig.paddingLeft = 32; ReadBookConfig.paddingRight = 32
            ChapterProvider.upViewSize(1080, 1600); ChapterProvider.upStyle()
        }
        val chapter = ChapterProvider.getTextChapterAsync(this, book, match!!.first, "Image page", BookContent(false, listOf(match.second), null), chapters.size)
        withTimeout(15000) { for (page in chapter.layoutChannel) { /* wait for the real image decode and asynchronous layout */ } }
        val page = chapter.pages.single()
        assertTrue(page.imageOnly)
        val line = page.lines.single()
        val image = line.columns.single() as ImageColumn
        assertTrue("One fitted image dimension must meet the viewport edge", abs(image.end - image.start - 1080) <= 2 || abs(line.height - 1600) <= 2)
        assertTrue("Text gutters must not clip the image", image.start < 1 || line.lineTop < 1)
        assertEquals(0, page.paddingTop)
    }

    @Test fun nativeReaderHidesSystemBarsAndUsesOneToolbarRow() {
        val file = File(context.cacheDir, "reader-$token.txt").also { files += it }
        file.writeText("第一章 海风\n" + "灯塔与书页，阅读中的每一行都应该清晰。\n".repeat(80) + "第二章 远方\n" + "继续阅读。\n".repeat(30))
        val book = LocalBook.importFile(Uri.fromFile(file)).also { books += it }
        val chapters = LocalBook.getChapterList(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
        ActivityScenario.launch<ReadBookActivity>(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)).use { scenario ->
            await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true }
            await {
                var hidden = false
                scenario.onActivity { hidden = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false }
                hidden
            }
            await {
                var ready = false
                scenario.onActivity { activity -> ready = views(activity.window.decorView).filterIsInstance<TextView>().any { it.id == R.id.tv_footer_left && it.text.toString().contains("海风") } }
                ready
            }
            scenario.onActivity { activity ->
                val footers = views(activity.window.decorView).filterIsInstance<TextView>().filter { it.id == R.id.tv_footer_left }.toList()
                assertTrue("Chapter title belongs at the bottom left", footers.any { it.text.toString().contains("海风") })
                assertTrue("No repeated book title at the bottom", footers.none { it.text.toString() == book.name })
                assertTrue(views(activity.window.decorView).filter { it.id == R.id.ll_header }.all { it.visibility == View.GONE })
            }
            scenario.onActivity { it.showMenuBar() }
            onView(withId(R.id.ll_catalog)).check(matches(isDisplayed()))
            SystemClock.sleep(350)
            saveFrame("native-toolbar")
            scenario.onActivity { activity ->
                val row = activity.findViewById<ViewGroup>(R.id.ll_bottom_bg)
                assertEquals(4, (0 until row.childCount).count { row.getChildAt(it).visibility == View.VISIBLE })
                val icons = listOf(R.id.iv_catalog, R.id.iv_font, R.id.iv_setting, R.id.fabNightTheme).map { activity.findViewById<View>(it) }
                assertEquals(1, icons.map { it.width to it.height }.distinct().size)
                assertTrue(icons.all { it.paddingTop == 0 && it.paddingBottom == 0 })
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ll_floating_button).visibility)
                assertTrue(activity.findViewById<View>(R.id.seek_read_page).width > activity.resources.displayMetrics.widthPixels / 2)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.omi_bookmark).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.title_bar_addition).visibility)
                val menu = activity.findViewById<io.legado.app.ui.widget.TitleBar>(R.id.title_bar).toolbar.menu
                assertEquals(listOf(R.id.menu_omi_reader_bookmark, R.id.menu_omi_reader_more), (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }.map { it.itemId })
                assertFalse(ViewCompat.getRootWindowInsets(activity.window.decorView)!!.isVisible(WindowInsetsCompat.Type.statusBars()))
            }
            val points = IntArray(4)
            scenario.onActivity { activity ->
                val bar = activity.findViewById<android.widget.SeekBar>(R.id.seek_read_page)
                assertTrue("Reading fixture must have multiple pages", bar.max >= 2)
                val location = IntArray(2); bar.getLocationOnScreen(location)
                points[0] = location[0] + bar.paddingLeft + 2
                points[1] = location[1] + bar.height / 2
                points[2] = location[0] + (bar.width * .72f).toInt()
            }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Int) {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), points[1].toFloat(), 0).also {
                    instrumentation.sendPointerSync(it); it.recycle()
                }
                instrumentation.waitForIdleSync()
            }
            touch(MotionEvent.ACTION_DOWN, points[0]); touch(MotionEvent.ACTION_MOVE, points[2])
            SystemClock.sleep(150)
            scenario.onActivity { activity ->
                val bar = activity.findViewById<android.widget.SeekBar>(R.id.seek_read_page)
                assertTrue("Dragging must update the live page", bar.progress > 0)
                points[3] = bar.progress
                assertEquals("${bar.progress + 1}/${bar.max + 1}", activity.findViewById<TextView>(R.id.tv_read_progress).text.toString())
                val image = android.graphics.Bitmap.createBitmap(bar.width, bar.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(image)
                canvas.drawColor(android.graphics.Color.parseColor(io.legado.app.help.config.OmiTheme.current(activity).surface))
                bar.draw(canvas)
                val contrast = androidx.core.graphics.ColorUtils.calculateContrast(image.getPixel(bar.width / 4, bar.height / 2), image.getPixel(bar.width * 9 / 10, bar.height / 2))
                assertTrue("Filled and remaining track need visible contrast, got $contrast", contrast > 1.5)
                image.recycle()
            }
            saveFrame("reader-live-progress")
            touch(MotionEvent.ACTION_UP, points[2])
            await { ReadBook.durPageIndex == points[3] }
            onView(withId(R.id.menu_omi_reader_more)).perform(click())
            onView(withText(R.string.omi_fullscreen)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.omi_page_colors)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText(R.string.omi_page_turn)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText(R.string.omi_reading_theme)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            saveFrame("reader-flat-more")
            tap(R.string.omi_done)
            scenario.onActivity { it.showReadStyle() }
            onView(withId(R.id.dsb_text_size)).check(matches(isDisplayed()))
            onView(withId(R.id.dsb_paragraph_spacing)).check(matches(isDisplayed()))
            saveFrame("reader-restored-typesetting")
            androidx.test.espresso.Espresso.pressBack()
            onView(withId(R.id.ll_catalog)).perform(click())
            SystemClock.sleep(350)
            saveFrame("native-contents")
            onView(withContentDescription(R.string.omi_bookmarks)).perform(click())
            onView(withText(R.string.omi_empty_bookmarks)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.omi_catalog)).perform(click())
            onView(allOf(withText(chapters.last().title), isDisplayed())).perform(click())
            await { ReadBook.durChapterIndex == chapters.lastIndex }
        }
    }

    @Test fun bookmarkTabOpensTheStoredChapterAndPosition() {
        val file = File(context.cacheDir, "bookmark-$token.txt").also { files += it }
        file.writeText("第一章 海风\n" + "第一章的阅读文字。\n".repeat(100) + "第二章 远方\n" + "第二章的书签位置。\n".repeat(200))
        val book = LocalBook.importFile(Uri.fromFile(file)).also { books += it }
        val chapters = LocalBook.getChapterList(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        val mark = Bookmark(bookName = book.name, bookAuthor = book.author, chapterIndex = chapters.lastIndex,
            chapterPos = 850, chapterName = chapters.last().title, bookText = "Bookmark destination")
        appDb.bookmarkDao.insert(mark)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.menu_discovery)).perform(click())
                onView(allOf(withText(book.name), isDisplayed())).perform(click())
                await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true }
                assertEquals(mark.chapterIndex, ReadBook.durChapterIndex)
                assertEquals(mark.chapterPos, ReadBook.durChapterPos)
                androidx.test.espresso.Espresso.pressBack()
                await { appDb.bookDao.getBook(book.bookUrl)?.durChapterPos == mark.chapterPos }
                onView(withId(R.id.menu_bookshelf)).perform(click())
                onView(allOf(withText(book.name), isDisplayed())).perform(click())
                await { ReadBook.curTextChapter?.isCompleted == true }
                assertEquals(mark.chapterIndex, ReadBook.durChapterIndex)
                assertEquals(mark.chapterPos, ReadBook.durChapterPos)
                androidx.test.espresso.Espresso.pressBack()
            }
        } finally { appDb.bookmarkDao.delete(mark) }
    }

    private fun seed(count: Int) {
        repeat(count) { i -> books += Book(bookUrl = "omi-ui-$token:$i", name = "Book ${i.toString().padStart(2, '0')}", author = "Omi", originName = "fixture.txt", type = BookType.local, order = i) }
        appDb.bookDao.insert(*books.toTypedArray())
    }
    private fun saveFrame(name: String) {
        val dir = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    private fun tap(resource: Int, description: Boolean = false) {
        val interaction = onView(allOf(if (description) withContentDescription(resource) else withText(resource), isDisplayed()))
        if (!description && resource != R.string.omi_language) interaction.inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
        interaction.perform(object : androidx.test.espresso.ViewAction {
            private val tap = click()
            override fun getConstraints() = tap.constraints
            override fun getDescription() = "Tap after window transitions settle"
            override fun perform(controller: androidx.test.espresso.UiController, view: View) {
                controller.loopMainThreadForAtLeast(300)
                tap.perform(controller, view)
                controller.loopMainThreadForAtLeast(300)
            }
        })
    }
    private fun visibleRecycler(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<RecyclerView>().first { it.id == R.id.rv_bookshelf && it.getGlobalVisibleRect(android.graphics.Rect()) }
    private fun count(scenario: ActivityScenario<MainActivity>): Int {
        var count = -1; scenario.onActivity { count = visibleRecycler(it).adapter!!.itemCount }; return count
    }
    private fun views(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) } }
    private fun await(condition: () -> Boolean) {
        val limit = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < limit) { if (condition()) return; SystemClock.sleep(100) }
        fail("The expected user-visible state did not arrive")
    }
}
