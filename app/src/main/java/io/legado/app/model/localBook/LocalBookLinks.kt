package io.legado.app.model.localBook

import android.util.Base64
import android.net.Uri
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

/** Carries local HTML links through the existing text formatter without rendering markup. */
object LocalBookLinks {
    private const val PREFIX = "\uE000omi:"
    private const val END = "\uE001"
    const val VERSION = "\uE000omi:v:1\uE001"
    val tokenPattern = Regex("\uE000omi:([laev]):([A-Za-z0-9_-]*)\uE001")

    data class Span(val start: Int, val end: Int, val target: String)
    data class Paragraph(val links: List<Span> = emptyList(), val anchors: Map<String, Int> = emptyMap()) {
        fun linkAt(offset: Int): String? {
            var low = 0; var high = links.lastIndex
            while (low <= high) {
                val mid = (low + high) ushr 1; val span = links[mid]
                when { offset < span.start -> high = mid - 1; offset >= span.end -> low = mid + 1; else -> return span.target }
            }
            return null
        }
        fun collapseImages(text: String): Paragraph {
            val matcher = io.legado.app.constant.AppPattern.imgPattern.matcher(text)
            val images = arrayListOf<IntRange>()
            while (matcher.find()) images += matcher.start() until matcher.end()
            fun map(position: Int): Int {
                var dropped = 0
                for (image in images) {
                    if (position <= image.first) break
                    if (position <= image.last + 1) return image.first - dropped + 1
                    dropped += image.last - image.first
                }
                return position - dropped
            }
            return Paragraph(links.map { it.copy(start = map(it.start), end = map(it.end)) }, anchors.mapValues { map(it.value) })
        }
        fun slice(start: Int, end: Int) = Paragraph(
            links.mapNotNull { span ->
                val a = maxOf(start, span.start); val b = minOf(end, span.end)
                if (a < b) Span(a - start, b - start, span.target) else null
            }, anchors.filterValues { it in start..end }.mapValues { it.value - start }
        )
    }
    data class Decoded(val text: String, val links: List<Span>, val anchors: Map<String, Int>) {
        fun normalize(start: Int, end: Int, indent: Int) = Paragraph(
            links.mapNotNull { span ->
                val a = maxOf(start, span.start); val b = minOf(end, span.end)
                if (a < b) Span(a - start + indent, b - start + indent, span.target) else null
            }, anchors.mapValues { (it.value - start).coerceIn(0, end - start) + indent }
        )
    }
    data class Destination(val index: Int, val anchor: String? = null)

    private fun token(kind: Char, value: String): String = PREFIX + kind + ":" +
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + END
    fun anchor(target: String) = token('a', target)
    private fun decode(value: String) = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrDefault("")

    class Decoder {
        private var active: String? = null
        fun read(input: String): Decoded {
            val text = StringBuilder(); val links = arrayListOf<Span>(); val anchors = linkedMapOf<String, Int>()
            var previous = 0; var beginning = 0
            fun finish() { active?.let { if (beginning < text.length) links += Span(beginning, text.length, it) } }
            tokenPattern.findAll(input).forEach { match ->
                text.append(input, previous, match.range.first)
                when (match.groupValues[1]) {
                    "l" -> { finish(); active = decode(match.groupValues[2]).takeIf { it.isNotEmpty() }; beginning = text.length }
                    "e" -> { finish(); active = null; beginning = text.length }
                    "a" -> anchors.putIfAbsent(decode(match.groupValues[2]), text.length)
                }
                previous = match.range.last + 1
            }
            text.append(input, previous, input.length); finish()
            return Decoded(text.toString(), links, anchors)
        }
    }

    fun retainedPrefix(input: String): String {
        val result = StringBuilder(); var active: String? = null
        tokenPattern.findAll(input).forEach {
            when (it.groupValues[1]) {
                "a" -> result.append(it.value)
                "l" -> active = it.value
                "e" -> active = null
            }
        }
        active?.let(result::append)
        return result.toString()
    }

    /** Only package-relative EPUB destinations. External URLs never become local book actions. */
    fun epubTarget(base: String, href: String): String? = runCatching {
        if (href.isBlank()) return null
        val resolved = URI(base.replace(" ", "%20")).resolve(href.replace(" ", "%20")).normalize()
        if (resolved.isAbsolute || resolved.rawAuthority != null) return null
        if (resolved.path.startsWith("/") || resolved.path == ".." || resolved.path.startsWith("../")) return null
        URI(null, null, resolved.path, null, resolved.fragment?.takeIf { it.isNotEmpty() }).toASCIIString()
    }.getOrNull()

    fun mobiPointer(href: String): String {
        val value = href.substringBefore('#').removePrefix("skip:").replaceFirst(Regex("^\\d+:"), "")
        if (value.startsWith("filepos:")) return value.removePrefix("filepos:").toIntOrNull()?.takeIf { it >= 0 }?.let {
            "filepos:${it.toString().padStart(10, '0')}"
        }.orEmpty()
        return Regex("kindle:pos:fid:([0-9a-v]+):off:([0-9a-v]+)", RegexOption.IGNORE_CASE).matchEntire(value)?.let {
            "kindle:pos:fid:${it.groupValues[1].uppercase(java.util.Locale.ROOT).padStart(4, '0')}:off:${it.groupValues[2].uppercase(java.util.Locale.ROOT).padStart(10, '0')}"
        }.orEmpty()
    }

    fun mark(root: Element, base: String, mobi: Boolean = false) {
        val resource = if (mobi) mobiPointer(base) else epubTarget("", base)
        if (!resource.isNullOrEmpty()) root.prependChild(TextNode(anchor(resource)))
        root.select("[id],a[name]").toList().forEach { node ->
            val id = node.id().ifEmpty { node.attr("name") }
            if (id.isNotEmpty()) {
                val target = if (mobi) mobiPointer(base) + "#" + Uri.encode(id) else epubTarget(base, "#${Uri.encode(id)}")
                if (target != null) {
                    val marker = TextNode(anchor(target))
                    if (node === root) node.prependChild(marker) else node.before(marker)
                }
            }
        }
        root.select("a[href],a[filepos]").forEach { link ->
            val href = if (link.hasAttr("filepos")) "filepos:${link.attr("filepos")}" else link.attr("href")
            val target = if (mobi) {
                if (href.startsWith("#")) mobiPointer(base) + "#" + Uri.encode(Uri.decode(href.removePrefix("#"))) else mobiPointer(href).takeIf { it.isNotEmpty() }
            } else epubTarget(base, href)
            if (!target.isNullOrEmpty()) {
                link.prependChild(TextNode(token('l', target)))
                link.appendChild(TextNode(token('e', "")))
            }
        }
    }

    /** A MOBI byte destination may point into an opening tag; keep the marker outside it. */
    fun insertAnchor(html: String, characterOffset: Int, target: String): String {
        var at = characterOffset.coerceIn(0, html.length)
        val opening = html.lastIndexOf('<', at.coerceAtMost(html.lastIndex))
        if (opening > html.lastIndexOf('>', at.coerceAtMost(html.lastIndex))) at = opening
        return html.substring(0, at) + anchor(target) + html.substring(at)
    }
}
