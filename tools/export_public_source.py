"""Export the reviewed working tree without Git history, caches, local assets or secrets."""
from pathlib import Path
import re
import subprocess
import zipfile
from check_public_release import ROOT, check_source

check_source()
names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode('utf-8').split('\0')
excluded = ('.git/', '.gradle/', '.idea/', '.kotlin/', 'app/build/', 'build/', 'tools/cache/',
            'app/optional/', 'shots/', 'dist/', 'asset/imuncle_live2d/')
files = []
secret = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}')
for name in sorted(set(names)):
    if not name or name.startswith(excluded):
        continue
    p = ROOT/name
    if not p.is_file():  # tracked files removed from this working tree
        continue
    if p.is_symlink() or not p.resolve().is_relative_to(ROOT):
        raise ValueError('Export does not follow symlinks: '+name)
    if p.name == 'local.properties' or p.name.startswith('.env') or p.suffix.lower() in {'.jks', '.keystore', '.p12', '.apk'}:
        raise ValueError('Local secret/build artifact in export: '+name)
    if p.suffix.lower() in {'.kt', '.kts', '.py', '.ps1', '.md', '.txt', '.xml', '.json', '.properties'}:
        if secret.search(p.read_text(encoding='utf-8', errors='replace')):
            raise ValueError('Credential pattern found in '+name)
    files.append((p, name))
out = ROOT/'dist/ChargeMate-public-source.zip'
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for p, name in files:
        archive.write(p, 'ChargeMate/'+name)
print(f'Exported {len(files)} files without Git history: {out}')
