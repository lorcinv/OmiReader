package io.legado.app.model.localBook

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getLocalUri
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Runs against Android's real PDF engine, with generated documents, offline. */
@RunWith(AndroidJUnit4::class)
class PdfFileInstrumentedTest {
    private lateinit var directory: File

    @Before
    fun setup() {
        PdfFile.release()
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "pdf-engine-test-${System.nanoTime()}")
        check(directory.mkdirs())
    }

    @After
    fun cleanup() {
        PdfFile.release()
        directory.deleteRecursively()
    }

    @Test
    fun originalPageRendersLosslesslyWithinMemoryLimit() {
        val book = document("render.pdf", 2)
        val chapters = PdfFile.getChapterList(book)
        assertEquals("第 1—2 页", chapters.single().title)
        val content = PdfFile.getContent(book, chapters.single())!!
        val src = Regex("src=\"([^\"]+)\"").find(content)!!.groupValues[1]
        val bytes = PdfFile.getImage(book, src)!!.use { it.readBytes() }
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        try {
            assertTrue(bitmap.width.toLong() * bitmap.height <= PdfRenderPolicy.MAX_PIXELS)
            assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            assertEquals(Color.WHITE, bitmap.getPixel(2, 2))
        } finally {
            bitmap.recycle()
        }
        assertNull(PdfFile.getImage(book, "-1"))
        assertNull(PdfFile.getImage(book, "9999"))
        // Bare page numbers remain usable for previously cached chapter markup.
        assertNotNull(PdfFile.getImage(book, "0")?.use { it.read() })
    }

    @Test
    fun switchingDocumentsAndReleaseCloseNativeRenderers() {
        val first = document("first.pdf", 1)
        val second = document("second.pdf", 1)
        assertNativeRendererClosesItsDescriptor(first)
        // Keep strong references: GC must not hide a missing close on replacement.
        val renderers = arrayListOf<PdfRenderer>()
        repeat(12) {
            PdfFile.getChapterList(first)
            renderers.forEach(::assertClosed)
            val firstRenderer = cachedRenderer() ?: error("First PDF has no renderer on switch $it")
            firstRenderer.openPage(0).use { page -> assertEquals(595, page.width) }
            renderers.add(firstRenderer)
            PdfFile.getChapterList(second)
            renderers.forEach(::assertClosed)
            val secondRenderer = cachedRenderer() ?: error("Second PDF has no renderer on switch $it")
            secondRenderer.openPage(0).use { page -> assertEquals(595, page.width) }
            renderers.add(secondRenderer)
        }
        PdfFile.release()
        renderers.forEach(::assertClosed)
        assertNull(cachedRenderer())
    }

    @Test
    fun failedOpenLeavesNoCachedRendererAndReimportUsesNewContent() {
        val broken = File(directory, "broken.pdf").apply { writeText("not a PDF") }
        val brokenBook = localPdfBook(broken)
        repeat(3) {
            try {
                PdfFile.getChapterList(brokenBook)
                fail("Invalid PDF was accepted")
            } catch (_: IOException) {
                assertNull("Failed PDF open must not cache a renderer", cachedRenderer())
            }
        }
        val book = document("updated.pdf", 12)
        assertEquals("第 11—12 页", PdfFile.getChapterList(book).last().title)
        document("updated.pdf", 1)
        PdfFile.upBookInfo(book)
        assertEquals("第 1 页", PdfFile.getChapterList(book).single().title)
    }

    private fun document(name: String, pages: Int): Book {
        val file = File(directory, name)
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(200f, 300f, 400f, 550f, Paint().apply { color = Color.RED })
                page.canvas.drawText("OmiReader PDF ${index + 1}", 40f, 80f,
                    Paint().apply { color = Color.BLACK; textSize = 16f })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return localPdfBook(file)
    }

    private fun localPdfBook(file: File): Book = Book(
        // These are the same flags LocalBook.importFile assigns to local books.
        type = BookType.text or BookType.local,
        origin = BookType.localTag,
        bookUrl = file.absolutePath,
        originName = file.name,
        coverUrl = File(directory, "${file.name}-cover.jpg").absolutePath
    ).also {
        // Check the real local-file guard before testing renderer failures.
        assertEquals(file.absolutePath, it.getLocalUri().path)
    }

    private fun assertNativeRendererClosesItsDescriptor(book: Book) {
        // Android restricts access to /proc FD links. Observe only a descriptor
        // owned by this test and verify the native renderer's ownership contract.
        val descriptor = ParcelFileDescriptor.open(File(book.bookUrl), ParcelFileDescriptor.MODE_READ_ONLY)
        val fileDescriptor = descriptor.fileDescriptor
        try {
            assertTrue(fileDescriptor.valid())
            val renderer = PdfRenderer(descriptor)
            try {
                renderer.openPage(0).use { page -> assertEquals(595, page.width) }
            } finally {
                renderer.close()
            }
            assertFalse("PdfRenderer.close must close its owned file descriptor", fileDescriptor.valid())
            assertClosed(renderer)
        } finally {
            if (fileDescriptor.valid()) descriptor.close()
        }
    }

    private fun assertClosed(renderer: PdfRenderer) {
        try {
            renderer.openPage(0).use { }
            fail("A replaced/released native PDF renderer is still usable")
        } catch (_: IllegalStateException) {
            // Android's real native renderer rejects operations after close.
        }
    }

    private fun cachedRenderer(): PdfRenderer? {
        // Read only this application's fields, not Android hidden APIs. The real
        // import/render methods create and close these objects without test hooks.
        val owner = PdfFile::class.java.getDeclaredField("pFile").apply { isAccessible = true }
            .get(null) as? PdfFile ?: return null
        return PdfFile::class.java.getDeclaredField("pdfRenderer").apply { isAccessible = true }
            .get(owner) as? PdfRenderer
    }
}
