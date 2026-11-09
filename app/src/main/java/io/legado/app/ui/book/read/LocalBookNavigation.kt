package io.legado.app.ui.book.read

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isMobi
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves book-owned destinations without opening a browser or an arbitrary local file. */
object LocalBookNavigation {
    data class Position(val chapter: Int, val character: Int)

    suspend fun find(book: Book, target: String): Position? = withContext(Dispatchers.IO) {
        val destination = when {
            book.isEpub -> EpubFile.resolveLink(book, target)
            book.isMobi -> MobiFile.resolveLink(book, target)
            else -> null
        } ?: return@withContext null
        val anchor = destination.anchor ?: return@withContext Position(destination.index, 0)
        ReadBook.curTextChapter?.takeIf { ReadBook.book?.bookUrl == book.bookUrl && it.position == destination.index }
            ?.localAnchors?.get(anchor)?.let { return@withContext Position(destination.index, it) }

        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, destination.index) ?: return@withContext null
        val raw = if (book.isMobi) MobiFile.getContentForLink(book, chapter, anchor) else BookHelp.getContent(book, chapter)
        raw ?: return@withContext null
        val processor = ContentProcessor.get(book)
        val title = chapter.getDisplayTitle(processor.getTitleReplaceRules(), book.getUseReplaceRule())
        val content = processor.getContent(book, chapter, raw, includeTitle = false)
        val layout = ChapterProvider.getTextChapterAsync(this, book, chapter, title, content,
            appDb.bookChapterDao.getChapterCount(book.bookUrl))
        var position: Int? = null
        try {
            for (page in layout.layoutChannel) {
                position = layout.localAnchors[anchor]
                if (position != null) break
            }
        } finally { layout.cancelLayout() }
        position?.let { Position(destination.index, it) }
    }
}
