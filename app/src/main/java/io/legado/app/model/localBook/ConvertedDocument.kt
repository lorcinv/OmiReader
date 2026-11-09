package io.legado.app.model.localBook

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.FileDoc
import io.legado.app.utils.openInputStream
import splitties.init.appCtx
import java.io.File

/** Converted books live in durable app storage, independent of the original file grant. */
object ConvertedDocument {
    private val directory get() = File(appCtx.filesDir, "omi-documents")
    private data class Cached(val path: String, val size: Long, val modified: Long, val archive: RichDocumentArchive)
    private var cached: Cached? = null

    fun prepare(uri: Uri, checkCancelled: () -> Unit = {}): Uri {
        val document = FileDoc.fromUri(uri, false)
        if (!DocumentImportConverter.supports(document.name)) return uri
        checkCancelled()
        val output = document.openInputStream().getOrThrow().use {
            DocumentImportConverter.convert(it, document.name, directory, checkCancelled)
        }
        return Uri.fromFile(output)
    }

    fun isConverted(book: Book): Boolean = runCatching {
        val uri = Uri.parse(book.bookUrl)
        if (uri.scheme != null && uri.scheme != "file") return@runCatching false
        val path = File(uri.path ?: book.bookUrl).canonicalFile
        path.parentFile == directory.canonicalFile && path.name.matches(Regex("[0-9a-f]{64}\\.epub"))
    }.getOrDefault(false)

    fun file(book: Book): File {
        require(isConverted(book)) { "这本书不是已导入的富文本文档。" }
        return File(Uri.parse(book.bookUrl).path ?: book.bookUrl)
    }

    /** TOC, full-text search and the rich reader share one chapter model. */
    @Synchronized
    fun open(book: Book, checkCancelled: () -> Unit = {}): RichDocumentArchive {
        checkCancelled()
        val source = file(book)
        cached?.takeIf { it.path == source.path && it.size == source.length() && it.modified == source.lastModified() }
            ?.let { return it.archive }
        return RichDocumentArchive.open(source, book.name, checkCancelled).also {
            cached = Cached(source.path, source.length(), source.lastModified(), it)
        }
    }

    fun getChapterList(book: Book, document: RichDocumentArchive = open(book)): ArrayList<BookChapter> =
        ArrayList(document.chapters.mapIndexed { index, chapter ->
            BookChapter(bookUrl = book.bookUrl, url = "omi-rich:$index", index = index,
                title = chapter.title, startFragmentId = chapter.anchor)
        })

    fun getContent(book: Book, chapter: BookChapter): String {
        require(chapter.bookUrl == book.bookUrl) { "章节不属于这本书。" }
        return open(book).chapters.getOrNull(chapter.index)?.text
            ?: error("文档章节不存在，请刷新目录。")
    }

    @Synchronized
    fun clear(book: Book) {
        if (cached?.path == Uri.parse(book.bookUrl).path) cached = null
    }
}
