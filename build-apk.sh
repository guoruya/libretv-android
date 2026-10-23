#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
if [ ! -f signing.properties ]; then
    printf '%s\n' '缺少签名配置 signing.properties。请按 README.md 配置自己的签名密钥。' >&2
    exit 1
fi
./gradlew testDebugUnitTest lintDebug assembleRelease
node --test preview/server.test.mjs updates/server.test.mjs
python3 updates/package_release.py
