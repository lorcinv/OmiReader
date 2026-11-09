package io.legado.app.model.localBook;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class DocumentImportConverterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File convert(byte[] bytes, String name, File output) throws Exception {
        return DocumentImportConverter.convert(new ByteArrayInputStream(bytes), name, output, () -> { });
    }
    private byte[] resource(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    private String entry(File file, String name) throws Exception {
        try (ZipFile zip = new ZipFile(file)) { return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8); }
    }
    private byte[] docx(String document, String extraPath) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(result)) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(document.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            if (extraPath != null) { zip.putNextEntry(new ZipEntry(extraPath)); zip.write(1); zip.closeEntry(); }
        }
        return result.toByteArray();
    }

    @Test public void markdownKeepsStructureAndContainsNoExecutableOrRemoteContent() throws Exception {
        File epub = convert(resource("document-rich.md"), "中文书名.markdown", temporary.newFolder());
        Document document = Jsoup.parse(entry(epub, "OEBPS/content.xhtml"));
        assertEquals("夜读随记", document.title());
        assertEquals(3, document.select("h1,h2").size());
        assertEquals(1, document.select("blockquote").size());
        assertEquals(1, document.select("pre code").size());
        assertTrue(document.select("ul ul").size() > 0);
        assertTrue(document.select("ol li").size() >= 2);
        assertEquals(1, document.select("table").size());
        assertEquals(2, document.select("table tbody tr").size());
        assertEquals(1, document.select("a[href=https://example.com/reading]").size());
        assertTrue(document.select("script,iframe,object,img").isEmpty());
        assertFalse(document.html().contains("href=\"javascript:"));
        assertTrue(document.text().contains("不会执行"));
        assertTrue(document.text().contains("只显示说明的外部图片"));
        RichDocumentArchive rich = RichDocumentArchive.open(epub, "fallback", () -> { });
        assertEquals(3, rich.chapters.size());
        assertEquals("表格与链接", rich.chapters.get(2).title);
        assertTrue(rich.chapters.get(1).html.contains("<pre><code>"));
        assertTrue(rich.chapters.get(2).html.contains("<table>"));
    }

    @Test public void docxKeepsMetadataHeadingsListsTableImageAndLink() throws Exception {
        File epub = convert(resource("document-rich.docx"), "资料 & 排版.docx", temporary.newFolder());
        Document document = Jsoup.parse(entry(epub, "OEBPS/content.xhtml"));
        assertEquals("Omi 文档检查 & 阅读", document.title());
        assertEquals(3, document.select("h1,h2").size());
        assertEquals(1, document.select("strong").size());
        assertEquals(1, document.select("em").size());
        assertTrue(document.select("ul ul").size() > 0);
        assertEquals("3", document.selectFirst("ol").attr("start"));
        assertEquals(1, document.select("table").size());
        assertEquals(2, document.select("th").size());
        assertEquals("2", document.selectFirst("td[colspan]").attr("colspan"));
        assertEquals("雾蓝与暖沙色块", document.selectFirst("img").attr("alt"));
        assertEquals("https://example.com/reading", document.selectFirst("a").attr("href"));
        RichDocumentArchive rich = RichDocumentArchive.open(epub, "fallback", () -> { });
        assertEquals(3, rich.chapters.size());
        assertTrue(rich.chapters.get(1).html.contains("https://omi-reader.invalid/OEBPS/images/image-1.png"));
        assertTrue(rich.readAsset("OEBPS/images/image-1.png").length > 100);
        assertThrows(java.io.IOException.class, () -> rich.readAsset("../private.txt"));
        try (ZipFile zip = new ZipFile(epub)) {
            ZipEntry first = zip.entries().nextElement();
            assertEquals("mimetype", first.getName()); assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals("application/epub+zip", entry(epub, "mimetype"));
        }
        assertTrue(entry(epub, "OEBPS/package.opf").contains("Omi 文档检查 &amp; 阅读"));
        assertTrue(entry(epub, "OEBPS/package.opf").contains("content=\"docx\""));
    }

    @Test public void preservesPreambleAndHasStableContentIdentity() throws Exception {
        byte[] markdown = "卷首说明\n\n# 第一章\n\n正文\n\n## 第二章\n\n后记".getBytes(StandardCharsets.UTF_8);
        File output = temporary.newFolder();
        File first = convert(markdown, "记录.md", output);
        File second = convert(markdown, "记录.md", output);
        assertEquals(first, second);
        assertEquals(1, output.listFiles().length);
        RichDocumentArchive rich = RichDocumentArchive.open(first, "记录", () -> { });
        assertEquals(3, rich.chapters.size());
        assertTrue(rich.chapters.get(0).text.contains("卷首说明"));
        assertTrue(entry(first, "OEBPS/toc.ncx").contains("content.xhtml#omi-start"));
    }

    @Test public void utf16MarkdownAndChineseFilenameArePreserved() throws Exception {
        byte[] body = "# 雪夜\n\n正文。".getBytes(StandardCharsets.UTF_16LE);
        byte[] input = new byte[body.length + 2]; input[0] = (byte) 255; input[1] = (byte) 254;
        System.arraycopy(body, 0, input, 2, body.length);
        File epub = convert(input, "冬日 随笔.MD", temporary.newFolder());
        assertTrue(entry(epub, "OEBPS/content.xhtml").contains("雪夜"));
        assertTrue(entry(epub, "OEBPS/package.opf").contains("冬日 随笔.MD"));
    }

    @Test public void rejectsTraversalEntriesWithoutWritingOutput() throws Exception {
        File output = temporary.newFolder();
        byte[] bytes = docx("<document/>", "../../escape.txt");
        assertThrows(java.io.IOException.class, () -> convert(bytes, "bad.docx", output));
        assertEquals(0, output.listFiles().length);
        assertFalse(new File(output.getParentFile(), "escape.txt").exists());
    }

    @Test public void rejectsDoctypeAndExternalEntityInUtf8AndUtf16() throws Exception {
        String xml = "<!DOCTYPE document [<!ENTITY secret SYSTEM 'file:///not-readable'>]><document>&secret;</document>";
        File output = temporary.newFolder();
        assertThrows(java.io.IOException.class, () -> convert(docx(xml, null), "entity.docx", output));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + xml).getBytes(StandardCharsets.UTF_16));
            zip.closeEntry();
        }
        assertThrows(java.io.IOException.class, () -> convert(bytes.toByteArray(), "entity16.docx", output));
        assertEquals(0, output.listFiles().length);
    }

    @Test public void rejectsMalformedAndOversizedInputsAndRemovesTemporaryFiles() throws Exception {
        File output = temporary.newFolder();
        assertThrows(java.io.IOException.class, () -> convert(docx("<w:document>", null), "broken.docx", output));
        assertThrows(java.io.IOException.class, () -> convert(new byte[DocumentImportConverter.MAX_MARKDOWN + 1], "large.md", output));
        assertEquals(0, output.listFiles().length);
    }

    @Test public void cancellationLeavesNoPartialBook() throws Exception {
        File output = temporary.newFolder();
        AtomicInteger steps = new AtomicInteger();
        byte[] document = resource("document-rich.docx");
        assertThrows(CancellationException.class, () -> DocumentImportConverter.convert(new ByteArrayInputStream(document), "cancel.docx", output,
                () -> { if (steps.incrementAndGet() >= 8) throw new CancellationException(); }));
        assertEquals(0, output.listFiles().length);
    }
}
