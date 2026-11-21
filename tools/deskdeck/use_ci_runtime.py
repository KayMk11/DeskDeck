#!/usr/bin/env python3
"""Fill the runtime inputs tools/build_local.sh would build from an upstream CI APK of the merge base.

DeskDeck's changes are Kotlin, resources and helper scripts, so the prebuilt preloads, proot loader,
Syncthing and store libraries of the upstream build this branch forked from fit it unchanged. The
helper scripts from this checkout are copied over the donor's afterwards, so local edits win.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/release'))
from check_session_assets import source_assets  # noqa: E402

CATALOG = 'https://raw.githubusercontent.com/Droid-Deck/DroidDeck-CI/main/catalog.json'
UPSTREAM = 'com.droiddeck.launcher'
CACHE = Path.home() / '.cache/deskdeck-donor'
MAIN = ROOT / 'app/src/main'
PREPARED = ROOT / 'app/build/prepared-assets'
TREES = ('assets/linuxfs/', 'assets/droiddeck-esync/')
PREPARED_FILES = ('assets/pulseaudio.tzst', 'assets/venus/libvulkan_virtio.so', 'assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst',
                  'assets/graphics_driver/adrenotools-turnip26.3.0-a6xx.tzst',
                  'assets/graphics_driver/adrenotools-turnip26.3.0-quest2.tzst')
LIBRARIES = ('libproot-loader.so', 'libdirectaudiorelay.so', 'libsyncthingnative.so', 'libdroiddeckstores.so',
             'libepoxy.so', 'libvirgl_render_server.so', 'libvirgl_test_server.so', 'libvirglrenderer.so')


def merge_base():
    upstream = subprocess.run(['git', '-C', str(ROOT), 'merge-base', 'HEAD', 'origin/main'],
                              capture_output=True, text=True)
    if upstream.returncode == 0:
        return upstream.stdout.strip()
    raise SystemExit('Cannot find the merge base with origin/main; fetch origin first.')


def donor(commit):
    catalog = json.load(urllib.request.urlopen(CATALOG, timeout=60))
    for release in [catalog.get('preview')] + catalog.get('previews', []) + [catalog.get('stable')]:
        if release and release.get('commit') == commit:
            return release['apks'][UPSTREAM]
    raise SystemExit(f'No upstream CI build of {commit[:9]} in the catalog; rebase onto a built main commit.')


def fetch(apk):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / apk['name']
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != apk['sha256']:
        print('downloading', apk['url'])
        part = path.with_suffix('.part')
        with urllib.request.urlopen(apk['url'], timeout=600) as source, part.open('wb') as out:
            shutil.copyfileobj(source, out)
        if hashlib.sha256(part.read_bytes()).hexdigest() != apk['sha256']:
            part.unlink()
            raise SystemExit('Donor APK checksum mismatch')
        part.replace(path)
    return path


def extract(package, name, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    with package.open(name) as source, target.open('wb') as out:
        shutil.copyfileobj(source, out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', help='upstream commit to take the runtime from (default: merge base with origin/main)')
    args = parser.parse_args()
    commit = args.commit or merge_base()
    path = fetch(donor(commit))
    for tree in TREES:
        shutil.rmtree(MAIN / tree, ignore_errors=True)
    innoextract = ROOT / 'tools/linuxfs/innoextract'
    staged = {f.relative_to(innoextract).as_posix() for f in innoextract.rglob('*') if f.is_file()}
    with ZipFile(path) as package:
        names = package.namelist()
        for name in names:
            if name.startswith(TREES) and not name.endswith('/') and name.removeprefix('assets/') not in staged:
                extract(package, name, MAIN / name)
        for name in PREPARED_FILES:
            extract(package, name, PREPARED / name.removeprefix('assets/'))
        for library in LIBRARIES:
            extract(package, 'lib/arm64-v8a/' + library, MAIN / 'jniLibs/arm64-v8a' / library)
    for source, asset in source_assets().items():
        target = MAIN / asset
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    print(f'runtime inputs from upstream {commit[:9]} ({path.name}) are in place')


if __name__ == '__main__':
    main()
