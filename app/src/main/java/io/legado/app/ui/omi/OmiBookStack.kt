package io.legado.app.ui.omi

import android.content.Context
import android.widget.FrameLayout
import io.legado.app.data.entities.Book
import io.legado.app.help.config.OmiTheme
import io.legado.app.ui.widget.image.CoverImageView

/** OmiComic mobile.css:643–651: three 88% covers offset by 0%, 6%, 12%. */
class OmiBookStack(context: Context, books: List<Book>, name: String) : FrameLayout(context) {
    init {
        for (i in 2 downTo 0) {
            val book = books.getOrNull(i) ?: books.firstOrNull()
            addView(CoverImageView(context).apply {
                load(book?.getDisplayCover(), book?.name ?: name, book?.author.orEmpty(), false, book?.origin.orEmpty())
                background = omiBackground(context, OmiTheme.current(context).surface, 9)
                alpha = 1f - i * .12f; elevation = context.omiDp(3 - i).toFloat()
            })
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: (w * 4 / 3)
        repeat(childCount) { n ->
            val i = 2 - n
            getChildAt(n).layoutParams = LayoutParams((w * .88f).toInt(), (h * .88f).toInt()).apply { leftMargin = (w * i * .06f).toInt(); topMargin = (h * i * .06f).toInt() }
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
