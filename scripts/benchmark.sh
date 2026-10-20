#!/usr/bin/env bash
# Runs the deterministic estimation benchmark (synthetic fixtures, mock provider)
# and prints the report. Measures the downstream pipeline, not photo recognition.
set -euo pipefail
cd "$(dirname "$0")/.."

NOURI_SKIP_ANDROID=1 ./gradlew :shared:jvmTest --tests "*BenchmarkTest*" --rerun --console=plain -q "$@"
cat shared/build/reports/benchmark/benchmark.md
