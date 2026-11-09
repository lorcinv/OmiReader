"""Create an original twelve-page PDF fixture without external libraries."""
from pathlib import Path

objects = []
def add(data):
    objects.append(data.encode('ascii') if isinstance(data, str) else data)
    return len(objects)

add('<< /Type /Catalog /Pages 2 0 R >>')
add('')
add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
add('<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [5 0 R] >>')
add('<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >> /FontDescriptor 6 0 R /DW 1000 >>')
add('<< /Type /FontDescriptor /FontName /STSong-Light /Flags 4 /FontBBox [-25 -254 1000 880] /ItalicAngle 0 /Ascent 880 /Descent -120 /CapHeight 700 /StemV 80 >>')
pages = []
for i in range(12):
    title = f'PDF 阅读示例 · 第 {i+1} 页'.encode('utf-16-be').hex()
    lines = [
        '保持纸书原有的版面、插图与文字位置。',
        '这一页用于检查清晰度、中文和页面切换。',
        '长按页面可以放大，双指缩放后拖动查看细节。',
        '离开书页后，再次打开应回到已保存的位置。',
    ]
    commands = ['0.975 0.975 0.97 rg 0 0 595 842 re f',
                '0.29 0.40 0.61 rg 44 760 42 4 re f',
                'BT /F1 13 Tf 0.4 0.45 0.55 rg 44 785 Td (OmiReader / ORIGINAL PDF FIXTURE) Tj ET',
                f'BT /F2 24 Tf 0.15 0.20 0.29 rg 44 705 Td <{title}> Tj ET']
    for row, line in enumerate(lines):
        commands.append(f'BT /F2 17 Tf 0.15 0.20 0.29 rg 44 {650-row*38} Td <{line.encode("utf-16-be").hex()}> Tj ET')
    commands.extend(['0.29 0.40 0.61 rg 44 290 240 175 re f',
                     '0.90 0.87 0.84 rg 284 290 267 175 re f',
                     'BT /F1 16 Tf 1 1 1 rg 64 322 Td (MIST BLUE) Tj ET',
                     'BT /F1 16 Tf 0.25 0.21 0.18 rg 304 322 Td (WARM SAND) Tj ET',
                     '0.29 0.40 0.61 RG 0.5 w 44 260 m 551 260 l S',
                     f'BT /F1 12 Tf 0.4 0.45 0.55 rg 44 52 Td (Page {i+1} / 12) Tj ET'])
    stream = '\n'.join(commands).encode('ascii')
    content = add(b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream')
    pages.append(add(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {content} 0 R >>'))
objects[1] = f'<< /Type /Pages /Count {len(pages)} /Kids [{" ".join(f"{p} 0 R" for p in pages)}] >>'.encode()
pdf = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
offsets = [0]
for number, body in enumerate(objects, 1):
    offsets.append(len(pdf))
    pdf += f'{number} 0 obj\n'.encode() + body + b'\nendobj\n'
xref = len(pdf)
pdf += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
for offset in offsets[1:]: pdf += f'{offset:010d} 00000 n \n'.encode()
pdf += f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
destination = Path(__file__).resolve().parents[1] / 'tests/fixtures/Omi-Pages.pdf'
destination.write_bytes(pdf)
print(f'Created {len(pages)} PDF pages, {len(pdf)} bytes.')
