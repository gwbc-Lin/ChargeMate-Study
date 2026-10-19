"""Copy a distributor's separately obtained runtimes; never fetch proprietary files."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

REQUIRED = {
    'live2d': ['pixi.min.js', 'cubism4.min.js', 'live2dcubismcore.min.js', 'cubism2.min.js', 'live2d.js'],
    'spine': ['pixi7.min.js', 'pixi-spine.js'],
}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kind', choices=REQUIRED, required=True)
parser.add_argument('--source', type=Path, required=True, help='Local folder containing vendor/ and licenses/')
parser.add_argument('--accept-license', action='store_true', help='Accept the applicable runtime license terms; this does not confirm publication approval')
args = parser.parse_args()
if not args.accept_license:
    parser.error('Accept applicable runtime terms first; see README. Release approval is a separate step.')
source = args.source.resolve()
for name in REQUIRED[args.kind]:
    if not (source/'vendor'/name).is_file():
        parser.error(f'Missing vendor/{name}')
if not (source/'licenses').is_dir() or not any((source/'licenses').iterdir()):
    parser.error('Provide upstream licenses/ and copyright notices')
target = Path(__file__).resolve().parents[1]/'app/optional'/args.kind/'assets/live2d'
target.mkdir(parents=True, exist_ok=True)
records = []
for name in REQUIRED[args.kind]:
    out = target/'vendor'/name
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source/'vendor'/name, out)
    records.append({'file': 'vendor/'+name, 'sha256': hashlib.sha256(out.read_bytes()).hexdigest()})
shutil.copytree(source/'licenses', target/'runtime-licenses'/args.kind, dirs_exist_ok=True)
(target/(args.kind+'-runtime-manifest.json')).write_text(json.dumps(records, indent=2), encoding='utf-8')
print(f'Prepared ignored local runtime: {args.kind}. Build flags do not grant publication rights.')
