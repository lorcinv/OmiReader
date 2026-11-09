package io.legado.app.ui.omi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.OmiTheme
import io.legado.app.model.localBook.ConvertedDocument
import io.legado.app.ui.association.FileAssociationActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

class OmiLibraryFragment() : Fragment(), MainFragmentInterface {
    constructor(position: Int, mode: String) : this() {
        arguments = Bundle().apply { putInt("position", position); putString("mode", mode) }
    }
    override val position: Int? get() = arguments?.getInt("position")
    private val mode get() = arguments?.getString("mode") ?: "library"
    private val c get() = requireContext()
    private lateinit var state: OmiLibraryState
    private lateinit var root: LinearLayout
    private lateinit var overlay: FrameLayout
    private var organizing: LinearLayout? = null
    private var floating: LinearLayout? = null
    private var selectedEntry: Entry? = null
    private var sorting = false
    private lateinit var heading: TextView
    private lateinit var back: ImageButton
    private lateinit var tabs: LinearLayout
    private lateinit var search: EditText
    private lateinit var listView: RecyclerView
    private lateinit var empty: TextView
    private lateinit var pagination: LinearLayout
    private lateinit var pageText: TextView
    private lateinit var previous: ImageButton
    private lateinit var next: ImageButton
    private lateinit var grid: GridLayoutManager
    private lateinit var dragHelper: ItemTouchHelper
    private val adapter = Cards()
    private var books = emptyList<Book>()
    private var bookmarks = emptyList<Bookmark>()
    private var folder = ""
    private var tag = ""
    private var query = ""
    private var page = 0
    private var allEntries = mutableListOf<Entry>()
    private val pageEntries = mutableListOf<Entry>()
    private val pageSize = 24
    private var dataLoaded = false
    private val scope get() = "$folder|$tag"
    private data class Entry(val book: Book? = null, val folder: OmiLibraryState.Folder? = null, val mark: Bookmark? = null) {
        val id get() = book?.let { "b:${it.bookUrl}" } ?: folder?.let { "f:${it.id}" } ?: "m:${mark!!.time}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = savedInstanceState?.getString("folder") ?: ""
        tag = savedInstanceState?.getString("tag") ?: ""
        page = savedInstanceState?.getInt("page") ?: 0
        query = savedInstanceState?.getString("query") ?: ""
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("folder", folder); outState.putString("tag", tag)
        outState.putInt("page", page); outState.putString("query", query)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        state = OmiLibraryState(c)
        overlay = FrameLayout(c)
        root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(OmiTheme.current(c).background))
        }
        overlay.addView(root, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(c.omiDp(8), 0, c.omiDp(6), 0)
        }
        back = omiIcon(c, R.drawable.ic_arrow_back, getString(R.string.back)) { goBack() }
        header.addView(back, LinearLayout.LayoutParams(c.omiDp(44), c.omiDp(52)))
        heading = omiText(c, "", 20, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        header.addView(heading, LinearLayout.LayoutParams(0, c.omiDp(56), 1f))
        header.addView(omiIcon(c, R.drawable.ic_search, getString(R.string.search)) {
            search.visibility = if (search.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (search.visibility == View.GONE) { search.setText(""); search.clearFocus() }
            else search.requestFocus()
        }.apply { id = R.id.menu_search }, LinearLayout.LayoutParams(c.omiDp(44), c.omiDp(48)))
        if (mode == "library") {
            header.addView(omiIcon(c, R.drawable.ic_add, getString(R.string.omi_import_book)) { importBook() }
                .apply { id = R.id.menu_add_local }, LinearLayout.LayoutParams(c.omiDp(44), c.omiDp(48)))
            header.addView(omiIcon(c, R.drawable.ic_more_vert, getString(R.string.omi_more)) { options() }, LinearLayout.LayoutParams(c.omiDp(44), c.omiDp(48)))
        }
        root.addView(header)
        search = EditText(c).apply {
            hint = getString(R.string.omi_search_library); textSize = 15f; setSingleLine(true)
            setTextColor(Color.parseColor(OmiTheme.current(c).text)); setHintTextColor(Color.parseColor(OmiTheme.current(c).muted))
            setPadding(c.omiDp(18), 0, c.omiDp(18), 0)
            visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            setText(query)
            doAfterTextChanged { query = it.toString(); page = 0; render() }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, c.omiDp(48)))
        tabs = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.omiDp(12), 0, c.omiDp(12), 0) }
        root.addView(HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false; addView(tabs)
            visibility = if (mode == "library") View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(-1, c.omiDp(48)))
        grid = GridLayoutManager(c, if (state.list || mode == "bookmarks") 1 else state.columns)
        listView = RecyclerView(c).apply {
            id = R.id.rv_bookshelf
            layoutManager = grid; adapter = this@OmiLibraryFragment.adapter
            itemAnimator = null; isVerticalScrollBarEnabled = false
            setPadding(c.omiDp(8), c.omiDp(10), c.omiDp(8), c.omiDp(12)); clipToPadding = false
        }
        val area = FrameLayout(c).apply { addView(listView, FrameLayout.LayoutParams(-1, -1)) }
        empty = omiText(c, "", 15).apply {
            gravity = Gravity.CENTER; setPadding(c.omiDp(24), 0, c.omiDp(24), 0)
            setTextColor(Color.parseColor(OmiTheme.current(c).muted))
            setOnClickListener { if (mode == "library" && query.isBlank()) importBook() }
        }
        area.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        val pagerArea = LinearLayout(c).apply { gravity = Gravity.CENTER; setPadding(0, c.omiDp(4), 0, c.omiDp(8)) }
        pagination = LinearLayout(c).apply {
            gravity = Gravity.CENTER; background = omiBackground(c, OmiTheme.current(c).surface, 24)
            setPadding(c.omiDp(6), 0, c.omiDp(6), 0)
        }
        previous = omiIcon(c, R.drawable.ic_cursor_left, getString(R.string.prev_page)) { page--; render(); listView.scrollToPosition(0) }
        next = omiIcon(c, R.drawable.ic_cursor_right, getString(R.string.next_page)) { page++; render(); listView.scrollToPosition(0) }
        pageText = omiText(c, "", 13).apply { gravity = Gravity.CENTER; setOnClickListener { jumpPage() } }
        pagination.addView(previous, LinearLayout.LayoutParams(c.omiDp(40), c.omiDp(40)))
        pagination.addView(pageText, LinearLayout.LayoutParams(c.omiDp(72), c.omiDp(40)))
        pagination.addView(next, LinearLayout.LayoutParams(c.omiDp(40), c.omiDp(40)))
        pagerArea.addView(pagination); root.addView(pagerArea)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0); insets
        }
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) listView.parent.requestDisallowInterceptTouchEvent(true)
                super.onSelectedChanged(holder, actionState)
            }
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition; val b = to.bindingAdapterPosition
                if (a !in pageEntries.indices || b !in pageEntries.indices) return false
                pageEntries.add(b, pageEntries.removeAt(a))
                val offset = page * pageSize
                allEntries.add(offset + b, allEntries.removeAt(offset + a))
                adapter.notifyItemMoved(a, b)
                return true
            }
            override fun chooseDropTarget(selected: RecyclerView.ViewHolder, targets: MutableList<RecyclerView.ViewHolder>, x: Int, y: Int): RecyclerView.ViewHolder? {
                val centerX = x + selected.itemView.width / 2
                val centerY = y + selected.itemView.height / 2
                return targets.firstOrNull { target ->
                    val view = target.itemView
                    centerX in view.left..view.right && centerY in view.top..view.bottom
                }
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                state.order[scope] = allEntries.map { it.id }.toMutableList(); state.save()
                listView.parent.requestDisallowInterceptTouchEvent(false)
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        dragHelper.attachToRecyclerView(listView)
        updateTabs(); render()
        return overlay
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isResumed && (selectedEntry != null || sorting)) clearSelection()
                else if (isResumed && (folder.isNotEmpty() || search.visibility == View.VISIBLE)) goBack()
                else { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { appDb.bookDao.flowAll().collect { incoming ->
                    if (mode == "library" && !c.defaultSharedPreferences.getBoolean("omiGroupsMigrated", false)) {
                        val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
                        state.migrateGroups(groups, incoming); updateTabs()
                    }
                    books = incoming.filterNot { it.isNotShelf || it.originName.endsWith(".pdf", true) || it.bookUrl.endsWith(".pdf", true) }
                    dataLoaded = true; render()
                } }
                if (mode == "bookmarks") launch { appDb.bookmarkDao.flowAll().collect { bookmarks = it; render() } }
            }
        }
    }
    override fun onResume() { super.onResume(); if (::root.isInitialized) { state = OmiLibraryState(c); updateTabs(); render() } }
    private fun goBack() {
        if (search.visibility == View.VISIBLE) { search.visibility = View.GONE; search.setText("") }
        else {
            val current = state.folders.find { it.id == folder }
            folder = current?.parent ?: ""; tag = current?.shelf ?: tag
            page = 0; updateTabs(); render()
        }
    }
    private fun updateTabs() {
        tabs.removeAllViews()
        fun tabButton(label: String, key: String, longClick: (() -> Unit)? = null) {
            tabs.addView(omiText(c, label, 14, key == tag).apply {
                setPadding(c.omiDp(14), 0, c.omiDp(14), 0)
                setTextColor(Color.parseColor(if (key == this@OmiLibraryFragment.tag) OmiTheme.current(c).accent else OmiTheme.current(c).muted))
                if (key == this@OmiLibraryFragment.tag) background = omiBackground(c, OmiTheme.current(c).surface, 18)
                setOnClickListener { clearSelection(); this@OmiLibraryFragment.tag = key; folder = ""; page = 0; updateTabs(); render() }
                if (longClick != null) setOnLongClickListener { longClick(); true }
            }, LinearLayout.LayoutParams(-2, c.omiDp(36)).apply { marginEnd = c.omiDp(4) })
        }
        tabButton(getString(R.string.omi_all), "")
        state.tags.forEach { t -> tabButton(t.name, t.id) { tagOptions(t) } }
        tabs.addView(omiIcon(c, R.drawable.ic_add, getString(R.string.omi_new_tag)) { newTag() }, LinearLayout.LayoutParams(c.omiDp(40), c.omiDp(40)))
    }
    private fun render() {
        if (!::root.isInitialized) return
        heading.text = when (mode) {
            "bookmarks" -> getString(R.string.omi_bookmarks)
            "recent" -> getString(R.string.omi_recent)
            else -> state.folders.find { it.id == folder }?.name ?: state.tags.find { it.id == tag }?.name ?: getString(R.string.bookshelf)
        }
        back.visibility = if (folder.isNotEmpty()) View.VISIBLE else View.GONE
        val candidates = books.filter { b ->
            val matches = query.isBlank() || listOf(b.name, b.author, state.bookTags[b.bookUrl].orEmpty().joinToString { id -> state.tags.find { it.id == id }?.name.orEmpty() }).any { it.contains(query, true) }
            matches && when {
                mode == "recent" -> c.defaultSharedPreferences.contains("omiProgress:${b.bookUrl}") || !b.durChapterTitle.isNullOrBlank() || b.durChapterPos > 0 || b.durChapterIndex > 0
                mode != "library" -> true
                tag.isNotEmpty() -> state.bookTags[b.bookUrl]?.contains(tag) == true && (query.isNotEmpty() || state.folderFor(b.bookUrl, tag) == folder)
                query.isNotEmpty() -> true
                else -> state.folderFor(b.bookUrl) == folder
            }
        }
        allEntries = when (mode) {
            "bookmarks" -> bookmarks.filter { query.isBlank() || (it.bookName + it.chapterName + it.bookText + it.content).contains(query, true) }
                .sortedByDescending { it.time }.map { Entry(mark = it) }.toMutableList()
            "recent" -> candidates.sortedByDescending { it.durChapterTime }.map { Entry(book = it) }.toMutableList()
            else -> {
                val entries = mutableListOf<Entry>()
                if (query.isEmpty()) entries += state.folders.filter { it.parent == folder && it.shelf == tag }.map { Entry(folder = it) }
                entries += candidates.sortedBy { it.order }.map { Entry(book = it) }
                val ordering = state.order[scope].orEmpty().withIndex().associate { it.value to it.index }
                entries.sortedBy { ordering[it.id] ?: Int.MAX_VALUE }.toMutableList()
            }
        }
        val count = ceil(allEntries.size / pageSize.toDouble()).toInt().coerceAtLeast(1)
        if (dataLoaded) page = page.coerceIn(0, count - 1)
        pageEntries.clear(); pageEntries += allEntries.drop(page * pageSize).take(pageSize)
        grid.spanCount = if (state.list || mode == "bookmarks") 1 else state.columns
        adapter.notifyDataSetChanged()
        empty.visibility = if (dataLoaded && allEntries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(when {
            query.isNotBlank() -> R.string.omi_empty_results
            mode == "bookmarks" -> R.string.omi_empty_bookmarks
            mode == "recent" -> R.string.omi_empty_recent
            else -> R.string.omi_empty_library
        })
        (pagination.parent as View).visibility = if (count > 1) View.VISIBLE else View.GONE
        pageText.text = "${page + 1} / $count"
        pageText.contentDescription = getString(R.string.omi_page, page + 1, count)
        previous.isEnabled = page > 0; previous.alpha = if (previous.isEnabled) 1f else .3f
        next.isEnabled = page + 1 < count; next.alpha = if (next.isEnabled) 1f else .3f
    }
    private fun importBook() = startActivity<FileAssociationActivity> { putExtra("omiPickBook", true) }
    private fun options() = OmiSheet(c, getString(R.string.omi_library_options)).apply {
        layoutControls(this)
        action(getString(R.string.omi_new_folder), R.drawable.ic_create_folder_outline) { newFolder() }
        action(getString(R.string.omi_new_tag), R.drawable.ic_add) { newTag() }
        action(getString(R.string.omi_import_folder), R.drawable.ic_folder_open) { startActivity<ImportBookActivity>() }
        show()
    }
    private fun layoutOptions() = OmiSheet(c, getString(R.string.omi_layout)).apply {
        layoutControls(this); show()
    }
    private fun layoutControls(sheet: OmiSheet) {
        val choices = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        fun choice(label: Int, icon: Int, list: Boolean) {
            choices.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER; setPadding(c.omiDp(8), 0, c.omiDp(8), 0)
                addView(omiIcon(c, icon, getString(label)), LinearLayout.LayoutParams(c.omiDp(34), c.omiDp(40)))
                addView(omiText(c, getString(label), 14))
                setOnClickListener { setLayout(list, state.columns) }
            }, LinearLayout.LayoutParams(0, c.omiDp(46), 1f))
        }
        choice(R.string.omi_grid, R.drawable.omi_ic_grid, false); choice(R.string.omi_list, R.drawable.omi_ic_list, true)
        sheet.body.addView(choices)
        OmiControls.slider(c, sheet.body, getString(R.string.omi_grid_columns), state.columns, 2, 6) { n, _ -> setLayout(false, n) }
    }
    private fun setLayout(asList: Boolean, columns: Int) {
        // Keep this RecyclerView and current data attached; there is no activity/fragment recreation.
        val first = grid.findFirstVisibleItemPosition().coerceAtLeast(0)
        state.list = asList; state.columns = columns; state.save()
        listView.recycledViewPool.clear(); render(); grid.scrollToPositionWithOffset(first, 0)
    }
    private fun newFolder() = OmiSheet(c, getString(R.string.omi_new_folder)).apply {
        input(hint = getString(R.string.omi_folder)) { name ->
            if (state.addFolder(name, folder, tag) == null) toast(getString(R.string.omi_name_exists))
            render()
        }; show()
    }
    private fun newTag() = OmiSheet(c, getString(R.string.omi_new_tag)).apply {
        input(hint = getString(R.string.omi_tags)) { name ->
            if (state.addTag(name) == null) toast(getString(R.string.omi_name_exists))
            updateTabs(); render()
        }; show()
    }
    private fun tagOptions(item: OmiLibraryState.Tag) = OmiSheet(c, item.name).apply {
        action(getString(R.string.omi_rename)) { OmiSheet(c, getString(R.string.omi_rename)).apply {
            input(item.name) { name ->
                val clean = name.trim().removePrefix("#").take(40)
                if (clean.isBlank() || state.tags.any { it.id != item.id && it.name == clean }) toast(getString(R.string.omi_name_exists))
                else { item.name = clean; state.save(); updateTabs() }
            }; show()
        } }
        action(getString(R.string.omi_remove_tag)) { state.removeTag(item); if (tag == item.id) tag = ""; updateTabs(); render() }
        show()
    }
    private fun folderDetails(item: OmiLibraryState.Folder) = OmiSheet(c, item.name).apply {
        note(getString(R.string.omi_books_count, books.count { state.bookFolders[it.bookUrl] == item.id }))
        action(getString(R.string.omi_rename)) { OmiSheet(c, getString(R.string.omi_rename)).apply {
            input(item.name) { name ->
                val clean = name.trim().take(80)
                if (state.folders.any { it.id != item.id && it.parent == item.parent && it.name == clean }) toast(getString(R.string.omi_name_exists))
                else { item.name = clean; state.save(); render() }
            }; show()
        } }
        action(getString(R.string.omi_remove_folder)) { OmiSheet(c, getString(R.string.omi_remove_folder)).apply {
            note(getString(R.string.omi_remove_folder_note)); action(getString(R.string.omi_remove_folder)) { state.removeFolder(item); render() }; show()
        } }
        show()
    }
    private fun bookDetails(book: Book) = OmiSheet(c, getString(R.string.omi_details)).apply {
        val row = LinearLayout(c).apply { gravity = Gravity.TOP }
        row.addView(CoverImageView(c).apply { load(book.getDisplayCover(), book.name, book.author, false, book.origin) }, LinearLayout.LayoutParams(c.omiDp(82), c.omiDp(123)))
        row.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(c.omiDp(16), 0, 0, 0)
            addView(omiText(c, book.name, 18, true)); addView(omiText(c, book.author, 14))
            addView(omiText(c, progressText(book), 13))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)
        val privateCopy = ConvertedDocument.isConverted(book) || book.bookUrl.contains("/omi-books/")
        note(getString(if (!book.isLocal) R.string.omi_online_source else if (privateCopy) R.string.omi_private_copy else R.string.omi_reference))
        action(getString(R.string.omi_read), R.drawable.ic_bottom_books) { startActivityForBook(book) }
        action(getString(R.string.omi_move_folder), R.drawable.ic_folder_outline) { selectFolder(book) }
        action(getString(R.string.omi_tags)) { selectTags(book) }
        action(getString(R.string.omi_details)) { startActivity<BookInfoActivity> { putExtra("name", book.name); putExtra("author", book.author) } }
        action(getString(R.string.omi_source_path)) { OmiSheet(c, getString(R.string.omi_source_path)).apply { note(book.originName); note(book.bookUrl); show() } }
        action(getString(R.string.omi_remove_book)) { OmiSheet(c, getString(R.string.omi_remove_book)).apply {
            note(getString(R.string.omi_remove_book_note))
            action(getString(R.string.omi_remove_book)) { viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { appDb.bookDao.delete(book) } } }
            show()
        } }
        show()
    }
    private fun selectFolder(book: Book) = OmiSheet(c, getString(R.string.omi_move_folder)).apply {
        action(state.tags.find { it.id == tag }?.name ?: getString(R.string.bookshelf), R.drawable.omi_ic_shelf, selected = state.folderFor(book.bookUrl, tag).isEmpty()) { state.setFolder(book.bookUrl, "", tag); clearSelection(); render() }
        state.folders.filter { it.shelf == tag }.forEach { f -> action(folderPath(f), R.drawable.omi_ic_move, selected = state.folderFor(book.bookUrl, tag) == f.id) { state.setFolder(book.bookUrl, f.id, tag); clearSelection(); render() } }
        action(getString(R.string.omi_new_folder), R.drawable.omi_ic_add) { OmiSheet(c, getString(R.string.omi_new_folder)).apply {
            input(hint = getString(R.string.omi_folder)) { name -> state.addFolder(name, folder, tag)?.let { state.setFolder(book.bookUrl, it.id, tag) }; clearSelection(); render() }; show()
        } }
        show()
    }
    private fun folderPath(item: OmiLibraryState.Folder): String {
        val names = mutableListOf(item.name); var parent = item.parent; val seen = mutableSetOf(item.id)
        while (parent.isNotEmpty() && seen.add(parent)) { val f = state.folders.find { it.id == parent } ?: break; names.add(0, f.name); parent = f.parent }
        return names.joinToString(" / ")
    }
    private fun selectTags(book: Book) = OmiSheet(c, getString(R.string.omi_tags)).apply {
        state.tags.forEach { t -> body.addView(CheckBox(c).apply {
            text = t.name; minHeight = c.omiDp(48); setTextColor(Color.parseColor(OmiTheme.current(c).text))
            isChecked = state.bookTags[book.bookUrl]?.contains(t.id) == true
            setOnCheckedChangeListener { _, _ -> state.toggleTag(book.bookUrl, t.id); render() }
        }) }
        action(getString(R.string.omi_new_tag), R.drawable.ic_add) { OmiSheet(c, getString(R.string.omi_new_tag)).apply {
            input(hint = getString(R.string.omi_tags)) { name -> state.addTag(name)?.let { state.toggleTag(book.bookUrl, it.id) }; updateTabs(); render() }; show()
        } }; show()
    }
    private fun jumpPage() = OmiSheet(c, getString(R.string.omi_jump_page)).apply {
        input((page + 1).toString()) { value -> value.toIntOrNull()?.let { page = it - 1; render(); listView.scrollToPosition(0) } }; show()
    }
    private fun progressText(book: Book): String {
        val saved = c.defaultSharedPreferences.getFloat("omiProgress:${book.bookUrl}", -1f)
        val value = if (saved >= 0) saved else book.durChapterIndex.toFloat() / book.totalChapterNum.coerceAtLeast(1)
        return if (saved < 0 && book.durChapterTitle.isNullOrBlank() && book.durChapterPos == 0 && book.durChapterIndex == 0) getString(R.string.omi_not_started)
        else getString(R.string.omi_progress, (value * 100).roundToInt().coerceIn(0, 100))
    }
    private fun openBookmark(mark: Bookmark) {
        val book = books.find { it.name == mark.bookName && it.author == mark.bookAuthor }
        if (book == null) toast(getString(R.string.omi_no_bookmark_book))
        else startActivityForBook(book) { putExtra("index", mark.chapterIndex); putExtra("chapterPos", mark.chapterPos) }
    }
    private fun bookmarkDetails(mark: Bookmark) = OmiSheet(c, mark.bookName).apply {
        note(mark.chapterName); note(mark.bookText); if (mark.content.isNotBlank()) note(mark.content)
        action(getString(R.string.omi_read)) { openBookmark(mark) }
        action(getString(R.string.omi_remove_bookmark)) { viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { appDb.bookmarkDao.delete(mark) } } }
        show()
    }
    private fun toast(text: String) = Toast.makeText(c, text, Toast.LENGTH_SHORT).show()

    private inner class Cards : RecyclerView.Adapter<Card>() {
        override fun getItemCount() = pageEntries.size
        override fun getItemViewType(position: Int) = if (mode == "bookmarks") 2 else if (state.list) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Card(type)
        override fun onBindViewHolder(holder: Card, position: Int) = holder.bind(pageEntries[position])
    }
    private inner class Card(private val type: Int) : RecyclerView.ViewHolder(LinearLayout(c)) {
        private val panel = itemView as LinearLayout
        private val cover = CoverImageView(c)
        private val folderCover = FrameLayout(c)
        private val image = FrameLayout(c)
        private val title = omiText(c, "", if (type == 0) 12 else 14, true)
        private val subtitle = omiText(c, "", if (type == 0) 10 else 11)
        private val menu = omiIcon(c, R.drawable.omi_ic_dots, getString(R.string.omi_details)).apply { setPadding(c.omiDp(5), c.omiDp(5), c.omiDp(5), c.omiDp(5)) }
        init {
            panel.orientation = if (type == 1) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            panel.setPadding(c.omiDp(4), c.omiDp(4), c.omiDp(4), c.omiDp(12))
            panel.layoutParams = RecyclerView.LayoutParams(-1, -2)
            if (type != 2) {
                image.addView(cover, FrameLayout.LayoutParams(-1, -2))
                image.addView(folderCover, FrameLayout.LayoutParams(-1, -1))
                panel.addView(image, LinearLayout.LayoutParams(if (type == 1) c.omiDp(78) else -1, -2))
            }
            val info = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (type == 1) c.omiDp(14) else c.omiDp(2), c.omiDp(6), 0, 0)
            }
            title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.END
            info.addView(title, LinearLayout.LayoutParams(-1, -2))
            val footer = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            subtitle.maxLines = if (type == 2) 3 else 1; subtitle.ellipsize = TextUtils.TruncateAt.END
            subtitle.setTextColor(Color.parseColor(OmiTheme.current(c).muted))
            footer.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
            footer.addView(menu, LinearLayout.LayoutParams(c.omiDp(32), c.omiDp(28)))
            info.addView(footer)
            panel.addView(info, if (type == 1) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        fun bind(entry: Entry) {
            panel.background = if (selectedEntry?.id == entry.id) omiBackground(c, OmiTheme.current(c).reader, 10).apply { setStroke(c.omiDp(2), Color.parseColor(OmiTheme.current(c).accent)) } else null
            panel.setOnLongClickListener {
                if (mode != "library" || query.isNotBlank()) false
                else if (sorting) {
                    listView.parent.requestDisallowInterceptTouchEvent(true)
                    dragHelper.startDrag(this)
                    true
                } else { selectEntry(entry); true }
            }
            panel.setOnTouchListener { _, event ->
                if (sorting && event.actionMasked == MotionEvent.ACTION_DOWN) { listView.parent.requestDisallowInterceptTouchEvent(true); dragHelper.startDrag(this) }
                false
            }
            entry.book?.let { book ->
                folderCover.visibility = View.GONE
                cover.load(book.getDisplayCover(), book.name, book.author, false, book.origin)
                title.text = book.name; subtitle.text = progressText(book)
                panel.setOnClickListener { startActivityForBook(book) }; menu.setOnClickListener { bookDetails(book) }
            }
            entry.folder?.let { f ->
                val inFolder = books.filter { state.folderFor(it.bookUrl, f.shelf) == f.id && (f.shelf.isEmpty() || state.bookTags[it.bookUrl]?.contains(f.shelf) == true) }
                cover.load(null, "", "", false, "")
                folderCover.visibility = View.VISIBLE; folderCover.removeAllViews()
                folderCover.background = null
                folderCover.addView(OmiBookStack(c, inFolder.take(3), f.name), FrameLayout.LayoutParams(-1, -1))
                title.text = f.name; subtitle.text = getString(R.string.omi_books_count, inFolder.size)
                panel.setOnClickListener { folder = f.id; tag = f.shelf; page = 0; updateTabs(); render(); listView.scrollToPosition(0) }
                menu.setOnClickListener { folderDetails(f) }
            }
            entry.mark?.let { mark ->
                title.text = mark.bookName
                subtitle.text = "${mark.chapterName}\n${mark.content.ifBlank { mark.bookText }}"
                panel.setOnClickListener { openBookmark(mark) }; menu.setOnClickListener { bookmarkDetails(mark) }
            }
        }
    }

    private fun selectEntry(entry: Entry) {
        selectedEntry = entry; sorting = false
        showOrganizing(); adapter.notifyDataSetChanged()
    }
    private fun clearSelection() {
        selectedEntry = null; sorting = false
        overlay.setPadding(0, 0, 0, 0)
        organizing?.let { overlay.removeView(it) }; organizing = null
        floating?.let { overlay.removeView(it) }; floating = null
        (activity as? io.legado.app.ui.main.MainActivity)?.setOrganizing(false)
        if (::listView.isInitialized) { listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, c.omiDp(12)); adapter.notifyDataSetChanged() }
    }
    private fun showOrganizing() {
        organizing?.let { overlay.removeView(it) }; floating?.let { overlay.removeView(it) }
        (activity as? io.legado.app.ui.main.MainActivity)?.setOrganizing(true)
        val bottomInset = ViewCompat.getRootWindowInsets(overlay)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        overlay.setPadding(0, 0, 0, bottomInset)
        listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, c.omiDp(80))
        val bar = LinearLayout(c).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor(OmiTheme.current(c).surface)) }
        fun action(label: Int, icon: Int, run: () -> Unit) {
            bar.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = getString(label)
                addView(omiIcon(c, icon, "").apply { contentDescription = null; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(c.omiDp(32), c.omiDp(30)))
                addView(omiText(c, getString(label), 10).apply { gravity = Gravity.CENTER })
                setOnClickListener { run() }
            }, LinearLayout.LayoutParams(0, c.omiDp(60), 1f))
        }
        action(R.string.omi_done, R.drawable.omi_ic_close) { clearSelection() }
        if (!sorting) {
            action(R.string.omi_sort, R.drawable.omi_ic_sort) { sorting = true; selectedEntry = null; showOrganizing(); adapter.notifyDataSetChanged() }
            action(R.string.omi_details, R.drawable.omi_ic_info) { selectedEntry?.book?.let { bookDetails(it) }; selectedEntry?.folder?.let { folderDetails(it) } }
            action(R.string.omi_remove_book, R.drawable.omi_ic_delete) { selectedEntry?.book?.let { bookDetails(it) }; selectedEntry?.folder?.let { folderDetails(it) } }
        }
        organizing = bar; overlay.addView(bar, FrameLayout.LayoutParams(-1, c.omiDp(60), Gravity.BOTTOM))
        if (!sorting) selectedEntry?.book?.let { book ->
            val stack = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            fun floatingAction(label: Int, icon: Int, run: () -> Unit) {
                stack.addView(LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, c.omiDp(4), 0, c.omiDp(4))
                    addView(omiText(c, getString(label), 11).apply { background = omiBackground(c, OmiTheme.current(c).surface, 7); setPadding(c.omiDp(8), c.omiDp(5), c.omiDp(8), c.omiDp(5)); elevation = c.omiDp(2).toFloat() })
                    addView(omiIcon(c, icon, "").apply { contentDescription = null; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; background = omiBackground(c, OmiTheme.current(c).surface, 19); elevation = c.omiDp(2).toFloat() }, LinearLayout.LayoutParams(c.omiDp(38), c.omiDp(38)).apply { marginStart = c.omiDp(8) })
                    contentDescription = getString(label); setOnClickListener { run() }
                })
            }
            floatingAction(R.string.omi_add_shelf, R.drawable.omi_ic_shelf) { selectTags(book) }
            floatingAction(R.string.omi_move_folder, R.drawable.omi_ic_move) { selectFolder(book) }
            floating = stack
            overlay.addView(stack, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = c.omiDp(14); bottomMargin = c.omiDp(76) })
        }
    }
    override fun onPause() { if (::overlay.isInitialized) clearSelection(); super.onPause() }
}
