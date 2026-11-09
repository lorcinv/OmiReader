package io.legado.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.MobiFile
import io.legado.app.model.localBook.PdfFile
import io.legado.app.model.localBook.TextFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the same file import, metadata, TOC and content path used by the reader. */
@RunWith(AndroidJUnit4::class)
class OmiLocalFormatsInstrumentedTest {
    private lateinit var directory: File
    private val imported = linkedMapOf<String, Book>()
    private val token = UUID.randomUUID().toString().replace("-", "").take(12)

    @Before
    fun setup() {
        clearParsers()
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "omi-local-formats-$token")
        check(directory.mkdirs())
    }

    @After
    fun cleanup() {
        clearParsers()
        imported.values.forEach { book ->
            LocalBook.deleteBook(book, false)
            book.removeLocalUriCache()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.readRecordDao.deleteByName(book.name)
            appDb.bookDao.delete(book)
        }
        directory.deleteRecursively()
    }

    @Test
    fun txtDetectsUtf8AndGb18030AndReadsChineseSecondChapter() {
        listOf(Charsets.UTF_8, Charset.forName("GB18030")).forEach { charset ->
            val file = textFile("text-${charset.name()}", charset)
            val (book, chapters) = importWithChapters(file)
            assertTrue("TXT chapters: ${chapters.map { it.title }}", chapters.size >= 3)
            val second = chapters.single { it.title.contains("第二章") }
            val content = readContent(book, second)
            assertTrue("Chinese text must survive ${charset.name()}", content.contains(SECOND_MARKER))
            assertFalse(content.contains('\uFFFD'))
            assertFalse(book.charset.isNullOrBlank())
            assertEquals(chapters.size, book.totalChapterNum)
            // A fresh parser must use the saved byte offsets and detected encoding as well.
            TextFile.clear()
            assertEquals(content, readContent(book, second))
        }
    }

    @Test
    fun epubImportsMetadataAndReadsBothChapters() {
        val title = "Omi EPUB $token"
        val (book, chapters) = importWithChapters(epubFile(title))
        assertEquals(title, book.name)
        assertTrue(chapters.size >= 2)
        assertTrue(readContent(book, chapters.first { it.title.contains("海风") }).contains(FIRST_MARKER))
        val second = chapters.first { it.title.contains("灯塔") }
        val content = readContent(book, second)
        assertTrue(content.contains(SECOND_MARKER))
        assertFalse(content.contains(FIRST_MARKER))
        EpubFile.clear()
        assertEquals(content, readContent(book, second))
    }

    @Test
    fun mobiAndAzw3ImportRealBooksAndRemainReadableAfterSwitching() {
        val cases = listOf("mobi" to 6, "azw3" to 8).map { (extension, version) ->
            val title = "OMI${token}_$extension"
            val (book, chapters) = importWithChapters(kindleFile(extension, version, title))
            assertEquals(title, book.name.trim())
            assertTrue("$extension must expose a real TOC", chapters.size > 2)
            val chapterTwo = chapters.firstOrNull {
                it.title.contains("Pool of Tears", ignoreCase = true)
            } ?: error("$extension has no second Alice chapter: ${chapters.map { it.title }}")
            val content = readContent(book, chapterTwo)
            assertTrue(content.contains("Alice", ignoreCase = true))
            assertTrue(content.length > 500)
            Triple(book, chapterTwo, content)
        }
        assertNotEquals(cases[0].second.url, cases[1].second.url)
        // Do not clear MobiFile here: this catches reuse of KF6 state for a KF8 book or vice versa.
        repeat(2) {
            cases.forEach { (book, chapter, expected) ->
                assertEquals(expected, readContent(book, chapter))
                assertEquals(chapter.url, LocalBook.getChapterList(book).first { it.title == chapter.title }.url)
            }
        }
    }

    @Test
    fun pdfImportIsRejectedWithOmiComicExplanation() {
        val error = runCatching { importWithChapters(pdfFile()) }.exceptionOrNull()
        assertNotNull("PDF must not be imported into OmiReader", error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("OmiComic"))
    }

    @Test
    fun missingEmptyAndDamagedFilesReturnAnExplanation() {
        assertReadableFailure("missing file") {
            importWithChapters(File(directory, "missing-$token.txt"))
        }
        assertReadableFailure("empty TXT") {
            importWithChapters(File(directory, "empty-$token.txt").apply { writeBytes(byteArrayOf()) })
        }
        listOf("epub", "mobi", "azw3", "pdf").forEach { extension ->
            clearParsers()
            assertReadableFailure("damaged $extension") {
                importWithChapters(File(directory, "damaged-$token.$extension").apply {
                    writeText("This is deliberately not a valid $extension document.")
                })
            }
        }
        val file = textFile("removed", Charsets.UTF_8)
        val (book, chapters) = importWithChapters(file)
        assertTrue(file.delete())
        TextFile.clear()
        val errorText = LocalBook.getContent(book, chapters.last()).orEmpty()
        assertTrue(errorText, errorText.startsWith("获取本地书籍内容失败"))
        assertTrue(errorText, errorText.contains("文件不存在"))
    }

    @Test
    fun readingPositionIsSavedAndReopenedSeparatelyForEachBook() {
        val (first, _) = importWithChapters(textFile("progress-first", Charsets.UTF_8))
        val (second, _) = importWithChapters(textFile("progress-second", Charsets.UTF_8))
        awaitReadSaves()
        val previousBook = ReadBook.book
        val previousCallback = ReadBook.callBack
        val previousChapter = ReadBook.durChapterIndex
        val previousPosition = ReadBook.durChapterPos
        ReadBook.callBack = null
        try {
            listOf(Triple(first, 1, 127), Triple(second, 2, 39)).forEach { (book, chapter, position) ->
                ReadBook.resetData(book)
                ReadBook.durChapterIndex = chapter
                ReadBook.durChapterPos = position
                ReadBook.saveRead()
                awaitReadSaves()
            }
            listOf(Triple(first, 1, 127), Triple(second, 2, 39)).forEach { (book, chapter, position) ->
                val reopened = appDb.bookDao.getBook(book.bookUrl)!!
                assertEquals(chapter, reopened.durChapterIndex)
                assertEquals(position, reopened.durChapterPos)
                ReadBook.resetData(reopened)
                assertEquals(chapter, ReadBook.durChapterIndex)
                assertEquals(position, ReadBook.durChapterPos)
                assertTrue(readContent(reopened, appDb.bookChapterDao.getChapter(book.bookUrl, chapter)!!).isNotBlank())
            }
        } finally {
            awaitReadSaves()
            if (previousBook != null) {
                ReadBook.resetData(previousBook)
            } else {
                ReadBook.book = null
                ReadBook.clearTextChapter()
                ReadBook.contentProcessor = null
            }
            ReadBook.durChapterIndex = previousChapter
            ReadBook.durChapterPos = previousPosition
            ReadBook.callBack = previousCallback
        }
    }

    private fun importWithChapters(file: File): Pair<Book, List<BookChapter>> {
        val book = LocalBook.importFile(Uri.fromFile(file))
        imported[book.bookUrl] = book
        assertNotNull("Import must persist its book", appDb.bookDao.getBook(book.bookUrl))
        val chapters = LocalBook.getChapterList(book)
        assertTrue("A readable local book needs chapters", chapters.isNotEmpty())
        assertEquals(chapters.indices.toList(), chapters.map { it.index })
        assertTrue(chapters.all { it.bookUrl == book.bookUrl })
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        book.save()
        return book to chapters
    }

    private fun readContent(book: Book, chapter: BookChapter): String {
        val content = LocalBook.getContent(book, chapter).orEmpty()
        assertTrue("Empty content: ${book.originName} / ${chapter.title}", content.isNotBlank())
        assertFalse(content, content.startsWith("获取本地书籍内容失败"))
        return content
    }

    private fun assertReadableFailure(label: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        // Assertion failures describe a failed test, not a readable parser error.
        if (failure is AssertionError) throw failure
        assertNotNull("$label was accepted", failure)
        val explanation = generateSequence(failure) { it.cause }
            .mapNotNull { it.localizedMessage?.takeIf(String::isNotBlank) }.joinToString(" / ")
        assertTrue("$label returned no explanation", explanation.isNotBlank())
    }

    private fun textFile(name: String, charset: Charset): File =
        File(directory, "$name-$token.txt").apply {
            val content = buildString {
                listOf("第一章 海风" to FIRST_MARKER, "第二章 灯塔" to SECOND_MARKER,
                    "第三章 归航" to "归航的船已经靠岸").forEach { (title, marker) ->
                    appendLine(title)
                    repeat(80) { appendLine("$marker。我们沿着港口慢慢走去，听见海风穿过树梢，也看见远处亮起的灯火。") }
                }
            }
            writeBytes(content.toByteArray(charset))
        }

    private fun epubFile(title: String): File = File(directory, "epub-$token.epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            fun entry(path: String, text: String, stored: Boolean = false) {
                val data = text.trimIndent().toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(path).apply {
                    if (stored) {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                    }
                })
                zip.write(data)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip", true)
            entry("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """)
            entry("OEBPS/content.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package version="2.0" unique-identifier="bookid" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title><dc:creator>OmiReader Fixture</dc:creator>
                    <dc:identifier id="bookid">urn:uuid:$token</dc:identifier><dc:language>zh-CN</dc:language>
                  </metadata>
                  <manifest><item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine toc="toc"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
                </package>
            """)
            entry("OEBPS/toc.ncx", """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head><meta name="dtb:uid" content="urn:uuid:$token"/><meta name="dtb:depth" content="1"/></head>
                  <docTitle><text>$title</text></docTitle><navMap>
                    <navPoint id="ch1" playOrder="1"><navLabel><text>第一章 海风</text></navLabel><content src="ch1.xhtml"/></navPoint>
                    <navPoint id="ch2" playOrder="2"><navLabel><text>第二章 灯塔</text></navLabel><content src="ch2.xhtml"/></navPoint>
                  </navMap>
                </ncx>
            """)
            listOf("海风" to FIRST_MARKER, "灯塔" to SECOND_MARKER).forEachIndexed { index, (heading, marker) ->
                entry("OEBPS/ch${index + 1}.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$heading</title></head>
                    <body><h1>$heading</h1><p>$marker。中文段落完整保留。</p><p>这是这一章的第二段。</p></body></html>
                """)
            }
        }
    }

    private fun kindleFile(extension: String, expectedVersion: Int, uniqueTitle: String): File {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("local-formats/Alice.$extension").use { it.readBytes() }
        assertEquals("BOOKMOBI", String(bytes, 60, 8, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val firstRecord = header.getInt(78)
        assertEquals(expectedVersion, header.getInt(firstRecord + 36))
        val title = uniqueTitle.toByteArray(Charsets.US_ASCII)
        val titleOffset = firstRecord + header.getInt(firstRecord + 84)
        val titleLength = header.getInt(firstRecord + 88)
        check(title.size <= titleLength)
        bytes.fill(' '.code.toByte(), titleOffset, titleOffset + titleLength)
        title.copyInto(bytes, titleOffset)
        header.putInt(firstRecord + 88, title.size)
        // The importer uses EXTH 503 before the full-name header. Keep every record offset intact.
        val exth = firstRecord + 16 + header.getInt(firstRecord + 20)
        assertEquals("EXTH", String(bytes, exth, 4, Charsets.US_ASCII))
        var offset = exth + 12
        repeat(header.getInt(exth + 8)) {
            val type = header.getInt(offset)
            val length = header.getInt(offset + 4)
            check(length >= 8 && offset + length <= bytes.size)
            if (type == 503) {
                check(title.size <= length - 8)
                bytes.fill(' '.code.toByte(), offset + 8, offset + length)
                title.copyInto(bytes, offset + 8)
            }
            offset += length
        }
        return File(directory, "$uniqueTitle.$extension").apply { writeBytes(bytes) }
    }

    private fun pdfFile(): File = File(directory, "pages-$token.pdf").apply {
        val pdf = PdfDocument()
        try {
            repeat(12) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, index).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(80f, 120f, 220f, 280f, Paint().apply {
                    color = if (index >= 10) Color.BLUE else Color.RED
                })
                pdf.finishPage(page)
            }
            outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun clearParsers() {
        TextFile.clear()
        EpubFile.clear()
        MobiFile.clear()
        PdfFile.release()
    }

    private fun awaitReadSaves() = ReadBook.executor.submit {}.get(10, TimeUnit.SECONDS)

    private companion object {
        const val FIRST_MARKER = "海风送来了远方的消息"
        const val SECOND_MARKER = "灯塔守夜人的信号"
    }
}
