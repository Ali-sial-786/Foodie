#!/usr/bin/env bash
# Builds the debug APK and prints its path. Needs the Android SDK and Google Maven
# (CI also publishes it as the "nouri-debug-apk" artifact on every push).
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :app:assembleDebug --console=plain "$@"
ls -1 "$PWD"/app/build/outputs/apk/debug/*.apk
