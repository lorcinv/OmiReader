package io.legado.app

import android.app.Application
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.association.ImportBookSourceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real import/database/network/rule-engine flow against an owned loopback site. */
@RunWith(AndroidJUnit4::class)
class OmiSourceInstrumentedTest {
    private lateinit var site: FixtureSite
    private val sourceKeys = mutableSetOf<String>()
    private val stores = mutableListOf<ViewModelStore>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val base get() = "http://127.0.0.1:${site.listeningPort}"

    @Before
    fun setup() {
        site = FixtureSite()
        site.start(5_000, true)
        assertTrue(site.listeningPort > 0)
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        runBlocking(Dispatchers.IO) { sourceKeys.forEach(SourceHelp::deleteBookSource) }
        site.stop()
    }

    @Test(timeout = 45_000)
    fun sourceUrlImportThenCssSearchTocAndPaginatedContent() {
        site.sourcesJson = "[${cssSourceJson()}]"
        // This is the same URL-import and selected-source save path as the UI.
        val source = importSource("$base/sources.json")
        assertEquals("Omi 本地验证书源", source.bookSourceName)
        assertEquals("@css:#books .book", source.getSearchRule().bookList)
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) {
                val results = WebBook.searchBookAwait(source, "海风", 2)
                assertEquals(1, results.size)
                val result = results.single()
                assertEquals("海风来信", result.name)
                assertEquals("OmiReader", result.author)
                assertEquals("$base/books/sea/index.html", result.bookUrl)
                val searchRequest = site.requests.first { it.path == "/search" }
                assertEquals("海风", searchRequest.parameters["keyword"])
                assertEquals("2", searchRequest.parameters["page"])

                val book = WebBook.getBookInfoAwait(source, result.toBook())
                assertEquals("$base/books/sea/toc.html", book.tocUrl)
                val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
                assertEquals(listOf("第一章 远方的信", "第二章 海边"), chapters.map { it.title })
                assertEquals("chapter-1.html", chapters[0].url)
                assertEquals("$base/books/sea/chapter-1.html", chapters[0].getAbsoluteURL())
                val content = WebBook.getContentAwait(source, book, chapters[0],
                    chapters[1].getAbsoluteURL(), needSave = false)
                assertTrue(content.contains("海风把一封信送到了窗前。"))
                assertTrue(content.contains("翻过这一页，灯塔还亮着。"))
                assertFalse(content.contains("站点导航"))
                assertFalse(content.contains("第二章的正文"))
                assertTrue(site.requests.any { it.path == "/books/sea/chapter-1-2.html" })
            }
        }
    }

    @Test(timeout = 40_000)
    fun pastedJsonSourceUsesJsonPathAndResolvesRelativeChapterUrls() {
        val source = importSource(jsonSourceJson())
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) {
                val result = WebBook.searchBookAwait(source, "灯塔").single()
                assertEquals("灯塔手记", result.name)
                assertEquals("测试作者", result.author)
                val book = WebBook.getBookInfoAwait(source, result.toBook())
                val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
                assertEquals("第一章 灯亮了", chapters.single().title)
                assertEquals("$base/json/books/lighthouse/chapter-1", chapters.single().getAbsoluteURL())
                val content = WebBook.getContentAwait(source, book, chapters.single(), needSave = false)
                assertTrue(content.contains("灯塔的光扫过平静的海面。"))
                assertEquals("灯塔", site.requests.first { it.path == "/json/search" }.parameters["keyword"])
            }
        }
    }

    @Test(timeout = 45_000)
    fun invalidImportMissingSearchAndEmptyTocExposeReadableErrors() {
        val invalid = newImporter()
        val importError = awaitImport(invalid, """{"bookSourceName":"缺少地址"}""")
        assertNotNull(importError)
        assertTrue(importError!!.contains("不是书源"))
        assertTrue(invalid.allSources.isEmpty())

        val missingSearch = importSource(JSONObject(cssSourceJson())
            .put("searchUrl", "").put("bookSourceUrl", "$base/missing-search").toString())
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) {
                val error = runCatching { WebBook.searchBookAwait(missingSearch, "书名") }.exceptionOrNull()
                assertNotNull(error)
                assertTrue(error!!.localizedMessage.orEmpty().contains("搜索url不能为空"))
            }
        }

        val emptyJson = JSONObject(cssSourceJson()).put("bookSourceUrl", "$base/empty-toc")
        emptyJson.getJSONObject("ruleToc").put("chapterList", "@css:.missing-chapter")
        val emptyToc = importSource(emptyJson.toString())
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) {
                val result = WebBook.searchBookAwait(emptyToc, "海风").single()
                val book = WebBook.getBookInfoAwait(emptyToc, result.toBook())
                val error = WebBook.getChapterListAwait(emptyToc, book).exceptionOrNull()
                assertNotNull("Empty table of contents must not appear as successful reading", error)
                assertFalse(error!!.localizedMessage.isNullOrBlank())
            }
        }
    }

    private fun newImporter(): ImportBookSourceViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return ImportBookSourceViewModel(application).also { model ->
            instrumentation.runOnMainSync {
                stores.add(ViewModelStore().apply { put("source-import", model) })
            }
        }
    }

    /** Waits for the real importer's LiveData; there are no timing sleeps. */
    private fun awaitImport(model: ImportBookSourceViewModel, input: String): String? {
        val completed = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val successObserver = Observer<Int> { completed.countDown() }
        val errorObserver = Observer<String> { error.set(it); completed.countDown() }
        instrumentation.runOnMainSync {
            model.successLiveData.observeForever(successObserver)
            model.errorLiveData.observeForever(errorObserver)
            model.importSource(input)
        }
        try {
            assertTrue("Source import did not finish", completed.await(15, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                model.successLiveData.removeObserver(successObserver)
                model.errorLiveData.removeObserver(errorObserver)
            }
        }
        return error.get()
    }

    private fun importSource(input: String): BookSource {
        val model = newImporter()
        assertNull(awaitImport(model, input))
        assertEquals(1, model.allSources.size)
        val source = model.allSources.single()
        sourceKeys.add(source.bookSourceUrl)
        val saved = CountDownLatch(1)
        instrumentation.runOnMainSync {
            model.selectStatus.indices.forEach { model.selectStatus[it] = true }
            model.importSelect { saved.countDown() }
        }
        assertTrue("Selected source was not saved", saved.await(15, TimeUnit.SECONDS))
        return runBlocking(Dispatchers.IO) {
            appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                ?: throw AssertionError("Import finished without saving its source")
        }
    }

    private fun cssSourceJson() = """
        {
          "bookSourceUrl":"$base",
          "bookSourceName":"Omi 本地验证书源",
          "bookSourceType":0,
          "enabled":true,
          "enabledExplore":false,
          "enabledCookieJar":false,
          "searchUrl":"/search?keyword={{key}}&page={{page}}",
          "ruleSearch":{
            "bookList":"@css:#books .book",
            "name":"@css:a.title@text",
            "author":"@css:.author@text",
            "bookUrl":"@css:a.title@href"
          },
          "ruleBookInfo":{
            "name":"@css:h1@text",
            "author":"@css:.writer@text",
            "intro":"@css:#intro@text",
            "tocUrl":"@css:#toc-link@href"
          },
          "ruleToc":{
            "chapterList":"@css:#chapters a",
            "chapterName":"text",
            "chapterUrl":"href"
          },
          "ruleContent":{
            "content":"@css:#content@html",
            "nextContentUrl":"@css:#next-page@href"
          }
        }
    """.trimIndent()

    private fun jsonSourceJson() = """
        {
          "bookSourceUrl":"$base/json-source",
          "bookSourceName":"Omi JSONPath 验证书源",
          "bookSourceType":0,
          "enabled":true,
          "enabledExplore":false,
          "enabledCookieJar":false,
          "searchUrl":"/json/search?keyword={{key}}",
          "ruleSearch":{
            "bookList":"$.data.books[*]",
            "name":"$.name",
            "author":"$.author",
            "bookUrl":"$.url"
          },
          "ruleBookInfo":{"name":"$.name","author":"$.author","tocUrl":"$.toc"},
          "ruleToc":{"chapterList":"$.chapters[*]","chapterName":"$.title","chapterUrl":"$.url"},
          "ruleContent":{"content":"$.content"}
        }
    """.trimIndent()

    private data class Request(val path: String, val parameters: Map<String, String>)

    private class FixtureSite : NanoHTTPD("127.0.0.1", 0) {
        var sourcesJson = "[]"
        val requests = ConcurrentLinkedQueue<Request>()

        override fun serve(session: IHTTPSession): Response {
            requests.add(Request(session.uri,
                session.parameters.mapValues { it.value.firstOrNull().orEmpty() }))
            val body = when (session.uri) {
                "/sources.json" -> sourcesJson
                "/search" -> """
                    <html><body><ul id="books"><li class="book">
                    <a class="title" href="/books/sea/index.html">海风来信</a>
                    <span class="author">作者：OmiReader</span></li></ul></body></html>
                """
                "/books/sea/index.html" -> """
                    <html><body><h1>海风来信</h1><span class="writer">OmiReader</span>
                    <div id="intro">一封从海边寄来的信。</div>
                    <a id="toc-link" href="toc.html">目录</a></body></html>
                """
                "/books/sea/toc.html" -> """
                    <html><body><div id="chapters">
                    <a href="chapter-1.html">第一章 远方的信</a>
                    <a href="chapter-2.html">第二章 海边</a></div></body></html>
                """
                "/books/sea/chapter-1.html" -> """
                    <html><body><nav>站点导航</nav><div id="content">
                    <p>海风把一封信送到了窗前。</p><p>信封上还留着一点盐。</p></div>
                    <a id="next-page" href="chapter-1-2.html">下一页</a></body></html>
                """
                "/books/sea/chapter-1-2.html" -> """
                    <html><body><div id="content"><p>翻过这一页，灯塔还亮着。</p></div>
                    <a id="next-page" href="chapter-2.html">下一章</a></body></html>
                """
                "/books/sea/chapter-2.html" -> "<div id=\"content\">第二章的正文</div>"
                "/json/search" -> """{"data":{"books":[{"name":"灯塔手记","author":"测试作者","url":"/json/books/lighthouse"}]}}"""
                "/json/books/lighthouse" -> """{"name":"灯塔手记","author":"测试作者","toc":"/json/books/lighthouse/toc"}"""
                "/json/books/lighthouse/toc" -> """{"chapters":[{"title":"第一章 灯亮了","url":"chapter-1"}]}"""
                "/json/books/lighthouse/chapter-1" -> """{"content":"灯塔的光扫过平静的海面。"}"""
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Missing fixture: ${session.uri}")
            }
            val mime = if (session.uri == "/sources.json" || session.uri.startsWith("/json/"))
                "application/json; charset=utf-8" else "text/html; charset=utf-8"
            return newFixedLengthResponse(Response.Status.OK, mime, body.trimIndent())
        }
    }
}
