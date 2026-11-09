package io.legado.app.model.localBook;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Offline import of Word/Markdown into an ordinary, portable EPUB.
 *  No Android classes are used here so the parser and archive boundaries can be tested on the JVM.
 */
public final class DocumentImportConverter {
    static final long MAX_INPUT = 64L * 1024 * 1024;
    static final long MAX_UNPACKED = 128L * 1024 * 1024;
    static final int MAX_XML = 16 * 1024 * 1024;
    static final int MAX_MARKDOWN = 8 * 1024 * 1024;
    static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String XHTML = "http://www.w3.org/1999/xhtml";
    private static final Runnable NO_CANCEL = () -> { };

    private DocumentImportConverter() { }

    public static boolean supports(String filename) {
        String extension = extension(filename);
        return extension.equals("docx") || extension.equals("md") || extension.equals("markdown");
    }

    /** Caller owns input. Output is content-addressed and is only installed after successful conversion. */
    public static File convert(InputStream input, String filename, File outputDir, Runnable checkCancelled)
            throws IOException {
        if (!supports(filename)) throw new IOException("不支持的文档格式：" + extension(filename));
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) throw new IOException("无法创建文档保存目录。");
        Runnable cancel = checkCancelled == null ? NO_CANCEL : checkCancelled;
        File source = File.createTempFile("omi-import-", ".input", outputDir);
        File pending = null;
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (Exception error) { throw new IOException(error); }
            digest.update(filename.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (FileOutputStream copy = new FileOutputStream(source)) {
                byte[] buffer = new byte[32768];
                long copied = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    cancelled(cancel);
                    copied += count;
                    long limit = extension(filename).equals("docx") ? MAX_INPUT : MAX_MARKDOWN;
                    if (copied > limit) throw new IOException("文档过大：DOCX 上限 64 MB，Markdown 上限 8 MB。");
                    copy.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                if (copied == 0) throw new IOException("文件内容为空。");
            }
            String key = hex(digest.digest());
            File target = new File(outputDir, key + ".epub");
            cancelled(cancel);
            if (target.isFile() && target.length() > 0) return target;
            BookData data = extension(filename).equals("docx")
                    ? readDocx(source, filename, cancel) : readMarkdown(source, filename, cancel);
            cancelled(cancel);
            pending = File.createTempFile("omi-epub-", ".partial", outputDir);
            writeEpub(data, key, filename, pending, cancel);
            cancelled(cancel);
            synchronized (DocumentImportConverter.class) {
                if (target.isFile() && target.length() > 0) return target;
                if (!pending.renameTo(target)) throw new IOException("保存转换后的书籍失败。");
            }
            return target;
        } finally {
            source.delete();
            if (pending != null) pending.delete();
        }
    }

    private static BookData readMarkdown(File source, String filename, Runnable cancel) throws IOException {
        byte[] bytes;
        try (InputStream input = new FileInputStream(source)) { bytes = readBounded(input, MAX_MARKDOWN, cancel); }
        String markdown = decodeText(bytes);
        cancelled(cancel);
        List<org.commonmark.Extension> extensions = Collections.singletonList(TablesExtension.create());
        String html = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build()
                .render(Parser.builder().extensions(extensions).build().parse(markdown));
        cancelled(cancel);
        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(html);
        // Markdown images can refer to files outside a single-document grant or to remote servers.
        // Keep their captions; opening a local book never triggers a network request.
        for (Element img : document.select("img")) {
            img.replaceWith(new org.jsoup.nodes.TextNode("[图片" + (img.attr("alt").isEmpty() ? "" : "：" + img.attr("alt")) + "]"));
        }
        BookData data = new BookData(baseName(filename));
        Element firstHeading = document.selectFirst("h1");
        if (firstHeading != null && !firstHeading.text().trim().isEmpty()) data.title = firstHeading.text().trim();
        data.body = document.body();
        return data;
    }

    private static BookData readDocx(File source, String filename, Runnable cancel) throws IOException {
        BookData data = new BookData(baseName(filename));
        try (ZipFile zip = new ZipFile(source)) {
            validateArchive(zip, cancel);
            if (zip.getEntry("word/document.xml") == null) throw new IOException("文件不是有效的 DOCX 文档。");
            DocxContext context = new DocxContext(zip, data, cancel);
            Document main = xml(readEntry(zip, "word/document.xml", MAX_XML, cancel));
            Document core = optionalXml(zip, "docProps/core.xml", cancel);
            if (core != null) {
                String title = firstText(core, "title");
                String author = firstText(core, "creator");
                if (!title.trim().isEmpty()) data.title = title.trim();
                data.author = author.trim();
            }
            context.readRelationships();
            context.readStyles();
            context.readNumbering();
            org.w3c.dom.Element body = first(main.getDocumentElement(), "body");
            if (body == null) throw new IOException("DOCX 文档缺少正文。");
            context.blocks(body, data.body, 0);
            if (data.body.text().trim().isEmpty() && data.assets.isEmpty()) throw new IOException("文档没有可阅读的正文。");
        }
        return data;
    }

    private static final class BookData {
        String title;
        String author = "";
        Element body = new Element("body");
        final Map<String, byte[]> assets = new LinkedHashMap<>();
        BookData(String title) { this.title = title; }
    }

    private static final class Relationship {
        final String target;
        final boolean external;
        Relationship(String target, boolean external) { this.target = target; this.external = external; }
    }

    private static final class ListStyle {
        final String type;
        final int start;
        ListStyle(String type, int start) { this.type = type; this.start = start; }
    }

    private static final class DocxContext {
        final ZipFile zip;
        final BookData data;
        final Runnable cancel;
        final Map<String, Relationship> relationships = new HashMap<>();
        final Map<String, org.w3c.dom.Element> styles = new HashMap<>();
        final Map<String, String> numToAbstract = new HashMap<>();
        final Map<String, ListStyle> listStyles = new HashMap<>();
        final Map<String, String> imageCache = new HashMap<>();
        int nodeCount;

        DocxContext(ZipFile zip, BookData data, Runnable cancel) { this.zip = zip; this.data = data; this.cancel = cancel; }

        void readRelationships() throws IOException {
            Document document = optionalXml(zip, "word/_rels/document.xml.rels", cancel);
            if (document == null) return;
            for (org.w3c.dom.Element item : children(document.getDocumentElement())) {
                if (local(item).equals("Relationship")) {
                    relationships.put(item.getAttribute("Id"), new Relationship(item.getAttribute("Target"),
                            "External".equalsIgnoreCase(item.getAttribute("TargetMode"))));
                }
            }
        }

        void readStyles() throws IOException {
            Document document = optionalXml(zip, "word/styles.xml", cancel);
            if (document == null) return;
            for (org.w3c.dom.Element style : children(document.getDocumentElement())) {
                if (local(style).equals("style")) styles.put(attr(style, "styleId"), style);
            }
        }

        void readNumbering() throws IOException {
            Document document = optionalXml(zip, "word/numbering.xml", cancel);
            if (document == null) return;
            for (org.w3c.dom.Element item : children(document.getDocumentElement())) {
                if (local(item).equals("num")) {
                    numToAbstract.put(attr(item, "numId"), val(child(item, "abstractNumId")));
                } else if (local(item).equals("abstractNum")) {
                    String id = attr(item, "abstractNumId");
                    for (org.w3c.dom.Element level : children(item)) {
                        if (!local(level).equals("lvl")) continue;
                        listStyles.put(id + ":" + attr(level, "ilvl"), new ListStyle(val(child(level, "numFmt")),
                                integer(val(child(level, "start")), 1, 1, 1000000)));
                    }
                }
            }
        }

        void check(int depth) throws IOException {
            cancelled(cancel);
            if (++nodeCount > 250000 || depth > 64) throw new IOException("文档结构过于复杂。");
        }

        void blocks(org.w3c.dom.Element source, Element target, int depth) throws IOException {
            check(depth);
            List<Element> lists = new ArrayList<>();
            String previousNum = "";
            for (org.w3c.dom.Element item : children(source)) {
                check(depth);
                String tag = local(item);
                if (tag.equals("p")) {
                    org.w3c.dom.Element pPr = child(item, "pPr");
                    org.w3c.dom.Element numPr = child(pPr, "numPr");
                    String numId = val(child(numPr, "numId"));
                    if (!numId.isEmpty() && !numId.equals("0")) {
                        int level = integer(val(child(numPr, "ilvl")), 0, 0, 8);
                        if (!previousNum.equals(numId)) lists.clear();
                        previousNum = numId;
                        while (lists.size() > level + 1) lists.remove(lists.size() - 1);
                        ListStyle style = listStyles.get(numToAbstract.get(numId) + ":" + level);
                        String listTag = style != null && style.type.equals("bullet") ? "ul" : "ol";
                        if (lists.size() == level + 1 && !lists.get(level).tagName().equals(listTag)) lists.remove(level);
                        while (lists.size() < level + 1) {
                            Element parent = target;
                            if (!lists.isEmpty()) {
                                Element lastList = lists.get(lists.size() - 1);
                                parent = lastList.children().last();
                                if (parent == null) parent = lastList.appendElement("li");
                            }
                            Element list = parent.appendElement(listTag);
                            if (listTag.equals("ol") && style != null && style.start > 1) list.attr("start", Integer.toString(style.start));
                            lists.add(list);
                        }
                        inline(item, lists.get(level).appendElement("li"), depth + 1);
                    } else {
                        lists.clear();
                        previousNum = "";
                        int heading = headingLevel(pPr);
                        Element paragraph = target.appendElement(heading > 0 ? "h" + heading : "p");
                        inline(item, paragraph, depth + 1);
                        if (paragraph.childNodeSize() == 0) paragraph.appendElement("br");
                    }
                } else {
                    lists.clear(); previousNum = "";
                    if (tag.equals("tbl")) table(item, target, depth + 1);
                    else if (tag.equals("sdt")) {
                        org.w3c.dom.Element content = child(item, "sdtContent");
                        if (content != null) blocks(content, target, depth + 1);
                    } else if (tag.equals("ins") || tag.equals("customXml")) blocks(item, target, depth + 1);
                }
            }
        }

        int headingLevel(org.w3c.dom.Element pPr) {
            org.w3c.dom.Element outline = child(pPr, "outlineLvl");
            if (outline != null) return integer(val(outline), 9, 0, 9) < 6 ? integer(val(outline), 9, 0, 9) + 1 : 0;
            String id = val(child(pPr, "pStyle"));
            for (int i = 0; i < 12 && !id.isEmpty(); i++) {
                int guessed = headingName(id);
                if (guessed > 0) return guessed;
                org.w3c.dom.Element style = styles.get(id);
                if (style == null) break;
                guessed = headingName(val(child(style, "name")));
                if (guessed > 0) return guessed;
                outline = child(child(style, "pPr"), "outlineLvl");
                if (outline != null) {
                    int level = integer(val(outline), 9, 0, 9);
                    if (level < 6) return level + 1;
                }
                id = val(child(style, "basedOn"));
            }
            return 0;
        }

        void table(org.w3c.dom.Element source, Element target, int depth) throws IOException {
            check(depth);
            Element table = target.appendElement("table");
            Element body = table.appendElement("tbody");
            for (org.w3c.dom.Element row : children(source)) {
                if (!local(row).equals("tr")) continue;
                Element tr = body.appendElement("tr");
                boolean header = child(child(row, "trPr"), "tblHeader") != null;
                for (org.w3c.dom.Element cell : children(row)) {
                    if (!local(cell).equals("tc")) continue;
                    Element td = tr.appendElement(header ? "th" : "td");
                    int span = integer(val(child(child(cell, "tcPr"), "gridSpan")), 1, 1, 64);
                    if (span > 1) td.attr("colspan", Integer.toString(span));
                    blocks(cell, td, depth + 1);
                }
            }
        }

        void inline(org.w3c.dom.Element source, Element target, int depth) throws IOException {
            check(depth);
            for (org.w3c.dom.Element item : children(source)) {
                check(depth);
                String tag = local(item);
                switch (tag) {
                    case "pPr": case "rPr": case "del": case "instrText": case "fldChar": break;
                    case "t": target.appendText(item.getTextContent()); break;
                    case "tab": target.appendText("\u2003"); break;
                    case "br": case "cr": target.appendElement("br"); break;
                    case "noBreakHyphen": target.appendText("\u2011"); break;
                    case "softHyphen": target.appendText("\u00ad"); break;
                    case "r": {
                        org.w3c.dom.Element properties = child(item, "rPr");
                        Element run = target;
                        if (enabled(child(properties, "b"))) run = run.appendElement("strong");
                        if (enabled(child(properties, "i"))) run = run.appendElement("em");
                        if (enabled(child(properties, "u"))) run = run.appendElement("u");
                        if (enabled(child(properties, "strike"))) run = run.appendElement("s");
                        String vertical = val(child(properties, "vertAlign"));
                        if (vertical.equals("superscript")) run = run.appendElement("sup");
                        if (vertical.equals("subscript")) run = run.appendElement("sub");
                        inline(item, run, depth + 1);
                        break;
                    }
                    case "hyperlink": {
                        Relationship relation = relationships.get(item.getAttributeNS(R, "id"));
                        String anchor = attr(item, "anchor");
                        String href = !anchor.isEmpty() ? "#" + anchor : relation == null ? "" : relation.target;
                        Element link = safeLink(href) ? target.appendElement("a").attr("href", href) : target;
                        inline(item, link, depth + 1);
                        break;
                    }
                    case "bookmarkStart": {
                        String name = attr(item, "name");
                        if (name.matches("[\\p{L}\\p{N}_-]{1,150}")) target.appendElement("span").attr("id", name);
                        break;
                    }
                    case "drawing": case "pict": image(item, target); break;
                    case "sdt": {
                        org.w3c.dom.Element content = child(item, "sdtContent");
                        if (content != null) inline(content, target, depth + 1);
                        break;
                    }
                    default: inline(item, target, depth + 1);
                }
            }
        }

        void image(org.w3c.dom.Element source, Element target) throws IOException {
            org.w3c.dom.Element blip = first(source, "blip");
            org.w3c.dom.Element legacy = first(source, "imagedata");
            String id = blip != null ? blip.getAttributeNS(R, "embed") : legacy != null ? legacy.getAttributeNS(R, "id") : "";
            org.w3c.dom.Element props = first(source, "docPr");
            String alt = props == null ? "" : props.getAttribute("descr");
            Relationship relation = relationships.get(id);
            if (relation == null || relation.external) { target.appendText("[图片" + (alt.isEmpty() ? "" : "：" + alt) + "]"); return; }
            String entry = resolvePart("word/document.xml", relation.target);
            if (!entry.startsWith("word/media/")) throw new IOException("DOCX 图片引用超出媒体目录。");
            String imagePath = imageCache.get(entry);
            if (imagePath == null) {
                byte[] bytes = readEntry(zip, entry, MAX_IMAGE, cancel);
                String extension = imageExtension(bytes);
                if (extension == null) { target.appendText("[图片" + (alt.isEmpty() ? "" : "：" + alt) + "]"); return; }
                if (data.assets.size() >= 1000) throw new IOException("文档图片数量超过上限。");
                imagePath = "images/image-" + (data.assets.size() + 1) + "." + extension;
                data.assets.put(imagePath, bytes);
                imageCache.put(entry, imagePath);
            }
            target.appendElement("img").attr("src", imagePath).attr("alt", alt);
        }
    }

    private static void writeEpub(BookData data, String key, String filename, File target, Runnable cancel) throws IOException {
        Safelist safe = new Safelist().addTags("p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "br", "hr",
                        "strong", "b", "em", "i", "u", "s", "del", "sup", "sub", "blockquote", "pre", "code", "ul", "ol", "li",
                        "a", "img", "table", "thead", "tbody", "tr", "th", "td")
                .addAttributes(":all", "id").addAttributes("a", "href", "title").addProtocols("a", "href", "http", "https", "mailto", "#")
                .addAttributes("img", "src", "alt").addAttributes("ol", "start").addAttributes("td", "colspan", "rowspan")
                .addAttributes("th", "colspan", "rowspan").preserveRelativeLinks(true);
        org.jsoup.nodes.Document html = new Cleaner(safe).clean(Jsoup.parseBodyFragment(data.body.html()));
        for (Element image : html.select("img")) if (!data.assets.containsKey(image.attr("src"))) image.remove();
        for (Element link : html.select("a[href]")) if (!safeLink(link.attr("href"))) link.removeAttr("href");
        html.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8).prettyPrint(false);
        List<Element> headings = new ArrayList<>();
        for (Element element : html.body().children()) if (element.tagName().matches("h[1-6]")) headings.add(element);
        if (headings.size() > 10000) throw new IOException("文档标题数量超过上限。");
        if (headings.isEmpty() || html.body().children().first() != headings.get(0)) {
            Element start = html.body().prependElement("span").attr("id", "omi-start");
            headings.add(0, start);
        }
        for (int i = 0; i < headings.size(); i++) {
            Element heading = headings.get(i);
            if (!heading.hasAttr("id")) heading.attr("id", "omi-heading-" + i);
        }
        StringBuilder toc = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head><meta name=\"dtb:uid\" content=\"urn:sha256:")
                .append(key).append("\"/><meta name=\"dtb:depth\" content=\"1\"/><meta name=\"dtb:totalPageCount\" content=\"0\"/><meta name=\"dtb:maxPageNumber\" content=\"0\"/></head><docTitle><text>")
                .append(escape(data.title)).append("</text></docTitle><navMap>");
        for (int i = 0; i < headings.size(); i++) {
            cancelled(cancel);
            Element heading = headings.get(i);
            String title = heading.text().trim();
            if (title.isEmpty()) title = data.title;
            toc.append("<navPoint id=\"n").append(i).append("\" playOrder=\"").append(i + 1)
                    .append("\"><navLabel><text>").append(escape(title)).append("</text></navLabel><content src=\"content.xhtml#")
                    .append(escape(heading.id())).append("\"/></navPoint>");
        }
        toc.append("</navMap></ncx>");
        StringBuilder packageFile = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"book-id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"book-id\">urn:sha256:")
                .append(key).append("</dc:identifier><dc:title>").append(escape(data.title)).append("</dc:title><dc:creator>")
                .append(escape(data.author)).append("</dc:creator><dc:language>zh</dc:language><dc:source>")
                .append(escape(filename)).append("</dc:source><dc:description>由 OmiReader 在设备本地导入转换。</dc:description><meta name=\"omi:source-format\" content=\"")
                .append(extension(filename)).append("\"/></metadata><manifest>")
                .append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
                .append("<item id=\"body\" href=\"content.xhtml\" media-type=\"application/xhtml+xml\"/>")
                .append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>");
        int imageIndex = 0;
        for (String image : data.assets.keySet()) {
            String type = extension(image).equals("jpg") ? "jpeg" : extension(image);
            packageFile.append("<item id=\"image-").append(imageIndex++).append("\" href=\"").append(image)
                    .append("\" media-type=\"image/").append(type).append("\"/>");
        }
        packageFile.append("</manifest><spine toc=\"ncx\"><itemref idref=\"body\"/></spine></package>");
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"" + XHTML + "\"><head><title>" + escape(data.title)
                + "</title><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; form-action 'none'\"/><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body>" + html.body().html() + "</body></html>";
        String css = "body{line-height:1.7;overflow-wrap:break-word}p{margin:.6em 0}h1,h2,h3,h4,h5,h6{line-height:1.4;page-break-after:avoid}img{max-width:100%;height:auto}blockquote{margin:1em 0;padding:0 1em;border-left:3px solid #9aa5b5}pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:.7em;background:#e9edf2}code{font-family:monospace}table{border-collapse:collapse;max-width:100%;width:100%;table-layout:fixed}td,th{border:1px solid #9aa5b5;padding:.35em;word-wrap:break-word}ul,ol{padding-left:1.6em}a{color:inherit;text-decoration:underline}";
        try (FileOutputStream file = new FileOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file)) {
            byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32(); crc.update(mime);
            ZipEntry mimetype = new ZipEntry("mimetype"); mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(mime.length); mimetype.setCompressedSize(mime.length); mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype); zip.write(mime); zip.closeEntry();
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"OEBPS/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>".getBytes(StandardCharsets.UTF_8), cancel);
            put(zip, "OEBPS/package.opf", packageFile.toString().getBytes(StandardCharsets.UTF_8), cancel);
            put(zip, "OEBPS/toc.ncx", toc.toString().getBytes(StandardCharsets.UTF_8), cancel);
            put(zip, "OEBPS/content.xhtml", content.getBytes(StandardCharsets.UTF_8), cancel);
            put(zip, "OEBPS/style.css", css.getBytes(StandardCharsets.UTF_8), cancel);
            for (Map.Entry<String, byte[]> image : data.assets.entrySet()) put(zip, "OEBPS/" + image.getKey(), image.getValue(), cancel);
            zip.finish(); file.getFD().sync();
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes, Runnable cancel) throws IOException {
        cancelled(cancel);
        zip.putNextEntry(new ZipEntry(name));
        for (int offset = 0; offset < bytes.length; offset += 32768) {
            cancelled(cancel); zip.write(bytes, offset, Math.min(32768, bytes.length - offset));
        }
        zip.closeEntry();
    }

    static void validateArchive(ZipFile zip, Runnable cancel) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        long total = 0;
        int count = 0;
        java.util.HashSet<String> names = new java.util.HashSet<>();
        while (entries.hasMoreElements()) {
            cancelled(cancel);
            ZipEntry entry = entries.nextElement();
            if (++count > 5000) throw new IOException("DOCX 压缩包包含过多文件。");
            String name = entry.getName();
            if (name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) throw new IOException("DOCX 包含不安全的文件路径。");
            for (String part : name.split("/")) if (part.equals("..")) throw new IOException("DOCX 包含越界文件路径。");
            if (!names.add(name)) throw new IOException("DOCX 包含重复文件路径。");
            if (entry.getSize() < 0 || entry.getSize() > MAX_INPUT) throw new IOException("DOCX 内部文件过大或长度无效。");
            total += entry.getSize();
            if (total > MAX_UNPACKED) throw new IOException("DOCX 解压后超过 128 MB。");
        }
    }

    static byte[] readEntry(ZipFile zip, String name, int limit, Runnable cancel) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("DOCX 缺少文件：" + name);
        if (entry.getSize() > limit) throw new IOException("DOCX 内部文件超过读取上限：" + name);
        byte[] bytes;
        try (InputStream input = zip.getInputStream(entry)) { bytes = readBounded(input, limit, cancel); }
        CRC32 crc = new CRC32(); crc.update(bytes);
        if (entry.getSize() != bytes.length || (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())) throw new IOException("DOCX 内部文件校验失败：" + name);
        return bytes;
    }

    private static byte[] readBounded(InputStream input, int limit, Runnable cancel) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            cancelled(cancel);
            if (output.size() > limit - count) throw new IOException("文档内容超过读取上限。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Document optionalXml(ZipFile zip, String name, Runnable cancel) throws IOException {
        return zip.getEntry(name) == null ? null : xml(readEntry(zip, name, MAX_XML, cancel));
    }

    private static Document xml(byte[] bytes) throws IOException {
        // This also rejects UTF-16/32 declarations: ASCII markup survives removing NUL bytes.
        String declarations = new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "").toUpperCase(Locale.ROOT);
        if (declarations.contains("<!DOCTYPE") || declarations.contains("<!ENTITY")) throw new IOException("DOCX XML 不允许 DTD 或外部实体。");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) { }
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are disabled"); });
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
                @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception error) { throw new IOException("DOCX XML 格式无效。", error); }
    }

    private static void feature(DocumentBuilderFactory factory, String name, boolean enabled) {
        try { factory.setFeature(name, enabled); } catch (Exception ignored) { /* Android supports a smaller feature vocabulary; DTD rejection and resolver above stay mandatory. */ }
    }

    private static String resolvePart(String base, String target) throws IOException {
        try {
            java.net.URI uri = new java.net.URI(target.replace(" ", "%20"));
            if (uri.isAbsolute() || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IOException("DOCX 图片引用无效。");
            String path = uri.getPath();
            if (path == null || path.contains("\\") || path.indexOf('\0') >= 0) throw new IOException("DOCX 图片路径无效。");
            String combined = path.startsWith("/") ? path.substring(1) : base.substring(0, base.lastIndexOf('/') + 1) + path;
            List<String> segments = new ArrayList<>();
            for (String part : combined.split("/")) {
                if (part.equals("..")) {
                    if (segments.isEmpty()) throw new IOException("DOCX 图片引用越界。");
                    segments.remove(segments.size() - 1);
                } else if (!part.equals(".") && !part.isEmpty()) segments.add(part);
            }
            return String.join("/", segments);
        } catch (java.net.URISyntaxException error) { throw new IOException("DOCX 图片路径无效。", error); }
    }

    private static boolean safeLink(String href) {
        if (href == null || href.isEmpty() || !href.equals(href.trim()) || href.matches(".*[\\p{Cntrl}].*")) return false;
        String lower = href.toLowerCase(Locale.ROOT);
        return href.startsWith("#") || lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:");
    }

    private static String imageExtension(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return "png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "jpg";
        if (bytes.length >= 6 && new String(bytes, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a")) return "gif";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "webp";
        return null;
    }

    private static String decodeText(byte[] bytes) throws IOException {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191) offset = 3;
        else if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) { charset = StandardCharsets.UTF_16LE; offset = 2; }
        else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) { charset = StandardCharsets.UTF_16BE; offset = 2; }
        try { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString(); }
        catch (CharacterCodingException error) {
            try { return Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (Exception invalid) { throw new IOException("Markdown 编码无效，请保存为 UTF-8 后重试。", invalid); }
        }
    }

    private static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent) {
        if (parent == null) return Collections.emptyList();
        List<org.w3c.dom.Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) result.add((org.w3c.dom.Element) nodes.item(i));
        return result;
    }

    private static org.w3c.dom.Element child(org.w3c.dom.Element parent, String name) {
        if (parent == null) return null;
        for (org.w3c.dom.Element item : children(parent)) if (local(item).equals(name)) return item;
        return null;
    }

    private static org.w3c.dom.Element first(org.w3c.dom.Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? null : (org.w3c.dom.Element) nodes.item(0);
    }

    private static String local(org.w3c.dom.Element element) { return element.getLocalName() == null ? element.getTagName() : element.getLocalName(); }
    private static String attr(org.w3c.dom.Element element, String name) { return element == null ? "" : element.getAttributeNS(W, name); }
    private static String val(org.w3c.dom.Element element) { return attr(element, "val"); }
    private static boolean enabled(org.w3c.dom.Element element) { return element != null && !val(element).matches("(?i)0|false|off|none"); }
    private static String firstText(Document document, String name) { NodeList nodes = document.getElementsByTagNameNS("*", name); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent(); }
    private static int integer(String value, int fallback, int min, int max) { try { return Math.min(max, Math.max(min, Integer.parseInt(value))); } catch (NumberFormatException error) { return fallback; } }
    private static int headingName(String name) { String value = name.toLowerCase(Locale.ROOT).replace(" ", ""); if (value.matches("(?:heading|标题)[1-6]")) return value.charAt(value.length() - 1) - '0'; return value.equals("title") || value.equals("标题") ? 1 : 0; }
    private static String baseName(String filename) { int dot = filename.lastIndexOf('.'); String result = (dot > 0 ? filename.substring(0, dot) : filename).trim(); return result.isEmpty() ? "未命名文档" : result; }
    private static String extension(String filename) { return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static String hex(byte[] value) { StringBuilder output = new StringBuilder(); for (byte item : value) output.append(String.format(Locale.ROOT, "%02x", item & 255)); return output.toString(); }
    private static void cancelled(Runnable callback) { if (Thread.currentThread().isInterrupted()) throw new CancellationException("已取消导入。"); callback.run(); }
}
