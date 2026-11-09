package io.legado.app.model.localBook;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads only the fixed layout emitted by DocumentImportConverter, never arbitrary EPUB URLs. */
public final class RichDocumentArchive {
    public static final class Chapter {
        public final String title;
        public final String html;
        public final String text;
        public final String anchor;
        Chapter(String title, String html, String text, String anchor) {
            this.title = title; this.html = html; this.text = text; this.anchor = anchor;
        }
    }
    public final List<Chapter> chapters;
    public final Map<String, Integer> anchors;
    private final File file;
    private final Set<String> assets;

    private RichDocumentArchive(File file, List<Chapter> chapters, Map<String, Integer> anchors, Set<String> assets) {
        this.file = file;
        this.chapters = Collections.unmodifiableList(chapters);
        this.anchors = Collections.unmodifiableMap(anchors);
        this.assets = assets;
    }

    public static RichDocumentArchive open(File file, String title, Runnable cancel) throws IOException {
        List<Chapter> chapters = new ArrayList<>();
        Map<String, Integer> anchors = new HashMap<>();
        Set<String> assets = new HashSet<>();
        try (ZipFile zip = new ZipFile(file)) {
            DocumentImportConverter.validateArchive(zip, cancel);
            String content = new String(DocumentImportConverter.readEntry(zip, "OEBPS/content.xhtml", 32 * 1024 * 1024, cancel), StandardCharsets.UTF_8);
            Safelist safe = new Safelist().addTags("p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "br", "hr",
                            "strong", "b", "em", "i", "u", "s", "del", "sup", "sub", "blockquote", "pre", "code", "ul", "ol", "li",
                            "a", "img", "table", "thead", "tbody", "tr", "th", "td")
                    .addAttributes(":all", "id").addAttributes("a", "href", "title").addProtocols("a", "href", "http", "https", "mailto", "#")
                    .addAttributes("img", "src", "alt").addAttributes("ol", "start").addAttributes("td", "colspan", "rowspan")
                    .addAttributes("th", "colspan", "rowspan").preserveRelativeLinks(true);
            org.jsoup.nodes.Document document = new Cleaner(safe).clean(Jsoup.parse(content));
            document.outputSettings().prettyPrint(false);
            for (Element image : document.select("img")) {
                String src = image.attr("src");
                if (!src.matches("images/image-[0-9]+\\.(png|jpg|gif|webp)")) { image.remove(); continue; }
                ZipEntry entry = zip.getEntry("OEBPS/" + src);
                if (entry == null || entry.getSize() > DocumentImportConverter.MAX_IMAGE) { image.remove(); continue; }
                assets.add("OEBPS/" + src);
                image.attr("src", "https://omi-reader.invalid/OEBPS/" + src);
            }
            Element section = new Element("div");
            String sectionTitle = title;
            String anchor = "omi-start";
            for (Node node : new ArrayList<>(document.body().childNodes())) {
                cancel.run();
                boolean heading = node instanceof Element && ((Element) node).tagName().matches("h[1-6]");
                if (heading) {
                    if (hasContent(section)) chapters.add(new Chapter(sectionTitle, section.html(), section.text(), anchor));
                    section = new Element("div");
                    sectionTitle = ((Element) node).text();
                    anchor = ((Element) node).id();
                }
                section.appendChild(node.clone());
            }
            if (hasContent(section)) chapters.add(new Chapter(sectionTitle, section.html(), section.text(), anchor));
            if (chapters.isEmpty()) throw new IOException("文档没有可阅读的内容。");
            for (int i = 0; i < chapters.size(); i++) {
                for (Element element : Jsoup.parseBodyFragment(chapters.get(i).html).select("[id]")) anchors.put(element.id(), i);
            }
        }
        return new RichDocumentArchive(file, chapters, anchors, assets);
    }

    private static boolean hasContent(Element section) { return !section.text().trim().isEmpty() || !section.select("img,hr,table,pre").isEmpty(); }

    public byte[] readAsset(String path) throws IOException {
        if (!assets.contains(path)) throw new IOException("文档资源不存在。");
        try (ZipFile zip = new ZipFile(file)) { return DocumentImportConverter.readEntry(zip, path, DocumentImportConverter.MAX_IMAGE, () -> { }); }
    }
}
