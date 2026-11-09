package io.legado.app.ui.omi

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.OmiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object OmiContents {
    fun show(context: Context, owner: LifecycleOwner, bookUrl: String, current: Int, bookmarkSelect: ((Bookmark) -> Unit)? = null, select: (Int) -> Unit) {
        owner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val book = appDb.bookDao.getBook(bookUrl)
                appDb.bookChapterDao.getChapterList(bookUrl).map { it.title } to (book?.let { appDb.bookmarkDao.getByBook(it.name, it.author) } ?: emptyList())
            }
            show(context, data.first, current, data.second, bookmarkSelect, select)
        }
    }
    fun show(context: Context, titles: List<String>, current: Int, marks: List<Bookmark> = emptyList(), bookmarkSelect: ((Bookmark) -> Unit)? = null, select: (Int) -> Unit) {
        val sheet = OmiSheet(context, context.getString(R.string.omi_catalog))
        var bookmarks = false
        var query = ""
        var rows = titles.indices.toList()
        val tabs = LinearLayout(context)
        val chapterTab = omiText(context, context.getString(R.string.omi_chapter_count, titles.size), 15, true).apply { gravity = Gravity.CENTER; contentDescription = context.getString(R.string.omi_catalog) }
        val markTab = omiText(context, context.getString(R.string.omi_mark_count, marks.size), 15).apply { gravity = Gravity.CENTER; contentDescription = context.getString(R.string.omi_bookmarks) }
        tabs.addView(chapterTab, LinearLayout.LayoutParams(0, context.omiDp(48), 1f))
        tabs.addView(markTab, LinearLayout.LayoutParams(0, context.omiDp(48), 1f))
        sheet.body.addView(tabs)
        val search = EditText(context).apply {
            hint = context.getString(R.string.search); setSingleLine(true); textSize = 15f
            setTextColor(Color.parseColor(OmiTheme.current(context).text))
        }
        sheet.body.addView(search, LinearLayout.LayoutParams(-1, context.omiDp(48)))
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context); isVerticalScrollBarEnabled = false; itemAnimator = null
        }
        val empty = omiText(context, context.getString(R.string.omi_empty_bookmarks), 14).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val adapter = object : RecyclerView.Adapter<Row>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Row(context)
            override fun onBindViewHolder(holder: Row, position: Int) {
                val index = rows[position]
                val mark = if (bookmarks) marks[index] else null
                holder.number.text = (if (bookmarks) mark!!.chapterIndex + 1 else index + 1).toString()
                holder.label.text = mark?.chapterName ?: titles[index]
                holder.label.setTextColor(Color.parseColor(if (!bookmarks && index == current) OmiTheme.current(context).accent else OmiTheme.current(context).text))
                holder.note.text = mark?.content?.ifBlank { mark.bookText }.orEmpty()
                holder.note.visibility = if (bookmarks) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { sheet.dismiss(); if (mark == null) select(index) else if (bookmarkSelect != null) bookmarkSelect(mark) else select(mark.chapterIndex) }
            }
        }
        list.adapter = adapter
        sheet.body.addView(empty, LinearLayout.LayoutParams(-1, context.omiDp(52)))
        sheet.body.addView(list, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * .60f).toInt()))
        fun render() {
            rows = if (bookmarks) marks.indices.filter { (marks[it].chapterName + marks[it].bookText + marks[it].content).contains(query, true) }
                else titles.indices.filter { titles[it].contains(query, true) }
            chapterTab.background = omiBackground(context, if (!bookmarks) OmiTheme.current(context).reader else OmiTheme.current(context).surface, 12)
            markTab.background = omiBackground(context, if (bookmarks) OmiTheme.current(context).reader else OmiTheme.current(context).surface, 12)
            chapterTab.setTextColor(Color.parseColor(if (!bookmarks) OmiTheme.current(context).accent else OmiTheme.current(context).muted))
            markTab.setTextColor(Color.parseColor(if (bookmarks) OmiTheme.current(context).accent else OmiTheme.current(context).muted))
            empty.visibility = if (bookmarks && rows.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
        chapterTab.setOnClickListener { bookmarks = false; render(); list.scrollToPosition(current.coerceIn(0, (rows.size - 1).coerceAtLeast(0))) }
        markTab.setOnClickListener { bookmarks = true; render(); list.scrollToPosition(0) }
        search.doAfterTextChanged { query = it.toString(); render() }
        render(); sheet.show(); list.scrollToPosition(current.coerceIn(0, (titles.size - 1).coerceAtLeast(0)))
    }
    private class Row(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        val number = omiText(context, "", 12).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor(OmiTheme.current(context).muted)) }
        val label = omiText(context, "", 15)
        val note = omiText(context, "", 12).apply { maxLines = 2; setTextColor(Color.parseColor(OmiTheme.current(context).muted)) }
        init {
            (itemView as LinearLayout).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = context.omiDp(52)
                setPadding(0, context.omiDp(10), 0, context.omiDp(10))
                addView(number, LinearLayout.LayoutParams(context.omiDp(32), -1))
                addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(note) }, LinearLayout.LayoutParams(0, -2, 1f))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
        }
    }
}
