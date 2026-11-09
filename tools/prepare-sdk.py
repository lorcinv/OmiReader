"""Install the exact Android SDK archives recorded by Google's repository index."""
from pathlib import Path
import concurrent.futures
import hashlib
import shutil
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache'
SDK = CACHE / 'android-sdk'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({'https': 'http://127.0.0.1:7897'}))
index = ET.parse(CACHE / 'android-repository.xml').getroot()

def install(package):
    target = SDK.joinpath(*package.split(';'))
    if (target / 'source.properties').exists():
        return str(target)
    entry = next(p for p in index.findall('remotePackage') if p.get('path') == package)
    archive = next(a for a in entry.findall('archives/archive') if a.findtext('host-os') in (None, 'windows'))
    url = archive.findtext('complete/url')
    downloaded = CACHE / url
    checksum = archive.findtext('complete/checksum')
    if not downloaded.exists() or hashlib.sha1(downloaded.read_bytes()).hexdigest() != checksum:
        with opener.open('https://dl.google.com/android/repository/' + url, timeout=90) as response, downloaded.open('wb') as output:
            shutil.copyfileobj(response, output)
    if hashlib.sha1(downloaded.read_bytes()).hexdigest() != checksum:
        raise ValueError('SDK archive checksum mismatch: ' + url)
    with zipfile.ZipFile(downloaded) as z:
        names = z.namelist()
        prefix = names[0].split('/')[0] + '/'
        for info in z.infolist():
            relative = Path(info.filename.removeprefix(prefix))
            if '..' in relative.parts or relative.is_absolute():
                raise ValueError('Unsafe SDK entry')
            destination = target / relative
            if info.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, destination.open('wb') as out:
                    shutil.copyfileobj(src, out)
    return str(target)

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for installed in pool.map(install, ['platforms;android-36', 'build-tools;36.0.0']):
        print('Installed ' + installed, flush=True)
(ROOT / 'local.properties').write_text('sdk.dir=' + SDK.as_posix().replace(':', '\\:') + '\n', encoding='utf-8')
