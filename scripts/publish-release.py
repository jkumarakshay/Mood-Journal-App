#!/usr/bin/env python3
"""Publish the matching APK/manifest pair using the APK's version for the GitHub tag."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

REPOSITORY = 'jkumarakshay/Mood-Journal-App'


def release_metadata(manifest, apk):
    code = manifest.get('versionCode')
    if type(code) is not int or not 1 <= code <= 99999:
        raise ValueError('Invalid versionCode')
    version = f'1.{code}.0'
    if manifest.get('schemaVersion') != 1 or manifest.get('packageName') != 'com.moodjournal.app':
        raise ValueError('Unsupported manifest or package')
    if manifest.get('versionName') != version:
        raise ValueError('App version does not match versionCode')
    if manifest.get('apkAssetName') != f'Daily-Pause-v{version}.apk' or apk.name != manifest['apkAssetName']:
        raise ValueError('APK filename does not match versionCode')
    if apk.stat().st_size != manifest.get('size') or hashlib.sha256(apk.read_bytes()).hexdigest() != manifest.get('sha256'):
        raise ValueError('APK bytes do not match the update manifest')
    return {'code': code, 'version': version, 'tag': f'v{version}', 'title': f'Daily Pause {version}'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--notes-file', type=Path, required=True)
    parser.add_argument('--dry-run', action='store_true', help='Validate and print the plan without GitHub writes')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    apk = args.apk.resolve()
    manifest_path = apk.parent / 'daily-pause-update.json'
    try:
        metadata = release_metadata(json.loads(manifest_path.read_text()), apk)
        args.notes_file.read_text(encoding='utf-8')
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))
    properties = dict(line.split('=', 1) for line in (root / 'version.properties').read_text().splitlines()
                      if line and not line.startswith('#'))
    if properties.get('releaseCode', '').strip() != str(metadata['code']):
        parser.error('APK release number does not match version.properties')
    target = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    plan = {**metadata, 'repository': REPOSITORY, 'target': target,
            'assets': [str(apk), str(manifest_path)], 'draft': False, 'prerelease': False, 'latest': True}
    print(json.dumps(plan, indent=2), flush=True)
    if args.dry_run:
        return
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root):
        parser.error('Commit the release source before publishing')
    remote = json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPOSITORY}/git/ref/heads/main'], text=True))
    if remote['object']['sha'] != target:
        parser.error('Push this exact release commit to main before publishing')
    # This is a dry-run until the first gh mutation below. A failed upload leaves a draft.
    common = ['--repo', REPOSITORY]
    subprocess.run(['gh', 'release', 'create', metadata['tag'], *common, '--target', target,
                    '--title', metadata['title'], '--notes-file', str(args.notes_file.resolve()), '--draft'], check=True)
    subprocess.run(['gh', 'release', 'upload', metadata['tag'], *common, str(apk), str(manifest_path)], check=True)
    subprocess.run(['gh', 'release', 'edit', metadata['tag'], *common, '--draft=false', '--latest'], check=True)


if __name__ == '__main__':
    main()
