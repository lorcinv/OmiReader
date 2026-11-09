package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import androidx.core.view.WindowInsetsControllerCompat
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.omi.*
import io.legado.app.utils.defaultSharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.OmiTheme
import io.legado.app.model.localBook.ConvertedDocument
import io.legado.app.model.localBook.RichDocumentArchive
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Full local rich-text reader for imported DOCX and Markdown books.
 *  Web content has no script bridge, JavaScript, file access, or network loading permission.
 */
class RichDocumentActivity : AppCompatActivity(), io.legado.app.ui.font.FontSelectDialog.CallBack {
    override val curFontPath get() = ReadBookConfig.textFont
    override fun selectFont(path: String) { ReadBookConfig.textFont = path; ReadBookConfig.save(); runOnUiThread { refreshReadingOptions() } }
    companion object {
        private const val HOST = "https://omi-reader.invalid"
        private val progressWriter = Executors.newSingleThreadExecutor()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("omi-rich-reader", MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var progressRail: LinearLayout
    private lateinit var progressSeek: SeekBar
    private lateinit var progressCaption: TextView
    private var chromeVisible = false
    private var renderedPalette = ""
    private lateinit var web: ReaderWebView
    private lateinit var bookTitleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var position: TextView
    private lateinit var loading: FrameLayout
    private var book: Book? = null
    @Volatile private var archive: RichDocumentArchive? = null
    @Volatile private var chapterIndex = 0
    @Volatile private var generation = 0
    @Volatile private var htmlBytes = ByteArray(0)
    private var ready = false
    private var finishingGeneration = -1
    private var openingFraction = 0f
    private var fontSize = 20
    private var lineSpacing = 1.8f
    private var fontFamily = "serif"
    private var lastVolumeTime = 0L
    private val savePosition = Runnable { persistPosition() }
    private val palette get() = OmiTheme.current(this)
    private val readerBackground get() = ReadBookConfig.durConfig.curBgStr().takeIf { ReadBookConfig.durConfig.curBgType() == 0 } ?: palette.reader
    private val readerText get() = "#%06X".format(ReadBookConfig.textColor and 0xffffff)
    private val bookKey get() = book?.bookUrl?.substringAfterLast('/') ?: ""

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppContextWrapper.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (io.legado.app.help.config.AppConfig.isNightTheme)
            io.legado.app.R.style.AppTheme_Dark else io.legado.app.R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        OmiTheme.ensureDefaults(this)
        OmiReadingOptions.ensureDefaults(this)
        loadTypography()
        renderedPalette = palette.id
        buildView()
        onBackPressedDispatcher.addCallback(this) { finishReader() }
        val url = intent.getStringExtra("bookUrl")
        if (url.isNullOrBlank()) { fail(getString(R.string.omi_open_missing)); return }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val selected = appDb.bookDao.getBook(url) ?: error(getString(R.string.omi_no_bookmark_book))
                    val task = coroutineContext
                    val document = ConvertedDocument.open(selected) { task.ensureActive() }
                    val chapters = ConvertedDocument.getChapterList(selected, document)
                    val stored = appDb.bookChapterDao.getChapterList(selected.bookUrl)
                    if (stored.size != chapters.size || stored.firstOrNull()?.url != "omi-rich:0") {
                        appDb.bookChapterDao.delByBook(selected.bookUrl)
                        appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    }
                    selected.totalChapterNum = chapters.size
                    appDb.bookDao.update(selected)
                    selected to document
                }
                book = result.first
                archive = result.second
                bookTitleView.text = result.first.name
                val localPositionIsNewer = prefs.getLong("saved:$bookKey", 0) >= result.first.durChapterTime
                val requested = when {
                    intent.hasExtra("index") -> intent.getIntExtra("index", 0)
                    savedInstanceState != null -> savedInstanceState.getInt("chapter", result.first.durChapterIndex)
                    localPositionIsNewer -> prefs.getInt("chapter:$bookKey", result.first.durChapterIndex)
                    else -> result.first.durChapterIndex
                }.coerceIn(0, result.second.chapters.lastIndex)
                val charLength = max(1, result.second.chapters[requested].text.length)
                val fraction = when {
                    intent.hasExtra("index") -> intent.getIntExtra("chapterPos", 0).toFloat() / charLength
                    savedInstanceState != null -> savedInstanceState.getFloat("fraction", 0f)
                    localPositionIsNewer -> prefs.getFloat("position:$bookKey", result.first.durChapterPos.toFloat() / charLength)
                    else -> result.first.durChapterPos.toFloat() / charLength
                }
                intent.removeExtra("index")
                intent.removeExtra("chapterPos")
                showChapter(requested, fraction.coerceIn(0f, 1f), saveCurrent = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error.localizedMessage ?: getString(R.string.omi_open_error)) }
        }
    }

    private fun loadTypography() {
        fontSize = ReadBookConfig.textSize.coerceIn(8, 64)
        lineSpacing = 1.2f + ReadBookConfig.lineSpacingExtra.toFloat() / fontSize
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildView() {
        val colors = palette
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor(readerBackground)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stage = FrameLayout(this)
        web = ReaderWebView(this).apply {
            setBackgroundColor(Color.parseColor(readerBackground))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isEnabled = false
            pageMode = { defaultSharedPreferences.getInt("omiPageAnimation", 1) }
            onCenterTap = { setChrome(!chromeVisible) }
            onPageTurn = { turnPage(it) }
            settings.apply {
                javaScriptEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                domStorageEnabled = false
                databaseEnabled = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                setSupportZoom(false)
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
            }
            webViewClient = LocalReaderClient()
            scrollListener = {
                if (ready) {
                    updatePositionLabel()
                    handler.removeCallbacks(savePosition)
                    handler.postDelayed(savePosition, 600)
                }
            }
        }
        stage.addView(web, FrameLayout.LayoutParams(-1, -1))
        loading = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(readerBackground))
            isClickable = true
            addView(ProgressBar(this@RichDocumentActivity), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        stage.addView(loading, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val status = row().apply { setPadding(dp(20), 0, dp(20), 0) }
        subtitle = text("", 11).apply {
            setTextColor(Color.parseColor(colors.muted)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        position = text("", 11).apply { setTextColor(Color.parseColor(colors.muted)); gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        status.addView(subtitle, LinearLayout.LayoutParams(0, dp(24), 1f))
        status.addView(position, LinearLayout.LayoutParams(-2, dp(24)))
        content.addView(status)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        header = row().apply {
            setBackgroundColor(Color.parseColor(colors.background)); setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(omiIcon(this, R.drawable.ic_arrow_back, getString(R.string.back)) { finishReader() }, LinearLayout.LayoutParams(dp(44), dp(56)))
        bookTitleView = text("", 17).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        header.addView(bookTitleView, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(omiIcon(this, R.drawable.omi_ic_bookmark, getString(R.string.omi_add_bookmark)) { addBookmark() }, LinearLayout.LayoutParams(dp(44), dp(56)))
        header.addView(omiIcon(this, R.drawable.ic_more_vert, getString(R.string.omi_more)) { showMore() }, LinearLayout.LayoutParams(dp(44), dp(56)))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
        toolbar = row().apply {
            background = omiBackground(this@RichDocumentActivity, colors.surface, 24)
            setPadding(dp(6), dp(6), dp(6), dp(6)); elevation = dp(3).toFloat()
        }
        fun tool(label: Int, icon: Int, run: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                contentDescription = getString(label)
                addView(omiIcon(this@RichDocumentActivity, icon, "").apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(getString(label), 12).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(26)))
                setOnClickListener { run() }
            }
            toolbar.addView(item, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        tool(R.string.omi_catalog, R.drawable.omi_ic_contents) { showContents() }
        tool(R.string.omi_typography, R.drawable.ic_interface_setting) { showTypography() }
        tool(R.string.omi_brightness, R.drawable.ic_daytime) { OmiReadingOptions.brightness(this) }
        tool(if (palette.id == "night") R.string.omi_day else R.string.omi_night, if (palette.id == "night") R.drawable.ic_daytime else R.drawable.ic_brightness) {
            persistPosition(); OmiTheme.apply(this, if (palette.id == "night") OmiTheme.day(this) else OmiTheme.palettes[3]); recreate()
        }
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        progressRail = row().apply { background = omiBackground(this@RichDocumentActivity, colors.surface, 16) }
        progressRail.addView(omiIcon(this, R.drawable.omi_ic_back, getString(R.string.previous_chapter)) { stepChapter(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        progressSeek = SeekBar(this).apply {
            max = 1000; contentDescription = getString(R.string.omi_reading_progress); OmiControls.style(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (user && ready) web.scrollTo(0, (web.scrollRange() * value / 1000f).roundToInt())
                    updateProgressCaption()
                }
                override fun onStopTrackingTouch(bar: SeekBar) { persistPosition() }
            })
        }
        progressRail.addView(progressSeek, LinearLayout.LayoutParams(0, dp(44), 1f))
        progressCaption = text("1/1", 11).apply { gravity = Gravity.CENTER }
        progressRail.addView(progressCaption, LinearLayout.LayoutParams(dp(48), dp(44)))
        progressRail.addView(omiIcon(this, R.drawable.omi_ic_next, getString(R.string.next_chapter)) { stepChapter(1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(progressRail, FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(90)) })
        setContentView(root)
        setChrome(false)
    }

    private fun setChrome(visible: Boolean) {
        chromeVisible = visible
        header.visibility = if (visible) View.VISIBLE else View.GONE
        toolbar.visibility = if (visible) View.VISIBLE else View.GONE
        progressRail.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) progressSeek.progress = (fraction() * 1000).roundToInt()
        applySystemBars()
    }

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.id != "night"
            isAppearanceLightNavigationBars = palette.id != "night"
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (ReadBookConfig.hideStatusBar) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    private fun refreshReadingOptions() {
        if (palette.id != renderedPalette) { persistPosition(); recreate(); return }
        val old = fraction()
        loadTypography(); applySystemBars()
        root.setBackgroundColor(Color.parseColor(readerBackground)); web.setBackgroundColor(Color.parseColor(readerBackground))
        if (archive != null) showChapter(chapterIndex, old)
    }

    private fun turnPage(direction: Int) {
        if (!ready) return
        if ((direction > 0 && web.scrollY >= web.scrollRange() - 2) || (direction < 0 && web.scrollY <= 0)) {
            val target = chapterIndex + direction
            if (target in (archive?.chapters?.indices ?: IntRange.EMPTY)) showChapter(target, if (direction < 0) 1f else 0f)
            return
        }
        val target = (web.scrollY + direction * (web.height - dp(28))).coerceIn(0, web.scrollRange())
        if (defaultSharedPreferences.getInt("omiPageAnimation", 1) == 1) {
            web.animate().cancel()
            web.animate().translationX(-direction * dp(36).toFloat()).alpha(.4f).setDuration(90).withEndAction {
                web.scrollTo(0, target)
                web.translationX = direction * dp(36).toFloat()
                web.animate().translationX(0f).alpha(1f).setDuration(110).start()
            }.start()
        } else web.scrollTo(0, target)
    }

    private fun showChapter(index: Int, fraction: Float = 0f, anchor: String? = null, saveCurrent: Boolean = true) {
        val document = archive ?: return
        if (index !in document.chapters.indices) return
        if (saveCurrent) persistPosition()
        handler.removeCallbacks(savePosition)
        ready = false
        finishingGeneration = -1
        openingFraction = fraction
        chapterIndex = index
        generation++
        val token = generation
        val chapter = document.chapters[index]
        subtitle.text = chapter.title
        loading.visibility = View.VISIBLE
        web.isEnabled = false
        val body = Jsoup.parseBodyFragment(chapter.html)
        body.outputSettings().prettyPrint(false)
        body.select("a[href]").filter { it.attr("href").startsWith("#") }.forEach { link ->
            val id = link.attr("href").removePrefix("#")
            document.anchors[id]?.let { target ->
                val query = if (target == index) "?v=$token" else ""
                link.attr("href", "$HOST/chapter/$target$query#${Uri.encode(id)}")
            }
        }
        val color = palette
        val css = """
            :root{color-scheme:${if (color.id == "night") "dark" else "light"}}
            @font-face{font-family:OmiUser;src:url('$HOST/omi-user-font')}
            html,body{margin:0;background:$readerBackground;color:$readerText;overflow-wrap:anywhere}
            body{box-sizing:border-box;padding:${ReadBookConfig.paddingTop}px ${ReadBookConfig.paddingRight}px ${ReadBookConfig.paddingBottom + 18}px ${ReadBookConfig.paddingLeft}px;font:${fontSize}px/$lineSpacing ${if (ReadBookConfig.textFont.isNotBlank()) "OmiUser," else ""}$fontFamily;letter-spacing:${ReadBookConfig.letterSpacing}em;font-weight:${if (ReadBookConfig.textBold == 1) 700 else if (ReadBookConfig.textBold == 2) 300 else 400}}
            p{margin:0 0 ${.5f + ReadBookConfig.paragraphSpacing / 10f}em;text-indent:${ReadBookConfig.paragraphIndent.length}em}h1,h2,h3,h4,h5,h6{line-height:1.45;margin:1.2em 0 .7em;font-weight:650;overflow-wrap:anywhere}
            h1{font-size:1.6em}h2{font-size:1.35em}h3{font-size:1.15em}h4,h5,h6{font-size:1.05em}
            body>h1:first-child,body>h2:first-child{margin-top:.2em}img{display:block;max-width:100%;height:auto;margin:1em auto}
            ul,ol{padding-left:1.6em;margin:.6em 0 1em}li{margin:.25em 0}li>p{margin:.2em 0}
            blockquote{margin:1em 0;padding:.4em 1em;border-left:3px solid ${color.accent};background:${color.background}}
            blockquote p:last-child{margin-bottom:0}pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:1em;background:${color.background};border-radius:10px;font-size:.85em;line-height:1.6}
            code{font-family:monospace;white-space:pre-wrap}p code{background:${color.background};padding:.1em .25em;border-radius:3px}
            table{border-collapse:collapse;max-width:100%;width:100%;table-layout:fixed;margin:1em 0;font-size:.85em}
            td,th{border:1px solid ${color.muted};padding:.4em;vertical-align:top;overflow-wrap:anywhere}th{background:${color.background}}td p,th p{margin:0 0 .3em}
            a{color:${color.accent};text-decoration:underline}hr{border:0;border-top:1px solid ${color.muted};margin:1.5em 0}
        """.trimIndent()
        val content = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https://omi-reader.invalid; font-src https://omi-reader.invalid; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'\">" +
            "<title>${TextUtils.htmlEncode(chapter.title)}</title><style>$css</style></head><body>${body.body().html()}</body></html>"
        htmlBytes = content.toByteArray(StandardCharsets.UTF_8)
        val fragment = if (anchor.isNullOrEmpty()) "" else "#${Uri.encode(anchor)}"
        web.loadUrl("$HOST/chapter/$index?v=$token$fragment")
    }

    private inner class LocalReaderClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
            val uri = request.url
            if (uri.scheme != "https" || uri.host != "omi-reader.invalid" || request.method != "GET") return blocked()
            return try {
                val path = uri.path.orEmpty()
                when {
                    path == "/omi-user-font" -> {
                        val path = ReadBookConfig.textFont
                        val bytes = if (path.startsWith("content://")) contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() } else java.io.File(path).takeIf { it.isFile }?.readBytes()
                        if (bytes == null) blocked() else response("font/ttf", bytes)
                    }
                    path == "/chapter/$chapterIndex" && (uri.getQueryParameter("v") == generation.toString()) ->
                        response("text/html", htmlBytes)
                    path.startsWith("/OEBPS/images/") -> {
                        val bytes = archive?.readAsset(path.removePrefix("/")) ?: return blocked()
                        val mime = when (path.substringAfterLast('.')) { "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/png" }
                        response(mime, bytes)
                    }
                    else -> blocked()
                }
            } catch (_: Exception) { blocked() }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return true
            return navigate(request.url, request.hasGesture())
        }

        @Deprecated("Older Android WebViews use this callback")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean = navigate(Uri.parse(url), true)

        override fun onPageFinished(view: WebView?, url: String?) {
            val uri = url?.let(Uri::parse) ?: return
            if (uri.host != "omi-reader.invalid" || uri.path != "/chapter/$chapterIndex" || uri.getQueryParameter("v") != generation.toString()) return
            val token = generation
            if (finishingGeneration == token) return
            finishingGeneration = token
            afterVisualCommit(token) { restorePositionAfterLayout(token, uri.fragment.isNullOrBlank()) }
        }
    }

    /** onPageFinished precedes Chromium's layout/paint; saving there can overwrite a real
     *  saved position with zero. Commit the document, then commit its restored scroll. */
    private fun afterVisualCommit(token: Int, action: () -> Unit) {
        fun complete() {
            if (token == generation && !isFinishing && !isDestroyed) action()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            web.postVisualStateCallback(token.toLong(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { complete() }
            })
        } else {
            web.postOnAnimation { web.postOnAnimation { complete() } }
        }
    }

    private fun restorePositionAfterLayout(token: Int, restoreScroll: Boolean, attempt: Int = 0) {
        if (token != generation || isFinishing || isDestroyed) return
        if (web.height == 0 || web.contentHeight == 0) {
            if (attempt >= 120) { fail(getString(R.string.omi_layout_error)); return }
            web.postOnAnimation { restorePositionAfterLayout(token, restoreScroll, attempt + 1) }
            return
        }
        val expected = (web.scrollRange() * openingFraction).roundToInt()
        if (restoreScroll) web.scrollTo(0, expected)
        afterVisualCommit(token) {
            val finalExpected = (web.scrollRange() * openingFraction).roundToInt()
            if (restoreScroll && (finalExpected != expected || kotlin.math.abs(web.scrollY - finalExpected) > 2)) {
                if (attempt >= 120) { fail(getString(R.string.omi_position_error)); return@afterVisualCommit }
                restorePositionAfterLayout(token, true, attempt + 1)
                return@afterVisualCommit
            }
            ready = true
            web.isEnabled = true
            loading.visibility = View.GONE
            updatePositionLabel()
            persistPosition()
        }
    }

    private fun navigate(uri: Uri, userGesture: Boolean): Boolean {
        if (uri.scheme == "https" && uri.host == "omi-reader.invalid") {
            val target = uri.path?.removePrefix("/chapter/")?.toIntOrNull()
            if (target != null && target in (archive?.chapters?.indices ?: IntRange.EMPTY)) {
                if (target == chapterIndex && uri.getQueryParameter("v") == generation.toString()) return false
                if (userGesture) showChapter(target, anchor = uri.fragment)
            }
            return true
        }
        if (userGesture && uri.scheme in setOf("http", "https", "mailto")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: Exception) { toast(getString(R.string.omi_no_link_app)) }
        }
        return true
    }

    private fun response(mime: String, bytes: ByteArray) = WebResourceResponse(mime, "UTF-8", 200, "OK",
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun fraction(): Float = if (!ready) openingFraction else if (web.scrollRange() == 0) 0f else (web.scrollY.toFloat() / web.scrollRange()).coerceIn(0f, 1f)

    private fun updatePositionLabel() {
        val count = archive?.chapters?.size ?: return
        val pageHeight = (web.height - dp(28)).coerceAtLeast(1)
        val current = (web.scrollY.toFloat() / pageHeight).roundToInt() + 1
        val pages = kotlin.math.ceil(web.scrollRange().toFloat() / pageHeight).toInt() + 1
        position.text = "$current / $pages"
        updateProgressCaption()
    }

    private fun updateProgressCaption() {
        if (!::progressCaption.isInitialized) return
        val pageHeight = (web.height - dp(28)).coerceAtLeast(1)
        val total = kotlin.math.ceil(web.scrollRange().toFloat() / pageHeight).toInt() + 1
        val page = ((web.scrollY.toFloat() / pageHeight).roundToInt() + 1).coerceIn(1, total)
        progressCaption.text = "$page/$total"
        val caption = getString(R.string.omi_progress_page, page, total)
        progressCaption.contentDescription = caption
        ViewCompat.setStateDescription(progressSeek, caption)
    }

    private fun persistPosition() {
        if (!ready) return
        val selected = book ?: return
        val chapter = archive?.chapters?.getOrNull(chapterIndex) ?: return
        val scroll = fraction()
        val index = chapterIndex
        val charPosition = (chapter.text.length * scroll).roundToInt()
        val savedAt = System.currentTimeMillis()
        prefs.edit().putFloat("position:$bookKey", scroll).putInt("chapter:$bookKey", index)
            .putLong("saved:$bookKey", savedAt).apply()
        selected.durChapterIndex = index
        selected.durChapterPos = charPosition
        selected.durChapterTitle = chapter.title
        val bookUrl = selected.bookUrl
        val count = archive?.chapters?.size ?: 1
        defaultSharedPreferences.edit().putFloat("omiProgress:$bookUrl", ((index + scroll) / count).coerceIn(0f, 1f)).apply()
        progressWriter.execute {
            runCatching {
                appDb.bookDao.getBook(bookUrl)?.let { current ->
                    current.durChapterIndex = index
                    current.durChapterPos = charPosition
                    current.durChapterTitle = chapter.title
                    current.durChapterTime = savedAt
                    current.totalChapterNum = count
                    current.lastCheckCount = 0
                    appDb.bookDao.update(current)
                }
            }.onFailure { AppLog.put("保存文档阅读进度失败", it) }
        }
    }

    private fun stepChapter(direction: Int) {
        val count = archive?.chapters?.size ?: return
        val target = chapterIndex + direction
        if (target in 0 until count) showChapter(target)
        else toast(if (direction > 0) getString(R.string.omi_last_chapter) else getString(R.string.omi_first_chapter))
    }

    private fun showContents() {
        val document = archive ?: return
        val selected = book ?: return
        OmiContents.show(this, this, selected.bookUrl, chapterIndex, bookmarkSelect = { mark ->
            val chapter = document.chapters.getOrNull(mark.chapterIndex)
            if (chapter != null) { showChapter(mark.chapterIndex, mark.chapterPos.toFloat() / max(1, chapter.text.length)); setChrome(false) }
        }) { showChapter(it); setChrome(false) }
    }

    private fun showTypography() {
        OmiReaderPanels.fullTypography(this, { refreshReadingOptions() }, complete = true)
    }

    private fun showMore() = OmiSheet(this, book?.name ?: getString(R.string.omi_reading_settings)).apply {
        OmiReaderPanels.fullscreen(this) { refreshReadingOptions() }
        action(getString(R.string.omi_find_text), R.drawable.ic_search) { showSearch() }
        show()
    }

    private fun addBookmark() {
        val selected = book ?: return
        if (!ready) return
        val index = chapterIndex
        val chapter = archive?.chapters?.getOrNull(index) ?: return
        val offset = (chapter.text.length * fraction()).roundToInt().coerceIn(0, chapter.text.length)
        OmiSheet(this, getString(R.string.omi_add_bookmark)).apply {
            val input = EditText(this@RichDocumentActivity).apply { hint = getString(R.string.omi_note_hint) }
            body.addView(input, LinearLayout.LayoutParams(-1, dp(80)))
            action(getString(R.string.omi_save)) {
                val mark = Bookmark(bookName = selected.name, bookAuthor = selected.author, chapterIndex = index,
                    chapterPos = offset, chapterName = chapter.title, bookText = chapter.text.drop(offset).take(100), content = input.text.toString())
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { appDb.bookmarkDao.insert(mark) }
                    toast(getString(R.string.omi_bookmark_saved))
                }
            }
            show()
        }
    }

    private fun showBookmarks() {
        val selected = book ?: return
        lifecycleScope.launch {
            val marks = withContext(Dispatchers.IO) { appDb.bookmarkDao.getByBook(selected.name, selected.author) }
            OmiSheet(this@RichDocumentActivity, getString(R.string.omi_bookmarks)).apply {
                if (marks.isEmpty()) note(getString(R.string.omi_empty_bookmarks))
                marks.forEach { mark ->
                    action(mark.chapterName + if (mark.content.isBlank()) "" else " · ${mark.content}") {
                        archive?.chapters?.getOrNull(mark.chapterIndex)?.let { chapter ->
                            showChapter(mark.chapterIndex, (mark.chapterPos.toFloat() / max(1, chapter.text.length)).coerceIn(0f, 1f))
                        }
                    }
                }
                show()
            }
        }
    }

    private fun showSearch() {
        if (!ready) return
        OmiSheet(this, getString(R.string.omi_find_text)).apply {
            val input = EditText(this@RichDocumentActivity).apply { hint = getString(R.string.omi_find_in_chapter); isSingleLine = true }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { web.findAllAsync(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            body.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
            action(getString(R.string.next_page), close = false) { web.findNext(true) }
            action(getString(R.string.prev_page), close = false) { web.findNext(false) }
            setOnDismissListener { web.clearMatches() }
            show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (ready && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val now = System.currentTimeMillis()
            if (event.repeatCount == 0 || now - lastVolumeTime > 220) {
                lastVolumeTime = now
                turnPage(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (ready && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) true else super.onKeyUp(keyCode, event)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("chapter", chapterIndex); outState.putFloat("fraction", fraction())
        persistPosition(); super.onSaveInstanceState(outState)
    }

    override fun onPause() { persistPosition(); super.onPause(); if (::web.isInitialized) web.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    private fun finishReader() {
        persistPosition()
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun fail(message: String) {
        if (::loading.isInitialized) loading.visibility = View.GONE
        AlertDialog.Builder(this).setTitle(getString(R.string.omi_open_error)).setMessage(message)
            .setPositiveButton(getString(R.string.back)) { _, _ -> finishReader() }.setOnCancelListener { finishReader() }.show()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun text(label: String, size: Int) = TextView(this).apply { text = label; textSize = size.toFloat(); setTextColor(Color.parseColor(palette.text)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun action(label: String, description: String, size: Int = 24, onClick: () -> Unit) = text(label, size).apply {
        gravity = Gravity.CENTER; contentDescription = description; isClickable = true; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor(palette.accent) and 0x33ffffff),
            null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })
        setOnClickListener { onClick() }
    }
    private class ReaderWebView(context: android.content.Context) : WebView(context) {
        var scrollListener: (() -> Unit)? = null
        var onCenterTap: (() -> Unit)? = null
        var onPageTurn: ((Int) -> Unit)? = null
        var pageMode: () -> Int = { 1 }
        private var downX = 0f
        private var downY = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_MOVE -> if (pageMode() != 3) return true
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX; val dy = event.y - downY
                    val threshold = 24 * resources.displayMetrics.density
                    val swipe = kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold
                    val tap = !swipe && event.eventTime - event.downTime < 400
                    val link = hitTestResult.type in setOf(HitTestResult.SRC_ANCHOR_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                    if ((swipe && pageMode() != 3) || (tap && !link)) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel); cancel.recycle()
                        if (swipe) onPageTurn?.invoke(if ((if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) dx else dy) < 0) 1 else -1)
                        else if (pageMode() == 3 || event.x in width * .3f..width * .7f) { performClick(); onCenterTap?.invoke() }
                        else onPageTurn?.invoke(if (event.x > width / 2) 1 else -1)
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        fun scrollRange() = (computeVerticalScrollRange() - height).coerceAtLeast(0)
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); scrollListener?.invoke() }
    }
}
