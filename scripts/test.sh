#!/usr/bin/env bash
# Runs the shared test suite and (unless NOURI_SKIP_ANDROID=1) the app's unit,
# Robolectric and Compose tests.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :shared:jvmTest --console=plain "$@"
if [[ "${NOURI_SKIP_ANDROID:-}" != "1" ]]; then
  ./gradlew :app:testDebugUnitTest --console=plain "$@"
fi
echo "Reports: shared/build/reports/tests/ and app/build/reports/tests/"
