#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Mood-Journal-App
mkdir -p /workspace/toolchains/downloads
downloads=/workspace/toolchains/downloads

fetch_verified() {
    local url="$1" path="$2" hash="$3" algorithm="$4"
    if [[ ! -f "$path" ]]; then curl -fLsS --retry 3 "$url" -o "$path"; fi
    printf '%s  %s\n' "$hash" "$path" | "${algorithm}sum" -c -
}

if [[ ! -x /workspace/toolchains/jdk-21.0.8+9/bin/javac ]]; then
    fetch_verified 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz' \
        "$downloads/jdk.tar.gz" f2dc5418092c43003db8f9005c4a286e1c0104fea96ccdd49e8ebd037cac9219 sha256
    tar -xzf "$downloads/jdk.tar.gz" -C /workspace/toolchains
fi
if [[ ! -x /workspace/toolchains/android-sdk/cmdline-tools/19.0/bin/sdkmanager ]]; then
    fetch_verified https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
        "$downloads/android-tools.zip" 5fdcc763663eefb86a5b8879697aa6088b041e70 sha1
    mkdir -p /workspace/toolchains/android-sdk/cmdline-tools
    unzip -q "$downloads/android-tools.zip" -d /workspace/toolchains/android-sdk/cmdline-tools
    mv /workspace/toolchains/android-sdk/cmdline-tools/cmdline-tools /workspace/toolchains/android-sdk/cmdline-tools/19.0
fi
source scripts/cloud-env.sh
python3 - <<'PY'
import os, subprocess
from urllib.parse import urlsplit
sdk = os.environ['ANDROID_HOME'] + '/cmdline-tools/19.0/bin/sdkmanager'
args = []
if os.environ.get('HTTPS_PROXY'):
    p = urlsplit(os.environ['HTTPS_PROXY'])
    args = ['--proxy=http', '--proxy_host=' + p.hostname, '--proxy_port=' + str(p.port or 80)]
subprocess.run([sdk, *args, '--licenses'], input='y\n' * 100, text=True, check=True)
subprocess.run([sdk, *args, 'platforms;android-35', 'build-tools;35.0.0', 'platform-tools'], check=True)
PY
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
