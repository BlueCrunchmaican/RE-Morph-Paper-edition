"""Builds release/RE-Morph-<version>/ and its zip from the already-built jars.

Usage: py -3 -I tools/package_release.py
Build both jars first (gradlew build in paper-plugin and fabric-client). Third-party plugins (Lib's Disguises,
PacketEvents) are not bundled, see NOTICE.md; the guide links to their downloads.
"""
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION = '6.1.7'
OUT = ROOT / 'release' / f'RE-Morph-{VERSION}'

SERVER_JAR = ROOT / 'paper-plugin/build/libs' / f'RE-Morph-paper-{VERSION}.jar'
CLIENT_JAR = ROOT / 'fabric-client/build/libs' / f'RE-Morph-fabric-{VERSION}+26.3.jar'


def zip_dir(src, dest):
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(src.rglob('*')):
            if f.is_file():
                z.write(f, f.relative_to(src).as_posix())


for jar in (SERVER_JAR, CLIENT_JAR):
    if not jar.exists():
        raise SystemExit(f'missing {jar}; build it first')

if OUT.exists():
    shutil.rmtree(OUT)
for d in ('server', 'client', 'resourcepack', 'default-configs', 'source'):
    (OUT / d).mkdir(parents=True)

shutil.copy2(SERVER_JAR, OUT / 'server')
shutil.copy2(CLIENT_JAR, OUT / 'client')
zip_dir(ROOT / 'resourcepack', OUT / 'resourcepack/RE-Morph-sounds.zip')
for name in ('config.yml', 'abilities.yml', 'variants.yml', 'survival.yml'):
    shutil.copy2(ROOT / 'paper-plugin/src/main/resources' / name, OUT / 'default-configs')
shutil.copy2(ROOT / 'docs/GUIDE.md', OUT / 'README.md')
for name in ('ABILITIES.md', 'TESTING.md', 'SURVIVAL-MODE.md'):
    shutil.copy2(ROOT / 'docs' / name, OUT)
for name in ('LICENSE', 'LICENSE-GPL-3.0', 'NOTICE.md'):
    shutil.copy2(ROOT / name, OUT)
# Source of the committed tree, as the LGPL asks.
subprocess.run(['git', 'archive', '--format=zip', f'--prefix=RE-Morph-{VERSION}-source/',
                '-o', str(OUT / 'source' / f'RE-Morph-{VERSION}-source.zip'), 'HEAD'], cwd=ROOT, check=True)

archive = OUT.parent / f'RE-Morph-{VERSION}.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in sorted(OUT.rglob('*')):
        if f.is_file():
            z.write(f, (Path(OUT.name) / f.relative_to(OUT)).as_posix())
print('wrote', OUT, 'and', archive)
