"""Check the current public asset tree and (optionally) an APK. Not a legal opinion."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'app/src/main/assets/live2d'
ALLOWED_DIRS = {'ChargeBot', 'ChargeCat', 'licenses', 'vendor'}
PUBLIC_RUNTIME = {'pixi7.min.js', 'pixi-spine.js'}

def check_assets(names, allow_live2d_development=False):
    for name in names:
        parts = Path(name).parts
        if len(parts) > 1 and parts[0] not in ALLOWED_DIRS:
            if not allow_live2d_development or parts[0] != 'runtime-licenses':
                raise ValueError(f'Unexpected public asset directory: {parts[0]}')
        if parts[0] == 'vendor' and Path(name).name not in PUBLIC_RUNTIME and not allow_live2d_development:
            raise ValueError(f'Live2D runtime requires separate publication review: {name}')
        if Path(name).suffix.lower() in {'.skel', '.atlas', '.moc', '.moc3', '.zip'}:
            raise ValueError(f'Model binary in public build: {name}')

def check_source():
    check_assets([p.relative_to(ASSETS).as_posix() for p in ASSETS.rglob('*') if p.is_file()])
    spine = ROOT/'app/src/spine/assets/live2d'
    check_assets([p.relative_to(spine).as_posix() for p in spine.rglob('*') if p.is_file()])
    for p in ASSETS.glob('*/character.svg'):
        text = p.read_text(encoding='utf-8')
        if re.search(r'<script|\bon\w+\s*=|(?:href|src)\s*=', text, re.I):
            raise ValueError(f'Active or externally referenced SVG: {p.name}')
    for name in ['Spine-LICENSE.txt', 'Spine-Runtimes-2025-LICENSE.txt', 'pixi-spine-LICENSE.txt', 'PixiJS7-LICENSE.txt']:
        if not (ASSETS/'licenses'/name).is_file():
            raise ValueError('Missing full runtime license: '+name)
    manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
    ns = '{http://schemas.android.com/apk/res/android}'
    forbidden = {'INTERNET', 'WRITE_SETTINGS', 'WRITE_SECURE_SETTINGS', 'MANAGE_EXTERNAL_STORAGE',
                 'REQUEST_INSTALL_PACKAGES', 'QUERY_ALL_PACKAGES', 'DISABLE_KEYGUARD'}
    permissions = {p.get(ns+'name', '').rsplit('.', 1)[-1] for p in manifest.findall('uses-permission')}
    if permissions & forbidden:
        raise ValueError('Unexpected sensitive permissions: '+str(permissions & forbidden))
    for p in (ROOT/'app/src/main/java').rglob('*.kt'):
        text = p.read_text(encoding='utf-8-sig')
        if re.search(r'Runtime\.getRuntime\(\)\.exec|ProcessBuilder\(|Settings\.(?:Global|Secure|System)\.put', text):
            raise ValueError(f'Review system mutation in {p.name}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--allow-live2d-development', action='store_true', help='Development audit only; not a publication clearance')
    args = parser.parse_args()
    check_source()
    if args.apk:
        with zipfile.ZipFile(args.apk) as archive:
            check_assets([name.removeprefix('assets/live2d/') for name in archive.namelist()
                          if name.startswith('assets/live2d/') and not name.endswith('/')], args.allow_live2d_development)
        print(f'APK checked: {args.apk.name} ({args.apk.stat().st_size/1024**2:.1f} MiB)')
        if args.allow_live2d_development:
            print('DEVELOPMENT ONLY: this check does not clear Live2D publication rights.')
    print('Public asset / permission / system-write checks passed. Git history is a separate release concern.')
