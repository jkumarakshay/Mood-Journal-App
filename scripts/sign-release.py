#!/usr/bin/env python3
"""Sign an unsigned Daily Pause release APK using explicitly supplied private key files."""
import argparse
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--keystore', required=True, type=Path)
    parser.add_argument('--password-file', required=True, type=Path)
    parser.add_argument('--alias', default='daily-pause')
    parser.add_argument('--lineage', type=Path)
    parser.add_argument('--legacy-keystore', type=Path)
    parser.add_argument('--legacy-password-file', type=Path)
    parser.add_argument('--legacy-alias', default='androiddebugkey')
    parser.add_argument('--build-tools', type=Path)
    args = parser.parse_args()
    if any((args.lineage, args.legacy_keystore, args.legacy_password_file)) and not all(
            (args.lineage, args.legacy_keystore, args.legacy_password_file)):
        parser.error('Rotation requires --lineage, --legacy-keystore and --legacy-password-file together.')
    for path in (args.apk, args.keystore, args.password_file, args.lineage,
                 args.legacy_keystore, args.legacy_password_file):
        if path is not None and not path.is_file():
            parser.error(f'Required file is missing: {path}')
    tools = args.build_tools
    if tools is None:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if not sdk:
            parser.error('Set ANDROID_HOME or supply --build-tools.')
        candidates = [p for p in (Path(sdk) / 'build-tools').iterdir()
                      if re.fullmatch(r'\d+\.\d+\.\d+', p.name)]
        if not candidates:
            parser.error('Android build tools were not found.')
        tools = max(candidates, key=lambda p: tuple(map(int, p.name.split('.'))))
    suffix = '.bat' if os.name == 'nt' else ''
    signer = str(tools / ('apksigner' + suffix))
    aapt = str(tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2'))
    metadata = subprocess.check_output([aapt, 'dump', 'badging', str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata, re.M)
    if not package or package[1] != 'com.moodjournal.app' or package[3] != f'1.{int(package[2])}.0':
        parser.error('Input must be a Daily Pause APK using the releaseCode version scheme.')
    if 'application-debuggable' in metadata:
        parser.error('Input is debuggable. Build the release variant first.')
    manifest = subprocess.check_output([aapt, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(args.apk)], text=True)
    if re.search(r'android:testOnly[^\n]*0xffffffff', manifest):
        parser.error('Input is marked testOnly.')
    verified = subprocess.run([signer, 'verify', str(args.apk)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if verified.returncode == 0:
        parser.error('Input is already signed. Use the unsigned output of assembleRelease.')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f'Daily-Pause-v{package[3]}.apk'
    if output.resolve() == args.apk.resolve() or output.exists():
        parser.error('Output must be a new file, distinct from the unsigned input.')
    aligned = args.output_dir / (output.name + '.aligned.tmp')
    if aligned.exists():
        parser.error('An alignment temporary file already exists in the output directory.')
    try:
        subprocess.run([str(tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign')),
                        '-f', '4', str(args.apk), str(aligned)], check=True)
        command = [signer, 'sign', '--out', str(output)]

        def key_options(keystore, password_file, alias):
            return ['--ks', str(keystore), '--ks-key-alias', alias,
                    '--ks-pass', 'file:' + str(password_file.resolve())]

        if args.lineage:
            command += ['--lineage', str(args.lineage), '--rotation-min-sdk-version', '33']
            command += key_options(args.legacy_keystore, args.legacy_password_file, args.legacy_alias)
            command += ['--next-signer']
        command += key_options(args.keystore, args.password_file, args.alias)
        command += [str(aligned)]
        subprocess.run(command, check=True)
        subprocess.run([signer, 'verify', '--verbose', '--print-certs', str(output)], check=True)
    except BaseException:
        output.unlink(missing_ok=True)
        raise
    finally:
        aligned.unlink(missing_ok=True)
    print(f'Signed {output}. Private key files were not copied into the APK or output directory.')


if __name__ == '__main__':
    main()
