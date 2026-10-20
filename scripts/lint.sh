#!/usr/bin/env bash
# Static checks: a repository scan for hard-coded secrets and token logging, then
# Android lint (skipped when NOURI_SKIP_ANDROID=1).
set -euo pipefail
cd "$(dirname "$0")/.."

status=0
sources=(shared/src app/src)

# OpenAI-style keys, bearer tokens and private keys must never be committed.
if grep -rnE '(sk-(proj-)?[A-Za-z0-9_-]{20,}|Bearer [A-Za-z0-9._-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' "${sources[@]}" \
    --include='*.kt' --include='*.kts' --include='*.xml' --include='*.json' --include='*.properties'; then
  echo "ERROR: possible hard-coded secret (see above)" >&2
  status=1
fi

# Tokens and keys must never reach logs.
if grep -rnE '(Log\.[dviwe]|println|print)\(.*(accessToken|refreshToken|idToken|apiKey|access_token|refresh_token)' "${sources[@]}" --include='*.kt'; then
  echo "ERROR: possible credential logging (see above)" >&2
  status=1
fi

[[ $status -eq 0 ]] && echo "Secret/logging scan: clean"

if [[ "${NOURI_SKIP_ANDROID:-}" != "1" ]]; then
  ./gradlew :app:lintDebug --console=plain "$@"
  echo "Lint report: app/build/reports/lint-results-debug.html"
fi
exit $status
