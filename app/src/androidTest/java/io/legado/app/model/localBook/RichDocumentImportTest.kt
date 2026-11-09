package io.legado.app.model.localBook

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.ui.book.read.RichDocumentActivity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the Android XML implementation, the shared book APIs and the actual script-free WebView. */
@RunWith(AndroidJUnit4::class)
class RichDocumentImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val token = UUID.randomUUID().toString()
    private lateinit var directory: File
    private val books = mutableListOf<Book>()
    private val marks = mutableListOf<Bookmark>()

    @Before fun setup() {
        directory = File(context.cacheDir, "omi-rich-test-$token")
        check(directory.mkdirs())
    }

    @After fun cleanup() {
        if (marks.isNotEmpty()) appDb.bookmarkDao.delete(*marks.toTypedArray())
        books.forEach { book ->
            val epub = ConvertedDocument.file(book)
            LocalBook.deleteBook(book, false)
            book.removeLocalUriCache()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            context.getSharedPreferences("omi-rich-reader", Context.MODE_PRIVATE).edit().apply {
                val key = book.bookUrl.substringAfterLast('/')
                remove("position:$key"); remove("chapter:$key"); remove("saved:$key")
            }.commit()
            epub.delete()
        }
        directory.deleteRecursively()
    }

    @Test fun androidXmlImportsDocxWithHeadingsListsTableAndRealPng() {
        val book = importFixture("docx")
        assertEquals("Omi 文档检查 & 阅读", book.name)
        val document = ConvertedDocument.open(book)
        assertEquals(3, document.chapters.size)
        val first = Jsoup.parseBodyFragment(document.chapters[0].html)
        assertEquals(1, first.select("h1").size)
        assertEquals(1, first.select("strong").size)
        assertEquals(1, first.select("em").size)
        val second = Jsoup.parseBodyFragment(document.chapters[1].html)
        assertEquals(1, second.select("ul ul").size)
        assertEquals("3", second.selectFirst("ol")!!.attr("start"))
        assertEquals(1, second.select("img").size)
        val third = Jsoup.parseBodyFragment(document.chapters[2].html)
        assertEquals(2, third.select("th").size)
        assertEquals("2", third.selectFirst("td[colspan]")!!.attr("colspan"))
        val bytes = document.readAsset("OEBPS/images/image-1.png")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        try {
            assertEquals(144, bitmap.width)
            assertEquals(72, bitmap.height)
            assertEquals(Color.rgb(75, 102, 155), bitmap.getPixel(20, 20))
            assertEquals(Color.rgb(230, 223, 213), bitmap.getPixel(100, 20))
        } finally { bitmap.recycle() }
        checkSharedReading(book)
    }

    @Test fun androidXmlRejectsExternalEntitiesWithoutInstallingABook() {
        val output = File(directory, "rejected")
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(("<?xml version=\"1.0\"?><!DOCTYPE document [<!ENTITY secret SYSTEM " +
                "'file:///data/private.xml'>]><document>&secret;</document>").toByteArray())
            zip.closeEntry()
        }
        val error = runCatching {
            DocumentImportConverter.convert(ByteArrayInputStream(zipBytes.toByteArray()), "rejected.docx", output) { }
        }.exceptionOrNull()
        assertNotNull("DOCTYPE must be rejected by Android too", error)
        assertTrue(error!!.localizedMessage.orEmpty().contains("DTD"))
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun docxReaderActuallyRendersEmbeddedImageAndTable() {
        val book = importFixture("docx")
        ActivityScenario.launch<RichDocumentActivity>(readerIntent(book)).use { scenario ->
            awaitChapter(scenario, 0)
            val heading = servedDocument(scenario)
            assertEquals(1, heading.select("h1").size)
            assertEquals(1, heading.select("strong").size)
            assertRenderedText(scenario, "这是粗体")
            click(scenario, "下一章")
            awaitChapter(scenario, 1)
            assertRenderedText(scenario, "嵌套的列表内容")
            val lists = servedDocument(scenario)
            assertEquals(1, lists.select("ul ul").size)
            assertEquals(1, lists.select("img[src^=https://omi-reader.invalid/]").size)
            val image = screenshot(scenario, "docx-image")
            try {
                assertTrue("The local DOCX PNG must be visible as adjacent blue and sand blocks", containsImageTiles(image))
            } finally { image.recycle() }
            click(scenario, "下一章")
            awaitChapter(scenario, 2)
            assertEquals(1, servedDocument(scenario).select("table").size)
            assertRenderedText(scenario, "合并两列的说明")
            screenshot(scenario, "docx-table").recycle()
        }
        await("DOCX chapter must be persisted") { appDb.bookDao.getBook(book.bookUrl)?.durChapterIndex == 2 }
        ActivityScenario.launch<RichDocumentActivity>(readerIntent(book)).use { scenario -> awaitChapter(scenario, 2) }
        checkSharedReading(book)
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun markdownReaderRendersListsQuoteCodeTableLinksAndBlocksActiveContent() {
        val book = importFixture("md")
        ActivityScenario.launch<RichDocumentActivity>(readerIntent(book)).use { scenario ->
            awaitChapter(scenario, 0)
            assertEquals(1, servedDocument(scenario).select("h1").size)
            assertRenderedText(scenario, "粗体")
            click(scenario, "下一章")
            awaitChapter(scenario, 1)
            val body = servedDocument(scenario)
            assertEquals(1, body.select("blockquote").size)
            assertEquals(1, body.select("pre code").size)
            assertEquals(1, body.select("ul ul").size)
            assertEquals(2, body.select("ol li").size)
            assertRenderedText(scenario, "println(book)")
            screenshot(scenario, "markdown-list-quote-code").recycle()
            click(scenario, "下一章")
            awaitChapter(scenario, 2)
            val table = servedDocument(scenario)
            assertEquals(1, table.select("table").size)
            assertEquals(1, table.select("a[href=https://example.com/reading]").size)
            assertTrue(table.select("script,iframe,object,img").isEmpty())
            assertFalse(table.html().contains("href=\"javascript:"))
            assertTrue(table.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content").contains("script-src 'none'"))
            assertRenderedText(scenario, "Markdown")
            screenshot(scenario, "markdown-table-link").recycle()
            scenario.onActivity { activity ->
                val web = webView(activity)
                assertFalse(web.settings.javaScriptEnabled)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                assertTrue(web.settings.blockNetworkLoads)
                val response = web.webViewClient.shouldInterceptRequest(web, Request(Uri.parse("https://example.com/remote.png")))!!
                assertEquals(403, response.statusCode)
                response.data.close()
            }
        }
        checkSharedReading(book)
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun scrollPositionAndSharedBookmarkReopenTheCorrectChapter() {
        val source = File(directory, "long-$token.markdown").apply {
            writeText(buildString {
                appendLine("# 阅读进度")
                repeat(80) { appendLine("\n第 $it 段。书页和记忆都停留在这里，下一次打开还应回到原处。") }
                appendLine("\n## 后半部")
                repeat(80) { appendLine("\n后半部第 $it 段。独立章节保持自己的阅读位置。") }
            })
        }
        val book = importBook(source)
        ActivityScenario.launch<RichDocumentActivity>(readerIntent(book)).use { scenario ->
            awaitChapter(scenario, 0)
            scenario.onActivity { webView(it).scrollTo(0, 700) }
            await("Reader must scroll") {
                var y = 0
                scenario.onActivity { y = webView(it).scrollY }
                y > 600
            }
        }
        await("Character position must reach the shared book record") { (appDb.bookDao.getBook(book.bookUrl)?.durChapterPos ?: 0) > 0 }
        ActivityScenario.launch<RichDocumentActivity>(readerIntent(book)).use { scenario ->
            awaitChapter(scenario, 0)
            await("Reopening must restore the vertical position") {
                var y = 0
                scenario.onActivity { y = webView(it).scrollY }
                y > 500
            }
        }
        val chapters = LocalBook.getChapterList(book)
        val mark = Bookmark(bookName = book.name, bookAuthor = book.author, chapterIndex = 1,
            chapterPos = 0, chapterName = chapters[1].title, bookText = "后半部", content = token)
        marks.add(mark)
        appDb.bookmarkDao.insert(mark)
        val saved = appDb.bookmarkDao.getByBook(book.name, book.author).single { it.content == token }
        ActivityScenario.launch<RichDocumentActivity>(readerIntent(book).putExtra("index", saved.chapterIndex)
            .putExtra("chapterPos", saved.chapterPos)).use { scenario ->
            awaitChapter(scenario, 1)
            assertRenderedText(scenario, "独立章节")
        }
        assertTrue(BookHelp.getContent(book, chapters[1]).orEmpty().contains("独立章节"))
    }

    private fun importFixture(extension: String): Book {
        val source = File(directory, "document-$token.$extension")
        instrumentation.context.assets.open("omi-import/document-rich.$extension").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        return importBook(source)
    }

    private fun importBook(source: File): Book = LocalBook.importFile(Uri.fromFile(source)).also { book ->
        books.add(book)
        assertTrue(ConvertedDocument.isConverted(book))
        val chapters = LocalBook.getChapterList(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        book.save()
        assertEquals(chapters.indices.map { "omi-rich:$it" }, chapters.map { it.url })
    }

    private fun checkSharedReading(book: Book) {
        val chapters = LocalBook.getChapterList(book)
        val archive = ConvertedDocument.open(book)
        assertEquals(archive.chapters.map { it.title }, chapters.map { it.title })
        chapters.forEachIndexed { index, chapter ->
            assertEquals(archive.chapters[index].text, LocalBook.getContent(book, chapter))
            // This is the exact content provider used by the existing full-book search and TTS.
            assertEquals(archive.chapters[index].text, BookHelp.getContent(book, chapter))
        }
    }

    private fun readerIntent(book: Book) = Intent(context, RichDocumentActivity::class.java).putExtra("bookUrl", book.bookUrl)

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }

    private fun webView(activity: RichDocumentActivity): WebView =
        views(activity.window.decorView).filterIsInstance<WebView>().single()

    private fun click(scenario: ActivityScenario<RichDocumentActivity>, description: String) {
        var url = ""
        var target = 0
        scenario.onActivity { activity ->
            url = activity.intent.getStringExtra("bookUrl")!!
            target = Uri.parse(webView(activity).url!!).path!!.substringAfterLast('/').toInt() + 1
        }
        val title = appDb.bookChapterDao.getChapterList(url)[target].title
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(WebView::class.java))
            .perform(androidx.test.espresso.action.ViewActions.click())
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(io.legado.app.R.string.omi_catalog))
            .perform(androidx.test.espresso.action.ViewActions.click())
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(title))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(object : androidx.test.espresso.ViewAction {
                private val tap = androidx.test.espresso.action.ViewActions.click()
                override fun getConstraints() = tap.constraints
                override fun getDescription() = "Tap chapter after the bottom sheet finishes expanding"
                override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                    controller.loopMainThreadForAtLeast(400)
                    tap.perform(controller, view)
                }
            })
    }

    private fun awaitChapter(scenario: ActivityScenario<RichDocumentActivity>, index: Int) {
        try { await("Chapter $index must finish layout and restore its position") {
            var loaded = false
            scenario.onActivity { activity ->
                val web = webView(activity)
                loaded = web.progress == 100 && web.isEnabled && web.isShown &&
                    Uri.parse(web.url.orEmpty()).path == "/chapter/$index"
            }
            loaded
        } } catch (error: AssertionError) {
            scenario.onActivity { activity ->
                val web = webView(activity)
                println("DOC_LAYOUT expected=$index url=${web.url} progress=${web.progress} enabled=${web.isEnabled} shown=${web.isShown}")
            }
            screenshot(scenario, "navigation-failure-$index").recycle()
            throw error
        }
        awaitDrawnFrame(scenario)
    }

    @androidx.annotation.RequiresApi(26)
    private fun servedDocument(scenario: ActivityScenario<RichDocumentActivity>): Document {
        var html = ""
        scenario.onActivity { activity ->
            val web = webView(activity)
            val response = web.webViewClient.shouldInterceptRequest(web, Request(Uri.parse(web.url!!)))!!
            assertEquals(200, response.statusCode)
            html = response.data.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return Jsoup.parse(html)
    }

    private fun assertRenderedText(scenario: ActivityScenario<RichDocumentActivity>, query: String) {
        val found = CountDownLatch(1)
        var matches = 0
        scenario.onActivity { activity ->
            webView(activity).apply {
                setFindListener { _, count, done -> if (done) { matches = count; found.countDown() } }
                findAllAsync(query)
            }
        }
        assertTrue("WebView did not finish searching for $query", found.await(10, TimeUnit.SECONDS))
        assertTrue("The rendered page is missing $query", matches > 0)
        scenario.onActivity { webView(it).apply { clearMatches(); setFindListener(null) } }
    }

    @androidx.annotation.RequiresApi(26)
    private fun screenshot(scenario: ActivityScenario<RichDocumentActivity>, name: String): Bitmap {
        awaitDrawnFrame(scenario)
        // Copy the committed application surface, rather than a compositor frame that can
        // still show the previous loading overlay immediately after a successful find.
        val copied = CountDownLatch(1)
        var image: Bitmap? = null
        var copyResult = -1
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, image!!, { result ->
                copyResult = result
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue("The committed reader frame must be copied", copied.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, copyResult)
        val bitmap = image!!
        val folder = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(folder, "rich-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun awaitDrawnFrame(scenario: ActivityScenario<RichDocumentActivity>) {
        val drawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = webView(activity)
            if (Build.VERSION.SDK_INT >= 29 && web.isHardwareAccelerated) {
                web.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }
                web.invalidate()
            } else {
                web.postOnAnimation { web.postOnAnimation { drawn.countDown() } }
                web.invalidate()
            }
        }
        assertTrue("The reader must submit a real frame before pixel assertions", drawn.await(10, TimeUnit.SECONDS))
    }

    private fun containsImageTiles(bitmap: Bitmap): Boolean {
        return ImageTileAssertion.containsOriginalTiles(bitmap.width, bitmap.height, bitmap::getPixel)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private class Request(private val address: Uri) : WebResourceRequest {
        override fun getUrl() = address
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
