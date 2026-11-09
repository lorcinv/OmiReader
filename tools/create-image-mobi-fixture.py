"""Original, uncompressed KF6 fixture with an embedded full-bleed test image."""
from pathlib import Path
from struct import pack_into, pack
import io
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1]
image = Image.new('RGB', (1080, 1600), '#4b669b')
draw = ImageDraw.Draw(image)
draw.rectangle((0, 0, 1079, 1599), outline='#e6dfd5', width=18)
draw.rectangle((130, 200, 950, 1400), fill='#9db0ce')
draw.rectangle((500, 420, 580, 1180), fill='#263249')
png = io.BytesIO()
image.save(png, format='PNG')
title = b'Omi Image Fixture'
html = b'<html><head><title>Image page</title></head><body><img recindex="1"/><mbp:pagebreak/></body></html>'
header = bytearray(248 + len(title))
pack_into('>H', header, 0, 1)
pack_into('>I', header, 4, len(html))
pack_into('>HH', header, 8, 1, 4096)
header[16:20] = b'MOBI'
for offset, value in {20:232,24:2,28:65001,32:110,36:6,80:2,84:248,88:len(title),108:2,244:0xffffffff}.items():
    pack_into('>I', header, offset, value)
header[248:] = title
records = [bytes(header), html, png.getvalue()]
pdb = bytearray(78 + len(records) * 8 + 2)
pdb[:len(title)] = title
pdb[60:68] = b'BOOKMOBI'
pack_into('>H', pdb, 76, len(records))
offset = len(pdb)
for index, record in enumerate(records):
    pack_into('>II', pdb, 78 + index * 8, offset, index * 2)
    offset += len(record)
result = bytes(pdb) + b''.join(records)
for relative in ['app/src/androidTest/assets/local-formats/omi-image.mobi', 'tests/fixtures/omi-image.mobi']:
    (root / relative).write_bytes(result)
print(f'Created original image MOBI fixture: {len(result)} bytes')
