#!/usr/bin/env bash
# Formats Kotlin sources with ktlint (https://pinterest.github.io/ktlint/).
# Usage: scripts/format.sh [--check]
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v ktlint >/dev/null 2>&1; then
  echo "ktlint is not installed. Install the official CLI (e.g. 'brew install ktlint' or the" >&2
  echo "release binary from https://github.com/pinterest/ktlint/releases) and re-run." >&2
  exit 1
fi

if [[ "${1:-}" == "--check" ]]; then
  ktlint "shared/src/**/*.kt" "app/src/**/*.kt" "!**/generated/**"
else
  ktlint --format "shared/src/**/*.kt" "app/src/**/*.kt" "!**/generated/**"
fi
