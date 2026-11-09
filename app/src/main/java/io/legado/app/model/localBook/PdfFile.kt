package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SystemUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.ceil


class PdfFile(var book: Book) {
    companion object : BaseLocalBookParse {
        private var pFile: PdfFile? = null

        /**
         * pdf分页尺寸
         */
        const val PAGE_SIZE = 10

        @Synchronized
        private fun getPFile(book: Book): PdfFile {
            if (pFile == null || pFile?.book?.bookUrl != book.bookUrl) {
                pFile?.closePdf()
                pFile = PdfFile(book)
                return pFile!!
            }
            pFile?.book = book
            return pFile!!
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            // Reimporting the same URI may replace its contents. An open native
            // renderer must not keep serving the previous document.
            pFile?.closePdf()
            pFile = null
            getPFile(book).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getPFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getPFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getPFile(book).getImage(href)
        }

        /** Can be called when the reader releases its chapter workers. */
        @Synchronized
        fun release(expectedBookUrl: String? = null) {
            if (expectedBookUrl != null && pFile?.book?.bookUrl != expectedBookUrl) return
            pFile?.closePdf()
            pFile = null
        }

    }

    // PdfRenderer owns its successful ParcelFileDescriptor. Keep a single
    // renderer and close it explicitly on replacement, rather than finalization.
    private var pdfRenderer: PdfRenderer? = null

    /**
     * 读取PDF文件
     *
     * @return
     */
    private fun readPdf(): PdfRenderer {
        pdfRenderer?.let { return it }
        val uri = book.getLocalUri()
        val descriptor = if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        } ?: throw FileNotFoundException("无法打开 PDF 文件")
        return try {
            PdfRenderer(descriptor).also { pdfRenderer = it }
        } catch (error: Throwable) {
            // The constructor did not take ownership if opening failed.
            descriptor.close()
            throw error
        }
    }

    /**
     * 关闭pdf文件
     *
     */
    private fun closePdf() {
        val renderer = pdfRenderer
        pdfRenderer = null
        renderer?.close()
    }


    /**
     * 渲染PDF页面
     * 根据index打开pdf页面,并渲染到Bitmap
     *
     * @param renderer
     * @param index
     * @return
     */
    private fun openPdfPage(
        renderer: PdfRenderer,
        index: Int,
        requestedWidth: Int = PdfRenderPolicy.readingWidth(SystemUtils.screenWidthPx)
    ): Bitmap? {
        if (index < 0 || index >= renderer.pageCount) {
            return null
        }
        return renderer.openPage(index).use { page ->
            val size = PdfRenderPolicy.dimensions(page.width, page.height, requestedWidth)
            val bitmap = createBitmap(size.width, size.height)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

    }

    private fun getContent(chapter: BookChapter): String? {
        val renderer = readPdf()
        val start = chapter.index.toLong() * PAGE_SIZE
        if (start < 0 || start >= renderer.pageCount) return null
        val end = (start + PAGE_SIZE).coerceAtMost(renderer.pageCount.toLong())
        val width = PdfRenderPolicy.readingWidth(SystemUtils.screenWidthPx)
        return buildString {
            (start until end).forEach { page ->
                // Version and width are part of the disk-cache key: do not reuse
                // a previous screen-resolution JPEG for the clearer PNG raster.
                append("<img src=\"omipdf-v2/").append(width).append('/')
                    .append(page).append(".png\" >\n")
            }
        }
    }


    private fun getImage(href: String): InputStream? {
        return try {
            val parts = href.removePrefix("omipdf-v2/").removeSuffix(".png").split('/')
            val index = parts.lastOrNull()?.toIntOrNull() ?: return null
            val width = if (href.startsWith("omipdf-v2/") && parts.size == 2) {
                parts[0].toIntOrNull()?.coerceIn(1, PdfRenderPolicy.MAX_REQUESTED_WIDTH)
                    ?: return null
            } else if (parts.size == 1) {
                // Existing cached chapter markup used a bare page number.
                PdfRenderPolicy.readingWidth(SystemUtils.screenWidthPx)
            } else return null
            val bitmap = openPdfPage(readPdf(), index, width)
            if (bitmap != null) {
                try {
                    ImageBuffer().apply {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) {
                            "PDF 页面编码失败"
                        }
                    }.inputStream()
                } finally {
                    bitmap.recycle()
                }
            } else {
                null
            }

        } catch (error: Exception) {
            AppLog.put("PDF 页面加载失败\n${error.localizedMessage}", error)
            return null
        }
    }

    // Avoid the extra full encoded-image copy made by toByteArray(). Ownership
    // of the completed buffer moves to its stream; it is never written again.
    private class ImageBuffer : ByteArrayOutputStream(64 * 1024) {
        fun inputStream(): InputStream = ByteArrayInputStream(buf, 0, count)
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()

        readPdf().let { renderer ->
            if (renderer.pageCount > 0) {
                val chapterCount = ceil((renderer.pageCount.toDouble() / PAGE_SIZE)).toInt()
                (0 until chapterCount).forEach {
                    val chapter = BookChapter()
                    chapter.index = it
                    chapter.bookUrl = book.bookUrl
                    chapter.title = PdfRenderPolicy.pageTitle(it, renderer.pageCount, PAGE_SIZE)
                    chapter.url = "pdf_${it}"
                    chapterList.add(chapter)
                }
            }
        }
        return chapterList
    }

    private fun upBookCover() {
        try {
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = LocalBook.getCoverPath(book)
            }
            readPdf().let { renderer ->
                FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!)).use { out ->
                    openPdfPage(renderer, 0, 512)?.let { bitmap ->
                        try {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    out.flush()
                }
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        readPdf()
        upBookCover()
        if (book.name.isEmpty()) {
            book.name = book.originName.substringBeforeLast('.')
        }

    }

}
