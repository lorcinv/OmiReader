package io.legado.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.LocalBookLinks
import io.legado.app.model.localBook.MobiFile
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual native-page taps, not direct invocation of the link handler. */
@RunWith(AndroidJUnit4::class)
class LocalBookLinksInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val token = UUID.randomUUID().toString().take(8)
    private lateinit var directory: File
    private val books = arrayListOf<Book>()

    @Before fun setup() { directory = File(context.cacheDir, "omi-links-$token").apply { check(mkdirs()) } }
    @After fun cleanup() {
        EpubFile.clear(); MobiFile.clear()
        books.forEach {
            LocalBook.deleteBook(it, false); it.removeLocalUriCache()
            appDb.bookChapterDao.delByBook(it.bookUrl); appDb.bookDao.delete(it)
            appDb.readRecordDao.deleteByName(it.name)
        }
        directory.deleteRecursively()
    }

    @Test fun epubUpgradesOldCacheAndFollowsUnicodeAnchorThenReturnsToContents() {
        val (book, chapters) = import(epub(split = false))
        BookHelp.saveText(book, chapters[0], "旧缓存中的目录文字")
        ActivityScenario.launch<ReadBookActivity>(reader(book)).use { scenario ->
            ready(book, 0)
            val raw = BookHelp.getContent(book, chapters[0])!!
            assertTrue("Previously imported EPUBs must refresh their plain-text cache", raw.startsWith(LocalBookLinks.VERSION))
            assertFalse(ReadBook.curTextChapter!!.getContent().contains("\uE000omi:"))
            tapLink(scenario) { it.endsWith("#%E6%B7%B1%E5%A4%84") }
            ready(book, 1)
            assertTrue(ReadBook.durPageIndex > 0)
            assertTrue(currentPage().contains("定位成功"))
            screenshot("epub-internal-anchor", scenario)
        }
        await { (appDb.bookDao.getBook(book.bookUrl)?.durChapterPos ?: 0) > 0 }
        ActivityScenario.launch<ReadBookActivity>(reader(book)).use { scenario ->
            ready(book, 1)
            assertTrue("Following a link must save the new reading position", currentPage().contains("定位成功"))
            tapLink(scenario) { it.endsWith("toc.xhtml#toc-top") }
            ready(book, 0)
            rendered(scenario)
            assertEquals(0, ReadBook.durPageIndex)
        }
    }

    @Test fun epubLinksChooseTheCorrectChapterWhenOneHtmlFileHasSeveralTocFragments() {
        val (book, chapters) = import(epub(split = true))
        val resource = chapters[1].url.substringBefore('#')
        assertEquals("A link to $resource (${LocalBookLinks.epubTarget("", resource)}) must open its first chapter; stored=" +
            appDb.bookChapterDao.getChapterList(book.bookUrl).joinToString { "${it.index}:${it.url}:${it.isVolume}" }, 1,
            EpubFile.resolveLink(book, resource)?.index)
        ActivityScenario.launch<ReadBookActivity>(reader(book)).use { scenario ->
            ready(book, 0)
            tapLink(scenario) { it.endsWith("#part-two") }
            ready(book, 2)
            rendered(scenario)
            assertTrue(currentPage().contains("第二部分的真正正文"))
            assertFalse(currentPage().contains("第一部分的正文"))
        }
    }

    @Test fun mobiWithoutNcxFollowsABytePositionInsideItsSingleChapter() {
        val (book, _) = import(simpleMobi())
        ActivityScenario.launch<ReadBookActivity>(reader(book)).use { scenario ->
            ready(book, 0)
            tapLink(scenario) { it.startsWith("filepos:") && it.removePrefix("filepos:").toInt() > 0 }
            await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true && currentPage().contains("位置链接已到达") }
            assertEquals(0, ReadBook.durChapterIndex)
            assertTrue(ReadBook.durChapterPos > 500)
            screenshot("mobi-internal-filepos", scenario)
        }
    }

    @Test fun realKf6ContentsLinksReachTheirNcxChapter() { realKindleLinks("mobi") }
    @Test fun realKf8ContentsLinksReachTheirNcxChapter() { realKindleLinks("azw3") }

    private fun realKindleLinks(extension: String) {
        val bytes = instrumentation.context.assets.open("local-formats/Alice.$extension").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val first = buffer.getInt(78)
        val name = "LNK${token}_$extension".toByteArray(Charsets.US_ASCII)
        val titleAt = first + buffer.getInt(first + 84); val titleSize = buffer.getInt(first + 88)
        check(name.size <= titleSize); bytes.fill(32, titleAt, titleAt + titleSize); name.copyInto(bytes, titleAt)
        buffer.putInt(first + 88, name.size)
        val exth = first + 16 + buffer.getInt(first + 20); var record = exth + 12
        repeat(buffer.getInt(exth + 8)) {
            val length = buffer.getInt(record + 4)
            if (buffer.getInt(record) == 503) { check(name.size <= length - 8); bytes.fill(32, record + 8, record + length); name.copyInto(bytes, record + 8) }
            record += length
        }
        val file = File(directory, "alice.$extension").apply { writeBytes(bytes) }
        val (book, chapters) = import(file)
        val last = chapters.last { !it.isVolume }
        val lastContent = ContentProcessor.get(book).getContent(book, last,
            LocalBook.getContent(book, last).orEmpty(), includeTitle = false)
        assertTrue("The final $extension chapter must retain its text", lastContent.textList.any { it.isNotBlank() })
        var candidate: Triple<Int, String, Int>? = null
        for (chapter in chapters) {
            val content = ContentProcessor.get(book).getContent(book, chapter, LocalBook.getContent(book, chapter).orEmpty(), includeTitle = false)
            val targets = content.localLinks.flatMap { p -> p.links }.map { it.target }.distinct()
            for (target in targets) {
                val destination = chapters.firstOrNull { it.index != chapter.index && !it.isVolume && it.url.substringAfter(':') == target }
                if (destination != null) { candidate = Triple(chapter.index, target, destination.index); break }
            }
            if (candidate != null) break
        }
        assertNotNull("The real $extension fixture must expose an actual contents link", candidate)
        val selected = candidate!!
        book.durChapterIndex = selected.first; book.durChapterPos = 0; appDb.bookDao.update(book)
        ActivityScenario.launch<ReadBookActivity>(reader(book)).use { scenario ->
            ready(book, selected.first)
            tapLink(scenario) { it == selected.second }
            ready(book, selected.third)
            rendered(scenario)
            assertEquals(chapters[selected.third].title, ReadBook.curTextChapter!!.title)
            assertTrue(currentPage().isNotBlank())
        }
    }

    private fun tapLink(scenario: ActivityScenario<ReadBookActivity>, match: (String) -> Boolean) {
        val targetPage = ReadBook.curTextChapter!!.pages.firstOrNull { p -> p.lines.any { line -> line.columns.filterIsInstance<TextColumn>().any { it.linkTarget?.let(match) == true } } }
        assertNotNull("A native page must retain a clickable destination", targetPage)
        scenario.onActivity { ReadBook.skipToPage(targetPage!!.index) }
        await { ReadBook.durPageIndex == targetPage!!.index }
        rendered(scenario)
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        val point = FloatArray(2)
        scenario.onActivity { activity ->
            val view = activity.findViewById<ReadView>(R.id.read_view).curPage.findViewById<ContentTextView>(R.id.content_text_view)
            val line = view.textPage.lines.first { it.columns.filterIsInstance<TextColumn>().any { c -> c.linkTarget?.let(match) == true } }
            val column = line.columns.filterIsInstance<TextColumn>().first { it.linkTarget?.let(match) == true }
            val location = IntArray(2); view.getLocationOnScreen(location)
            point[0] = location[0] + (column.start + column.end) / 2
            point[1] = location[1] + (line.lineTop + line.lineBottom) / 2
        }
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0).also {
                instrumentation.sendPointerSync(it); it.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun import(file: File): Pair<Book, List<BookChapter>> {
        val book = LocalBook.importFile(Uri.fromFile(file)).also(books::add)
        val chapters = LocalBook.getChapterList(book)
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
        return book to chapters
    }
    private fun reader(book: Book) = Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
    private fun currentPage() = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)?.text.orEmpty()
    private fun ready(book: Book, index: Int) = await {
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == index && ReadBook.curTextChapter?.position == index && ReadBook.curTextChapter?.isCompleted == true
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(50) }
        fail("Book link did not reach its expected destination; chapter=${ReadBook.durChapterIndex}, page=${ReadBook.durPageIndex}, text=${currentPage().take(120)}")
    }
    private fun rendered(scenario: ActivityScenario<ReadBookActivity>) {
        await {
            var visible = false
            scenario.onActivity { activity ->
                val page = activity.findViewById<ReadView>(R.id.read_view).curPage.textPage
                visible = !page.isMsgPage && page.text == currentPage() && page.chapterIndex == ReadBook.durChapterIndex
            }
            visible
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }
    private fun screenshot(name: String, scenario: ActivityScenario<ReadBookActivity>) {
        rendered(scenario)
        val dir = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image -> File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
    }

    private fun epub(split: Boolean): File {
        val before = (0..65).joinToString("") { "<p>第 $it 段。链接应该跳到指定位置，而不是随意翻一页。</p>" }
        val content = if (split) "<h1 id='part-one'>第一部分</h1><p>第一部分的正文</p><h1 id='part-two'>第二部分</h1><p>第二部分的真正正文</p>"
            else "<h1>第二章</h1>$before<p id='深处'>定位成功</p><p><a href='../toc.xhtml#toc-top'>返回目录</a></p>" + before
        val target = if (split) "part-two" else "%E6%B7%B1%E5%A4%84"
        val file = File(directory, "links-$token.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, value: String) { zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OEBPS/content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            entry("OEBPS/content.opf", "<package xmlns='http://www.idpf.org/2007/opf' version='2.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='id'>$token</dc:identifier><dc:title>Omi Links $token</dc:title><dc:creator>OmiReader</dc:creator></metadata><manifest><item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/><item id='toc' href='toc.xhtml' media-type='application/xhtml+xml'/><item id='body' href='Text/ch2.xhtml' media-type='application/xhtml+xml'/></manifest><spine toc='ncx'><itemref idref='toc'/><itemref idref='body'/></spine></package>")
            var nav = "<navPoint id='a' playOrder='1'><navLabel><text>目录</text></navLabel><content src='toc.xhtml'/></navPoint>"
            if (split) nav += "<navPoint id='b' playOrder='2'><navLabel><text>第一部分</text></navLabel><content src='Text/ch2.xhtml#part-one'/></navPoint><navPoint id='c' playOrder='3'><navLabel><text>第二部分</text></navLabel><content src='Text/ch2.xhtml#part-two'/></navPoint>"
            else nav += "<navPoint id='b' playOrder='2'><navLabel><text>第二章</text></navLabel><content src='Text/ch2.xhtml'/></navPoint>"
            entry("OEBPS/toc.ncx", "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/' version='2005-1'><head/><docTitle><text>Links</text></docTitle><navMap>$nav</navMap></ncx>")
            fun html(body: String) = "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Links</title></head><body>$body</body></html>"
            entry("OEBPS/toc.xhtml", html("<h1 id='toc-top'>目录</h1><p><a href='Text/../Text/ch2.xhtml#$target'>继续阅读指定位置</a></p><p><a href='../../outside.xhtml'>普通无效文字</a></p>"))
            entry("OEBPS/Text/ch2.xhtml", html(content))
        }
        return file
    }

    private fun simpleMobi(): File {
        val placeholder = "0000000000"
        var html = "<html><head><title>Mobi Links $token</title></head><body><p><a filepos='$placeholder'>跳转到指定位置</a></p>" +
            (0..65).joinToString("") { "<p>第 $it 段。此处是目录后面的前置内容。</p>" } + "<mbp:pagebreak/><p id='target'>位置链接已到达</p><p>这才是正确目标。</p></body></html>"
        val offset = html.substringBefore("<p id='target'>").toByteArray().size
        html = html.replace(placeholder, offset.toString().padStart(10, '0'))
        val data = html.toByteArray(); val title = "Mobi Links $token".toByteArray()
        val header = ByteArray(248 + title.size); val h = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        h.putShort(0, 1); h.putInt(4, data.size); h.putShort(8, 1); h.putShort(10, 4096)
        "MOBI".toByteArray().copyInto(header, 16)
        mapOf(20 to 232, 24 to 2, 28 to 65001, 32 to 110, 36 to 6, 80 to 2, 84 to 248, 88 to title.size, 108 to 2, 244 to -1).forEach { (at, value) -> h.putInt(at, value) }
        title.copyInto(header, 248)
        val pdb = ByteArray(78 + 2 * 8 + 2); val p = ByteBuffer.wrap(pdb).order(ByteOrder.BIG_ENDIAN)
        title.copyInto(pdb); "BOOKMOBI".toByteArray().copyInto(pdb, 60); p.putShort(76, 2)
        p.putInt(78, pdb.size); p.putInt(82, 0); p.putInt(86, pdb.size + header.size); p.putInt(90, 2)
        return File(directory, "links-$token.mobi").apply { writeBytes(pdb + header + data) }
    }
}
