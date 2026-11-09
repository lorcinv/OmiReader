"""Reuse OmiComic's SVG paths as Android vectors; keep the original geometry."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
source = root.parent / 'OmiComic/src/components/AppIcon.tsx'
paths = {a or b: d for a, b, d in re.findall(r"(?:'([^']+)'|(\w+)):\s*'([^']+)'", source.read_text(encoding='utf-8'))}
extra = {
    'contents': 'M12 7v14M3 4h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5v15h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3ZM6 8h3M6 12h3M15 8h3M15 12h3',
    'sun': 'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5',
    'moon': 'M20.5 14a8.8 8.8 0 1 1-10.5-10.5A7 7 0 0 0 20.5 14Z',
    'voice': 'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0ZM6 11v1a6 6 0 0 0 12 0v-1M12 18v4M8 22h8',
    'auto_page': 'M4 3h12v18H4ZM16 7h4v14h-4M8 8l5 4-5 4Z',
}
paths.update(extra)
target = root / 'app/src/main/res/drawable'
for name, path in paths.items():
    if name not in extra and name not in {'shuffle', 'temporary', 'menu', 'search', 'grid', 'list', 'close', 'back', 'next', 'chevron', 'rename', 'info', 'shelf', 'delete', 'select', 'pin', 'sort', 'add', 'move', 'remove', 'open-folder', 'cover', 'reset', 'refresh', 'settings', 'library', 'heart', 'bookmark', 'history'}:
        continue
    for active in (False, True):
        width = 2.5 if active else (1.8 if name == 'shelf' else 1.7)
        vector = f'''<!-- SVG geometry from OmiComic src/components/AppIcon.tsx. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
<path android:pathData="{path}" android:fillColor="@android:color/transparent" android:strokeColor="#000000" android:strokeWidth="{width}" android:strokeLineCap="round" android:strokeLineJoin="round"/>
</vector>
'''
        (target / f"omi_ic_{name.replace('-', '_')}{'_active' if active else ''}.xml").write_text(vector, encoding='utf-8')
for name, points in {'dots': [(5,12),(12,12),(19,12)], 'more': [(12,5),(12,12),(12,19)]}.items():
    d = ' '.join(f'M{x-1.5},{y}a1.5,1.5 0,1 0,3 0a1.5,1.5 0,1 0,-3 0' for x,y in points)
    (target / f'omi_ic_{name}.xml').write_text(f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:pathData="{d}" android:fillColor="#000000"/></vector>\n', encoding='utf-8')
print(f'Reused {len(paths) - len(extra)} OmiComic SVG paths and added {len(extra)} matching reader symbols.')
