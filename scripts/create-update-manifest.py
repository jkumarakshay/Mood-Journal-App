#!/usr/bin/env python3
"""Create the public release sidecar from a signed APK's actual metadata and bytes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, help='Defaults to daily-pause-update.json next to the APK')
    parser.add_argument('--build-tools', type=Path, help='Android SDK build-tools directory')
    args = parser.parse_args()
    apk = args.apk.resolve()
    tools = args.build_tools
    if tools is None:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if not sdk:
            parser.error('Set ANDROID_HOME or provide --build-tools.')
        candidates = [p for p in (Path(sdk) / 'build-tools').iterdir() if re.fullmatch(r'\d+\.\d+\.\d+', p.name)]
        if not candidates:
            parser.error('No Android build tools found.')
        tools = max(candidates, key=lambda p: tuple(map(int, p.name.split('.'))))
    suffix = '.bat' if os.name == 'nt' else ''
    subprocess.run([str(tools / ('apksigner' + suffix)), 'verify', str(apk)], check=True)
    aapt = tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    metadata = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata, re.MULTILINE)
    minimum = re.search(r"^sdkVersion:'(\d+)'", metadata, re.MULTILINE)
    if not package or not minimum or package[1] != 'com.moodjournal.app':
        parser.error('APK is not Daily Pause or its version/minimum SDK could not be read.')
    if not re.fullmatch(r'\d{1,5}\.\d{1,5}\.\d{1,5}', package[3]):
        parser.error('Use a stable major.minor.patch versionName, for example 1.10.0.')
    release_code = int(package[2])
    expected_version = f'1.{release_code}.0'
    if not 1 <= release_code <= 99999 or package[3] != expected_version:
        parser.error(f'versionCode {release_code} must use versionName {expected_version}. Rebuild from version.properties.')
    expected_name = f'Daily-Pause-v{expected_version}.apk'
    if apk.name != expected_name:
        parser.error(f'APK filename must be {expected_name} to match its installed version.')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,159}\.apk', apk.name):
        parser.error('APK filename must use letters, digits, dots, underscores, or hyphens.')
    size = apk.stat().st_size
    if not 0 < size <= 150 * 1024 * 1024 or not 0 < int(package[2]) <= 2147483647:
        parser.error('APK size or versionCode is outside the updater limits.')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    value = dict(schemaVersion=1, packageName=package[1], versionCode=int(package[2]),
                 versionName=package[3], minSdk=int(minimum[1]), apkAssetName=apk.name,
                 size=size, sha256=digest.hexdigest())
    output = args.output or apk.parent / 'daily-pause-update.json'
    output.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    print(f'Created {output}: version {package[3]}, versionCode {package[2]}')
    print(f'GitHub tag: v{expected_version}; release title: Daily Pause {expected_version}')
    print('Upload this manifest and the unchanged APK to the same stable GitHub Release.')


if __name__ == '__main__':
    main()
