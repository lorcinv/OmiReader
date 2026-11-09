const fs = require('node:fs');
const path = require('node:path');
// Prefer a project-installed renderer; the sibling OmiComic toolchain is a local fallback.
let Resvg;
try { ({ Resvg } = require('@resvg/resvg-js')); }
catch { ({ Resvg } = require('../../OmiComic/node_modules/@resvg/resvg-js')); }
const root = path.resolve(__dirname, '..');
const svg = fs.readFileSync(path.join(root, 'resources/omireader-icon.svg'), 'utf8');
const writePng = (file, size) => {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, new Resvg(svg, {fitTo: {mode: 'width', value: size}}).render().asPng());
};
writePng(path.join(root, 'resources/omireader-icon.png'), 1024);
for (const [density, size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
  writePng(path.join(root, `app/src/main/res/mipmap-${density}/ic_launcher.png`), size);
}
const attrs = s => Object.fromEntries([...s.matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1],m[2]]));
const paths = [...svg.matchAll(/<path\s+([^>]+)\/>/g)].map(m => attrs(m[1]));
const gradients = Object.fromEntries([...svg.matchAll(/<linearGradient\s+([^>]+)>(.*?)<\/linearGradient>/gs)].map(m => { const a=attrs(m[1]); return [a.id, {...a, stops:[...m[2].matchAll(/<stop\s+([^>]+)\/>/g)].map(s=>attrs(s[1]))}]; }));
// Raw paths intentionally omit the legacy SVG's group transform. Android expands a
// 108dp adaptive layer into its 72dp mask; 0.60 keeps every leaf inside the 66dp safe area.
const vector = (mono) => `<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="512" android:viewportHeight="512"><group android:scaleX="0.60" android:scaleY="0.60" android:pivotX="256" android:pivotY="256">${paths.filter(p=>!mono || !p.id.startsWith('eye')).map(p=>{
  if(mono) { const eye=paths.find(x=>x.id === (p.id==='innerLeft'?'eyeLeft':p.id==='innerRight'?'eyeRight':'')); return `<path android:pathData="${p.d}${eye?' '+eye.d:''}" android:fillType="evenOdd" android:fillColor="#15263F"/>`; }
  const id=p.fill.match(/url\(#(.*?)\)/)?.[1], g=gradients[id];
  const grad=g ? `<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="${g.x1}" android:startY="${g.y1}" android:endX="${g.x2}" android:endY="${g.y2}">${g.stops.map((s,i)=>`<item android:offset="${s.offset||0}" android:color="${s['stop-color']}"/>`).join('')}</gradient></aapt:attr>` : '';
  return `<path android:pathData="${p.d}" ${g?'':`android:fillColor="${p.fill}"`} android:fillAlpha="${p['fill-opacity']||1}" ${p.stroke?`android:strokeColor="${p.stroke}" android:strokeWidth="${p['stroke-width']}" android:strokeAlpha="${p['stroke-opacity']||1}"`:''}>${grad}</path>`;
}).join('')}</group></vector>`;
fs.writeFileSync(path.join(root, 'app/src/main/res/drawable/omi_launcher_foreground.xml'), vector(false));
fs.writeFileSync(path.join(root, 'app/src/main/res/drawable/omi_launcher_monochrome.xml'), vector(true));
fs.writeFileSync(path.join(root, 'app/src/main/res/drawable/omi_launcher_background.xml'), '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#15263F"/></shape>');
fs.writeFileSync(path.join(root, 'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml'), '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/omi_launcher_background"/><foreground android:drawable="@drawable/omi_launcher_foreground"/><monochrome android:drawable="@drawable/omi_launcher_monochrome"/></adaptive-icon>');
console.log('OmiReader vector, adaptive icon and PNGs generated.');
