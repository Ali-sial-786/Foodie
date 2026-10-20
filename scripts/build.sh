#!/usr/bin/env bash
# Builds Nouri. Android needs the Android SDK and Google Maven; set NOURI_SKIP_ANDROID=1
# to build and test only the shared module (e.g. in restricted sandboxes).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${NOURI_SKIP_ANDROID:-}" == "1" ]]; then
  echo "NOURI_SKIP_ANDROID=1: building :shared only"
  ./gradlew :shared:jvmJar :shared:jvmTest --console=plain "$@"
else
  ./gradlew :shared:jvmJar :app:assembleDebug --console=plain "$@"
fi
