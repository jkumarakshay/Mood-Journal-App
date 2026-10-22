#!/usr/bin/env bash
# Source this file before Android commands in the Codex cloud workspace.
export JAVA_HOME=/workspace/toolchains/jdk-21.0.8+9
export ANDROID_HOME=/workspace/toolchains/android-sdk
export ANDROID_USER_HOME=/workspace/toolchains/android-user
export GRADLE_USER_HOME=/workspace/toolchains/gradle-home
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/19.0/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME" /workspace/toolchains/java-home
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=/workspace/toolchains/java-home"
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
    export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    mood_proxy_options="$(python3 - <<'PY'
import os
from urllib.parse import urlsplit
p = urlsplit(os.environ['HTTPS_PROXY'])
if p.hostname:
    print(f'-Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port or 80} '
          f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port or 80} '
          '-Dhttp.nonProxyHosts=localhost|127.*')
PY
)"
    export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS $mood_proxy_options"
    unset mood_proxy_options
fi
