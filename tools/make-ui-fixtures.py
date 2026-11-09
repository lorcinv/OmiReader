"""Generate small original, deterministic books for device reading checks."""
from pathlib import Path
import zipfile
from html import escape

root = Path(__file__).resolve().parents[1] / 'tests' / 'fixtures'
root.mkdir(parents=True, exist_ok=True)
paragraphs = [
    '把阅读的时间留给文字。打开一本书，沿着上一页继续读下去，书签、目录和进度都保存在这台设备里。',
    '这一页用于检查中文标点、段落缩进和自动换行。调整字号之后，原来的文字位置应当继续保留；切换主题，只改变页面和文字的颜色。',
    '目录记录章节的位置。轻点页面中间可以打开阅读工具，滑动或点击两侧可以翻页。行距、段距和页边距各自调节，适应手机与平板的阅读尺寸。',
    '从书架重新打开时，应用应回到上次阅读的位置。每一本书的阅读记录彼此独立，切换书籍不会覆盖另一册的进度。',
]
chapters = ['第一章　阅读，从这一页开始', '第二章　合适的留白', '第三章　再次打开']
text = '\n\n'.join(title + '\n\n' + '\n\n'.join(paragraphs * 8) for title in chapters)
(root / 'Omi阅读示例.txt').write_text(text, encoding='utf-8')
(root / 'Omi阅读示例-GB18030.txt').write_bytes(text.encode('gb18030'))
with zipfile.ZipFile(root / 'Omi阅读示例.epub', 'w') as z:
    z.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
    z.writestr('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    manifest = '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
    spine = ''
    nav = ''
    for i, title in enumerate(chapters):
        manifest += f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>'
        spine += f'<itemref idref="c{i}"/>'
        nav += f'<navPoint id="c{i}" playOrder="{i+1}"><navLabel><text>{title}</text></navLabel><content src="c{i}.xhtml"/></navPoint>'
        body = '<h1>' + title + '</h1>' + ''.join('<p>' + escape(p) + '</p>' for p in paragraphs * 8)
        z.writestr(f'OEBPS/c{i}.xhtml', '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>' + title + '</title></head><body>' + body + '</body></html>')
    z.writestr('OEBPS/content.opf', '<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">omireader-ui-fixture-v1</dc:identifier><dc:title>阅读，从这一页开始</dc:title><dc:creator>OmiReader</dc:creator><dc:language>zh</dc:language></metadata><manifest>' + manifest + '</manifest><spine toc="ncx">' + spine + '</spine></package>')
    z.writestr('OEBPS/toc.ncx', '<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="omireader-ui-fixture-v1"/></head><docTitle><text>阅读，从这一页开始</text></docTitle><navMap>' + nav + '</navMap></ncx>')
(root / '阅读笔记.md').write_text('''# 阅读笔记

一本书的正文之外，也可以留下一份排版清晰的笔记。

## 先留下重点

- 标题帮助整理结构。
- **粗体**标出重点，*斜体*保留强调。
- 列表中的中文和英文可以混排。

> 阅读进度按书保存，下一次从这里继续。

## 一段代码

```python
def read_next(book):
    return book.next_page()
```

| 格式 | 用途 |
| --- | --- |
| EPUB | 可调整字号的书籍 |
| Markdown | 带结构的阅读笔记 |

[OmiReader 开源底座](https://github.com/gedoor/legado)

## 长文阅读

''' + '\n\n'.join(paragraphs * 15), encoding='utf-8')
print('Created Chinese TXT, GB18030 TXT, EPUB and Markdown fixtures.')
