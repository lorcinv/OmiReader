"""Generate small original DOCX/Markdown documents for the OmiReader import checks."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]

def png():
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)
    rows = bytearray()
    for y in range(72):
        rows.append(0)
        for x in range(144):
            rows.extend((75, 102, 155) if x < 72 else (230, 223, 213))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 144, 72, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')

def create_docx(path):
    document = '''<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>文档排版检查</w:t></w:r></w:p>
<w:p><w:r><w:t>这段文字来自 DOCX。中文、引号“书页”、符号 &amp; 都应正确显示。</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>这是粗体。</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>这是斜体。</w:t></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Subheading"/></w:pPr><w:r><w:t>列表与图片</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>第一条列表内容</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>嵌套的列表内容</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>有序列表内容</w:t></w:r></w:p>
<w:p><w:r><w:drawing><wp:inline><wp:docPr id="1" name="Omi色块" descr="雾蓝与暖沙色块"/><a:graphic><a:graphicData><a:blip r:embed="image1"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>表格与链接</w:t></w:r></w:p>
<w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>内容</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>书名</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>夜读随记</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>合并两列的说明</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:hyperlink r:id="link1"><w:r><w:t>打开示例链接</w:t></w:r></w:hyperlink></w:p>
</w:body></w:document>'''
    files = {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
        '_rels/.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        'word/document.xml': document,
        'word/_rels/document.xml.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="image1" Target="media/image1.png" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"/><Relationship Id="link1" Target="https://example.com/reading" TargetMode="External" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"/></Relationships>',
        'word/styles.xml': '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:styleId="Subheading"><w:name w:val="Custom subheading"/><w:basedOn w:val="Heading2"/></w:style></w:styles>',
        'word/numbering.xml': '<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl><w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:start w:val="3"/></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num></w:numbering>',
        'docProps/core.xml': '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Omi 文档检查 &amp; 阅读</dc:title><dc:creator>OmiReader</dc:creator></cp:coreProperties>',
        'word/media/image1.png': png(),
    }
    with ZipFile(path, 'w', ZIP_DEFLATED) as archive:
        for name, value in files.items():
            archive.writestr(name, value.encode('utf-8') if isinstance(value, str) else value)

markdown = '''# 夜读随记

一段 **粗体**、*斜体* 与 `行内代码`。

## 列表、引用与代码

- 读到这里
  - 记下一句话
- 再翻一页

1. 第一项
2. 第二项

> 文字需要留白。

```kotlin
val book = "夜读"
println(book)
```

## 表格与链接

| 项目 | 内容 |
| --- | --- |
| 书名 | 夜读随记 |
| 格式 | Markdown |

[示例链接](https://example.com/reading)

[不执行的链接](javascript:alert(1))

<script>alert("不会执行")</script>

![只显示说明的外部图片](https://example.com/remote.png)
'''

destinations = [ROOT/'tests/fixtures', ROOT/'app/src/test/resources/fixtures', ROOT/'app/src/androidTest/assets/omi-import']
for directory in destinations:
    directory.mkdir(parents=True, exist_ok=True)
    create_docx(directory/'document-rich.docx')
    (directory/'document-rich.md').write_text(markdown, encoding='utf-8')
print('Created DOCX and Markdown fixtures in test, JVM, and device-test folders.')
